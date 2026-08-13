from __future__ import annotations

import asyncio
from collections.abc import Awaitable, Callable
from typing import Any

from app.planning.unit_plan_validator import (
    UnitPlanValidationError,
    UnitPlanValidationIssue,
    UnitPlanValidator,
)
from app.providers.base import ModelProvider, ModelProviderError
from app.schemas.unit_plans import UnitPlan


class DeepSeekUnitPlanner:
    def __init__(
        self,
        provider: ModelProvider,
        *,
        max_files_per_unit: int,
        max_units: int,
        on_phase: Callable[[str], Awaitable[None]] | None = None,
        batch_concurrency: int = 2,
    ) -> None:
        self._provider = provider
        self._validator = UnitPlanValidator(
            max_files_per_unit=max_files_per_unit,
            max_units=max_units,
        )
        self._on_phase = on_phase
        self._repair_used = False
        self._batch_concurrency = max(1, batch_concurrency)

    async def plan(
        self,
        project_index: dict[str, Any] | list[dict[str, Any]],
        *,
        validation_index: dict[str, Any] | None = None,
    ) -> UnitPlan:
        self._repair_used = False
        if self._on_phase:
            await self._on_phase("PLANNING_UNITS")
        if isinstance(project_index, list):
            if not project_index:
                raise ValueError("At least one ProjectIndex batch is required")
            if validation_index is None:
                raise ValueError("A validation index is required for batched planning")
            return await self._plan_batches(project_index, validation_index)
        return await self._plan_once(project_index)

    async def _plan_candidate(
        self,
        batch: dict[str, Any],
        semaphore: asyncio.Semaphore,
    ) -> dict[str, Any] | None:
        async with semaphore:
            try:
                candidate = await self._provider.plan_project_units(batch)
            except ModelProviderError as exc:
                if exc.code != "MODEL_INVALID_RESPONSE":
                    raise
                candidate = await self._repair_candidate(
                    batch,
                    exc.raw_plan or {},
                    exc.validation_errors
                    or [{
                        "path": "$",
                        "code": "MODEL_INVALID_RESPONSE",
                        "message": "Return a schema-valid UnitPlan for this batch",
                    }],
                )
                if candidate is None:
                    return None
            candidate_issues: list[dict[str, str]] = []
            try:
                self._validator.validate(candidate, batch)
            except UnitPlanValidationError as exc:
                candidate_issues = exc.safe_details()
                repaired = await self._repair_candidate(
                    batch,
                    candidate.model_dump(mode="json"),
                    candidate_issues,
                )
                if repaired is not None:
                    candidate = repaired
                    candidate_issues = []
            return {
                "batch": batch.get("batch"),
                "topLevelModules": batch.get("topLevelModules", []),
                "unitPlan": candidate.model_dump(mode="json"),
                "validationIssues": candidate_issues,
            }

    async def _repair_candidate(
        self,
        batch: dict[str, Any],
        previous_plan: dict[str, Any],
        validation_errors: list[dict[str, str]],
    ) -> UnitPlan | None:
        try:
            repaired = await self._provider.plan_project_units(
                batch,
                previous_plan=previous_plan,
                validation_errors=validation_errors,
            )
            return self._validator.validate(repaired, batch)
        except UnitPlanValidationError:
            return None
        except ModelProviderError as exc:
            if exc.code != "MODEL_INVALID_RESPONSE":
                raise
            return None

    async def _plan_once(self, project_index: dict[str, Any]) -> UnitPlan:
        try:
            plan = await self._provider.plan_project_units(project_index)
        except ModelProviderError as exc:
            if exc.code != "MODEL_INVALID_RESPONSE":
                raise
            return await self._repair(
                project_index,
                exc.raw_plan or {},
                [
                    {
                        "path": "$",
                        "code": "MODEL_INVALID_RESPONSE",
                        "message": "Return a complete UnitPlan matching the schema",
                    }
                ],
            )
        if self._on_phase:
            await self._on_phase("VALIDATING_UNIT_PLAN")
        try:
            return self._validator.validate(plan, project_index)
        except UnitPlanValidationError as exc:
            return await self._repair(
                project_index,
                plan.model_dump(mode="json"),
                exc.safe_details(),
            )

    async def _plan_batches(
        self,
        batches: list[dict[str, Any]],
        validation_index: dict[str, Any],
    ) -> UnitPlan:
        # 候选 batch 并发规划（batch_concurrency 控并发、防 DeepSeek 限流），
        # 结果按原顺序收集——consolidation 依赖 batch 顺序展示给模型。
        semaphore = asyncio.Semaphore(self._batch_concurrency)
        candidates = await asyncio.gather(
            *(self._plan_candidate(batch, semaphore) for batch in batches)
        )
        candidate_plans = [c for c in candidates if c is not None]
        if not candidate_plans:
            raise UnitPlanValidationError(
                [
                    UnitPlanValidationIssue(
                        "candidateBatchPlans",
                        "NO_VALID_CANDIDATE_SCHEMA",
                        "No batch returned a schema-valid UnitPlan",
                    )
                ]
            )
        consolidation_index = {
            "repositoryId": validation_index["repositoryId"],
            "revision": validation_index["revision"],
            "topLevelModules": validation_index.get("topLevelModules", []),
            "files": [
                {
                    "filePath": item["filePath"],
                    "language": item.get("language"),
                    "moduleKey": item.get("moduleKey"),
                    "layerHint": item.get("layerHint"),
                }
                for item in validation_index["files"]
            ],
            "fileDependencies": validation_index.get("fileDependencies", []),
            "documents": validation_index.get("documents", []),
            "planningMode": "CONSOLIDATE_BATCH_PLANS",
            "candidateBatchPlans": candidate_plans,
            "requestedMaxUnits": validation_index.get("requestedMaxUnits"),
            "constraints": {
                "maxUnits": self._validator.max_units,
                "maxFilesPerUnit": self._validator.max_files_per_unit,
            },
        }
        try:
            plan = await self._provider.plan_project_units(consolidation_index)
        except ModelProviderError as exc:
            if exc.code != "MODEL_INVALID_RESPONSE":
                raise
            return await self._repair(
                validation_index,
                exc.raw_plan or {},
                [
                    {
                        "path": "$",
                        "code": "MODEL_INVALID_RESPONSE",
                        "message": "Return one complete repository-wide UnitPlan",
                    }
                ],
                provider_index=consolidation_index,
            )
        if self._on_phase:
            await self._on_phase("VALIDATING_UNIT_PLAN")
        try:
            return self._validator.validate(plan, validation_index)
        except UnitPlanValidationError as exc:
            return await self._repair(
                validation_index,
                plan.model_dump(mode="json"),
                exc.safe_details(),
                provider_index=consolidation_index,
            )

    async def _repair(
        self,
        project_index: dict[str, Any],
        previous_plan: dict[str, Any],
        validation_errors: list[dict[str, str]],
        *,
        provider_index: dict[str, Any] | None = None,
    ) -> UnitPlan:
        if self._repair_used:
            raise UnitPlanValidationError(
                [
                    UnitPlanValidationIssue(
                        "$",
                        "REPAIR_LIMIT_EXCEEDED",
                        "The single Planner repair has already been used",
                    )
                ]
            )
        self._repair_used = True
        if self._on_phase:
            await self._on_phase("PLANNING_UNITS")
        repaired = await self._provider.plan_project_units(
            provider_index or project_index,
            previous_plan=previous_plan,
            validation_errors=validation_errors,
        )
        if self._on_phase:
            await self._on_phase("VALIDATING_UNIT_PLAN")
        return self._validator.validate(repaired, project_index)
