import json
import logging
import re
from collections.abc import Awaitable, Callable
from contextlib import nullcontext
from functools import partial
from typing import Any, cast

from langgraph.graph import END, START, StateGraph

from app.clients.mcp_client import ReviewMcpClient
from app.config import Settings
from app.graph.state import AgentState
from app.graph.workflow import ContextWorkflow
from app.planning.binding_candidates import (
    BindingCandidateBuilder,
    BindingPlanExpander,
    BindingPlanValidationError,
)
from app.planning.context_serializer import build_model_context
from app.planning.document_block_plans import (
    DocumentBlockPlanBuilder,
    complete_and_validate_binding_plan,
    validate_document_operations,
)
from app.planning.program_document_plan import (
    ProgramDocumentPlanAssembler,
    build_block_content_context,
)
from app.planning.validator import AgentPlanValidator, PlanValidationError
from app.profiling import ProfileStage, RuntimeMemoryProfiler
from app.providers.base import ModelProvider, ModelProviderError
from app.schemas.binding_plans import BindingPlan, DocumentBlockPlan
from app.schemas.document_block_content import DocumentBlockContentPlan
from app.schemas.plans import AgentPlan, Decision
from app.tracing.trace_logger import traced

StatusCallback = Callable[[str, str, dict[str, Any]], Awaitable[None]]
LOGGER = logging.getLogger("devcollab.agent.document_plan")


class ReviewSubmissionError(RuntimeError):
    code = "REVIEW_SUBMISSION_FAILED"


class DocumentSyncWorkflow:
    def __init__(
        self,
        client: ReviewMcpClient,
        provider: ModelProvider,
        settings: Settings,
        on_status: StatusCallback,
        *,
        memory_profiler: RuntimeMemoryProfiler | None = None,
        profile_context: dict[str, str] | None = None,
    ) -> None:
        self._client = client
        self._provider = provider
        self._settings = settings
        self._on_status = on_status
        self._memory_profiler = memory_profiler
        self._profile_context = profile_context or {}
        self._validator = AgentPlanValidator(
            settings.agent_review_max_operations,
            settings.agent_review_max_evidence,
        )
        self._binding_candidates = BindingCandidateBuilder()
        self._binding_expander = BindingPlanExpander()
        self._block_plans = DocumentBlockPlanBuilder()
        self._program_plan = ProgramDocumentPlanAssembler()
        context = ContextWorkflow(client, settings)
        graph = StateGraph(AgentState)
        graph.add_node("load_workspace_context", context.load_workspace_context)
        graph.add_node("read_selected_code", context.read_selected_code)
        graph.add_node("list_existing_bindings", context.list_existing_bindings)
        graph.add_node("resolve_documents", context.resolve_documents)
        graph.add_node("read_document_structures", context.read_document_structures)
        graph.add_node("build_context_bundle", context.build_context_bundle)
        graph.add_node("plan_changes", self.plan_changes)
        graph.add_node("validate_plan", self.validate_plan)
        graph.add_node("repair_plan", self.repair_plan)
        graph.add_node("validate_repaired_plan", self.validate_repaired_plan)
        graph.add_node("plan_bindings", self.plan_bindings)
        graph.add_node("finish_no_change", self.finish_no_change)
        graph.add_node("submit_review", self.submit_review)
        graph.add_node("fail_run", self.fail_run)
        graph.add_edge(START, "load_workspace_context")
        graph.add_edge("load_workspace_context", "read_selected_code")
        graph.add_edge("read_selected_code", "list_existing_bindings")
        graph.add_edge("list_existing_bindings", "resolve_documents")
        graph.add_edge("resolve_documents", "read_document_structures")
        graph.add_edge("read_document_structures", "build_context_bundle")
        graph.add_edge("build_context_bundle", "plan_changes")
        graph.add_edge("plan_changes", "validate_plan")
        graph.add_conditional_edges(
            "validate_plan",
            self._route,
            {
                "NO_CHANGE": "finish_no_change",
                "SUBMIT_REVIEW": "submit_review",
                "REPAIR": "repair_plan",
                "BINDING": "plan_bindings",
                "INVALID": "fail_run",
            },
        )
        graph.add_edge("repair_plan", "validate_repaired_plan")
        graph.add_conditional_edges(
            "validate_repaired_plan",
            self._route,
            {
                "NO_CHANGE": "finish_no_change",
                "SUBMIT_REVIEW": "submit_review",
                "INVALID": "fail_run",
                "BINDING": "plan_bindings",
            },
        )
        graph.add_conditional_edges(
            "plan_bindings",
            self._route,
            {
                "NO_CHANGE": "finish_no_change",
                "SUBMIT_REVIEW": "submit_review",
            },
        )
        graph.add_edge("finish_no_change", END)
        graph.add_edge("submit_review", END)
        graph.add_edge("fail_run", END)
        self.graph = graph.compile()

    async def execute_context_bundle(self, state: AgentState) -> dict[str, Any]:
        mutable = cast(dict[str, Any], state)
        mutable.update(await self.plan_changes(state))
        mutable.update(await self.validate_plan(state))
        route = self._route(state)
        if route == "REPAIR":
            mutable.update(await self.repair_plan(state))
            mutable.update(await self.validate_repaired_plan(state))
            route = self._route(state)
        if route == "BINDING":
            mutable.update(await self.plan_bindings(state))
            route = self._route(state)
        if route == "NO_CHANGE":
            mutable.update(await self.finish_no_change(state))
            return mutable
        if route == "SUBMIT_REVIEW":
            mutable.update(await self.submit_review(state))
            return mutable
        await self.fail_run(state)
        raise AssertionError("unreachable")

    async def plan_changes(self, state: AgentState) -> dict[str, Any]:
        await self._on_status("PLANNING", "plan_changes", {})
        model_context = build_model_context(state["context_bundle"])
        code_candidates = self._binding_candidates.build_code(model_context)
        block_plans = self._block_plans.build(code_candidates)
        model_context["documentBlockPlans"] = [
            item.model_dump(mode="json", exclude_none=True) for item in block_plans
        ]
        self._log_block_contract(state, block_plans)
        if not block_plans:
            plan = AgentPlan(
                decision=Decision.NO_CHANGE,
                summary="未发现可生成正式职责文档的代码单元",
                rationale="程序未生成 DocumentBlockPlan，因此不调用模型。",
            )
            return {
                "model_context": model_context,
                "plan": plan,
                "validation_attempt": 0,
                "trace_events": state["trace_events"],
            }

        block_content_context = build_block_content_context(
            model_context,
            code_candidates,
            block_plans,
        )
        input_characters = len(str(block_content_context))
        if input_characters > self._settings.agent_model_max_input_characters:
            raise ValueError("Model context exceeds configured input limit")
        with self._profile_stage("DOCUMENT_PROPOSAL") as profile_stage:
            profile_stage.attribute("promptCharacters", input_characters)
            content_plan = await traced(
                cast(dict[str, Any], state),
                "generate_document_blocks",
                None,
                lambda: self._provider.generate_document_blocks(block_content_context),
                input_characters,
            )
            repaired_block_keys: list[str] = []
            for block in tuple(content_plan.blocks):
                if block.status != "INSUFFICIENT_EVIDENCE":
                    continue
                block_key = block.blockKey
                LOGGER.warning(
                    "runId=%s phase=initial_content validation=failed "
                    "errors=%s",
                    state.get("run_id"),
                    json.dumps(
                        [{
                            "path": f"blocks.{block_key}",
                            "code": "BLOCK_INSUFFICIENT_EVIDENCE",
                            "message": "Required Block returned insufficient evidence",
                        }],
                        ensure_ascii=False,
                        separators=(",", ":"),
                    ),
                )
                selected_context = {
                    "workspace": block_content_context.get("workspace", {}),
                    "task": block_content_context.get("task", {}),
                    "blocks": [
                        item
                        for item in block_content_context.get("blocks", [])
                        if item.get("blockKey") == block_key
                    ],
                }
                repaired = await traced(
                    cast(dict[str, Any], state),
                    f"repair_document_block:{block_key}",
                    None,
                    partial(
                        self._provider.repair_document_block,
                        selected_context,
                        previous_block=block.model_dump(
                            mode="json", exclude_none=True
                        ),
                        validation_errors=[{
                            "path": f"blocks.{block_key}",
                            "code": "BLOCK_INSUFFICIENT_EVIDENCE",
                            "message": (
                                "Use the supplied code evidence to write only this "
                                "required Block; do not change its structure"
                            ),
                        }],
                    ),
                    len(str(selected_context)),
                )
                content_plan = self._program_plan.replace_block(
                    content_plan, repaired
                )
                repaired_block_keys.append(block_key)
            assembled = self._program_plan.assemble(
                model_context,
                code_candidates,
                block_plans,
                content_plan,
            )
            profile_stage.attribute(
                "documentOperationCount", len(assembled.agent_plan.operations)
            )
        self._log_plan_shape(state, "program_assembled", assembled.agent_plan)
        return {
            "model_context": model_context,
            "block_content_context": block_content_context,
            "block_content_plan": assembled.block_content_plan,
            "program_binding_plan": assembled.binding_plan,
            "plan": assembled.agent_plan,
            "validation_attempt": 0,
            "repaired_block_keys": repaired_block_keys,
            "trace_events": state["trace_events"],
        }

    async def validate_plan(self, state: AgentState) -> dict[str, Any]:
        await self._on_status("VALIDATING", "validate_plan", {})
        plan = state.get("plan")
        if not isinstance(plan, AgentPlan):
            return {"plan_outcome": "INVALID"}
        if plan.bindingProposals:
            return {
                "validation_errors": [
                    {
                        "path": "bindingProposals",
                        "code": "BINDING_PASS_REQUIRED",
                        "message": "Document planning must leave bindingProposals empty",
                    }
                ],
                "plan_outcome": "INVALID",
            }
        try:
            block_plans = tuple(
                DocumentBlockPlan.model_validate(item)
                for item in state["model_context"].get("documentBlockPlans", [])
            )
            validate_document_operations(plan, block_plans)
            valid = self._validator.validate(plan, state["model_context"])
        except (PlanValidationError, BindingPlanValidationError) as exc:
            errors = (
                exc.safe_details() if isinstance(exc, PlanValidationError) else exc.issues
            )
            self._log_validation_failure(state, "initial_semantic", errors, plan)
            repair_keys = self._repairable_block_keys(errors, plan)
            if set(repair_keys) & set(state.get("repaired_block_keys", [])):
                repair_keys = []
            return {
                "validation_errors": errors,
                "invalid_block_keys": repair_keys,
                "plan_outcome": "REPAIR" if repair_keys else "INVALID",
            }
        self._log_validation_success(state, "initial", valid)
        return {
            "plan": valid,
            "decision": valid.decision.value,
            "summary": valid.summary,
            "plan_outcome": (
                Decision.NO_CHANGE.value
                if valid.decision == Decision.NO_CHANGE
                else "BINDING"
            ),
        }

    async def repair_plan(self, state: AgentState) -> dict[str, Any]:
        await self._on_status("REPAIRING_PLAN", "repair_plan", {})
        content_plan = cast(DocumentBlockContentPlan, state["block_content_plan"])
        block_context = state["block_content_context"]
        errors = state.get("validation_errors", [])
        for block_key in state.get("invalid_block_keys", []):
            previous = next(item for item in content_plan.blocks if item.blockKey == block_key)
            selected_context = {
                "workspace": block_context.get("workspace", {}),
                "task": block_context.get("task", {}),
                "blocks": [
                    item for item in block_context.get("blocks", [])
                    if item.get("blockKey") == block_key
                ],
            }
            block_errors = [
                item for item in errors
                if self._error_targets_block(item, block_key, cast(AgentPlan, state["plan"]))
            ]
            repaired = await traced(
                cast(dict[str, Any], state),
                f"repair_document_block:{block_key}",
                None,
                partial(
                    self._provider.repair_document_block,
                    selected_context,
                    previous_block=previous.model_dump(mode="json", exclude_none=True),
                    validation_errors=block_errors,
                ),
                len(str(block_errors)),
            )
            if repaired.blockKey != block_key:
                raise BindingPlanValidationError(
                    [{
                        "path": "blockKey",
                        "code": "DOCUMENT_BLOCK_CONTENT_PLAN_MISMATCH",
                        "message": "Block repair returned a different blockKey",
                    }]
                )
            content_plan = self._program_plan.replace_block(content_plan, repaired)

        block_plans = tuple(
            DocumentBlockPlan.model_validate(item)
            for item in state["model_context"].get("documentBlockPlans", [])
        )
        code_candidates = self._binding_candidates.build_code(state["model_context"])
        assembled = self._program_plan.assemble(
            state["model_context"], code_candidates, block_plans, content_plan
        )
        self._log_plan_shape(state, "block_repair_assembled", assembled.agent_plan)
        return {
            "plan": assembled.agent_plan,
            "block_content_plan": assembled.block_content_plan,
            "program_binding_plan": assembled.binding_plan,
            "validation_attempt": 1,
            "repaired_block_keys": list({
                *state.get("repaired_block_keys", []),
                *state.get("invalid_block_keys", []),
            }),
            "trace_events": state["trace_events"],
        }

    async def validate_repaired_plan(self, state: AgentState) -> dict[str, Any]:
        await self._on_status("VALIDATING", "validate_repaired_plan", {})
        plan = state.get("plan")
        if not isinstance(plan, AgentPlan):
            return {"plan_outcome": "INVALID"}
        if plan.bindingProposals:
            return {
                "validation_errors": [
                    {
                        "path": "bindingProposals",
                        "code": "BINDING_PASS_REQUIRED",
                        "message": "Document planning must leave bindingProposals empty",
                    }
                ],
                "plan_outcome": "INVALID",
            }
        try:
            block_plans = tuple(
                DocumentBlockPlan.model_validate(item)
                for item in state["model_context"].get("documentBlockPlans", [])
            )
            validate_document_operations(plan, block_plans)
            valid = self._validator.validate(plan, state["model_context"])
        except (PlanValidationError, BindingPlanValidationError) as exc:
            errors = (
                exc.safe_details() if isinstance(exc, PlanValidationError) else exc.issues
            )
            self._log_validation_failure(state, "repair_semantic", errors, plan)
            return {
                "validation_errors": errors,
                "plan_outcome": "INVALID",
            }
        self._log_validation_success(state, "repair", valid)
        return {
            "plan": valid,
            "decision": valid.decision.value,
            "summary": valid.summary,
            "plan_outcome": (
                Decision.NO_CHANGE.value
                if valid.decision == Decision.NO_CHANGE
                else "BINDING"
            ),
        }

    async def plan_bindings(self, state: AgentState) -> dict[str, Any]:
        await self._on_status("PLANNING_BINDINGS", "plan_bindings", {})
        document_plan = cast(AgentPlan, state["plan"])
        candidates = self._binding_candidates.build(state["model_context"], document_plan)
        await self._on_status(
            "PLANNING_BINDINGS",
            "binding_candidates",
            {
                "codeCandidateCount": len(candidates.code),
                "documentCandidateCount": len(candidates.documents),
            },
        )
        if not candidates.code or not candidates.documents:
            return {
                "plan": document_plan,
                "decision": document_plan.decision.value,
                "summary": document_plan.summary,
                "plan_outcome": document_plan.decision.value,
            }

        program_binding_plan = state.get("program_binding_plan")
        if isinstance(program_binding_plan, BindingPlan):
            binding_plan = complete_and_validate_binding_plan(
                program_binding_plan,
                candidates.code,
                candidates.block_plans,
            )
            expanded = self._binding_expander.expand(
                document_plan,
                binding_plan,
                candidates,
            )
            valid = self._validator.validate(expanded, state["model_context"])
            return {
                "plan": valid,
                "decision": valid.decision.value,
                "summary": valid.summary,
                "plan_outcome": valid.decision.value,
                "trace_events": state["trace_events"],
            }

        payload = candidates.model_payload()
        previous: dict[str, Any] | None = None
        errors: list[dict[str, str]] | None = None
        for attempt in range(2):
            try:
                with self._profile_stage("BINDING_PROPOSAL") as profile_stage:
                    profile_stage.attribute(
                        "candidateCount",
                        len(candidates.code) + len(candidates.documents),
                    )
                    binding_plan = await traced(
                        cast(dict[str, Any], state),
                        "plan_bindings" if attempt == 0 else "repair_bindings",
                        None,
                        partial(
                            self._provider.plan_block_bindings,
                            payload,
                            previous_plan=previous,
                            validation_errors=errors,
                        ),
                        len(str(payload if attempt == 0 else errors)),
                    )
                    binding_plan = complete_and_validate_binding_plan(
                        binding_plan,
                        candidates.code,
                        candidates.block_plans,
                    )
                    profile_stage.attribute("bindingCount", len(binding_plan.selections))
                expanded = self._binding_expander.expand(document_plan, binding_plan, candidates)
                valid = self._validator.validate(expanded, state["model_context"])
                return {
                    "plan": valid,
                    "decision": valid.decision.value,
                    "summary": valid.summary,
                    "plan_outcome": valid.decision.value,
                    "trace_events": state["trace_events"],
                }
            except ModelProviderError as exc:
                if exc.code != "MODEL_INVALID_RESPONSE" or attempt == 1:
                    raise
                previous = exc.raw_plan or {}
                errors = [
                    {
                        "path": "$",
                        "code": "MODEL_INVALID_RESPONSE",
                        "message": "Return a complete BindingPlan matching the schema",
                    }
                ]
            except BindingPlanValidationError as exc:
                if attempt == 1:
                    raise
                previous = binding_plan.model_dump(mode="json")
                errors = exc.issues
        raise AssertionError("binding repair loop must return or raise")

    async def finish_no_change(self, state: AgentState) -> dict[str, Any]:
        plan = cast(AgentPlan, state["plan"])
        await self._on_status(
            "NO_CHANGE",
            "finish_no_change",
            {"decision": Decision.NO_CHANGE.value, "summary": plan.summary},
        )
        return {
            "decision": Decision.NO_CHANGE.value,
            "summary": plan.summary,
        }

    async def submit_review(self, state: AgentState) -> dict[str, Any]:
        await self._on_status(
            "SUBMITTING_REVIEW",
            "submit_review",
            {
                "decision": Decision.SUBMIT_REVIEW.value,
                "summary": state.get("summary"),
            },
        )
        plan = cast(AgentPlan, state["plan"])
        try:
            with self._profile_stage("REVIEW_BUILD") as profile_stage:
                profile_stage.attribute("reviewOperationCount", len(plan.operations))
                profile_stage.attribute("bindingCount", len(plan.bindingProposals))
                result = await traced(
                    cast(dict[str, Any], state),
                    "submit_review",
                    "devcollab.review.submit_document_change",
                    lambda: self._client.submit_document_change(
                        plan,
                        workspace_id=state["workspace_id"],
                        run_id=state["run_id"],
                        authorization=state["authorization"],
                    ),
                    len(str(plan.model_dump(exclude_none=True))),
                )
        except Exception as exc:
            if hasattr(exc, "code"):
                raise
            raise ReviewSubmissionError("Could not submit review request") from exc
        change_request_id = result.get("changeRequestId")
        if result.get("status") != "PENDING" or not change_request_id:
            raise ReviewSubmissionError("MCP returned an invalid review result")
        await self._on_status(
            "REVIEW_SUBMITTED",
            "submit_review",
            {
                "decision": Decision.SUBMIT_REVIEW.value,
                "summary": plan.summary,
                "changeRequestId": str(change_request_id),
            },
        )
        return {
            "decision": Decision.SUBMIT_REVIEW.value,
            "summary": plan.summary,
            "change_request_id": str(change_request_id),
        }

    def _profile_stage(self, name: str) -> Any:
        if self._memory_profiler is None:
            return nullcontext(ProfileStage.noop())
        return self._memory_profiler.stage(name, **self._profile_context)

    @staticmethod
    def _log_block_contract(
        state: AgentState,
        block_plans: tuple[DocumentBlockPlan, ...],
    ) -> None:
        LOGGER.info(
            "runId=%s phase=document_block_contract plans=%s",
            state.get("run_id"),
            json.dumps(
                DocumentSyncWorkflow._safe_block_contract(block_plans),
                ensure_ascii=False,
                separators=(",", ":"),
            ),
        )

    @staticmethod
    def _safe_block_contract(
        block_plans: tuple[DocumentBlockPlan, ...],
    ) -> list[dict[str, Any]]:
        return [
            {
                "blockKey": item.blockKey,
                "targetKind": item.targetKind.value,
                "sortOrder": item.sortOrder,
                "primaryCandidateIds": item.primaryCandidateIds,
                "supportingCandidateIds": item.supportingCandidateIds,
                "requiredCandidateIds": item.requiredCandidateIds,
            }
            for item in block_plans
        ]

    @classmethod
    def _log_plan_shape(
        cls,
        state: AgentState,
        phase: str,
        plan: AgentPlan | dict[str, Any] | None,
    ) -> None:
        LOGGER.info(
            "runId=%s phase=%s plan=%s",
            state.get("run_id"),
            phase,
            json.dumps(
                cls._safe_plan_shape(plan),
                ensure_ascii=False,
                separators=(",", ":"),
            ),
        )

    @classmethod
    def _log_validation_failure(
        cls,
        state: AgentState,
        phase: str,
        errors: list[dict[str, str]],
        plan: AgentPlan | dict[str, Any] | None,
    ) -> None:
        LOGGER.warning(
            "runId=%s phase=%s validation=failed errors=%s contracts=%s plan=%s",
            state.get("run_id"),
            phase,
            json.dumps(errors, ensure_ascii=False, separators=(",", ":")),
            json.dumps(
                cls._safe_block_contract(
                    tuple(
                        DocumentBlockPlan.model_validate(item)
                        for item in state.get("model_context", {}).get(
                            "documentBlockPlans", []
                        )
                    )
                ),
                ensure_ascii=False,
                separators=(",", ":"),
            ),
            json.dumps(
                cls._safe_plan_shape(plan),
                ensure_ascii=False,
                separators=(",", ":"),
            ),
        )

    @classmethod
    def _log_validation_success(
        cls,
        state: AgentState,
        phase: str,
        plan: AgentPlan,
    ) -> None:
        LOGGER.info(
            "runId=%s phase=%s validation=success plan=%s",
            state.get("run_id"),
            phase,
            json.dumps(
                cls._safe_plan_shape(plan),
                ensure_ascii=False,
                separators=(",", ":"),
            ),
        )

    @staticmethod
    def _safe_plan_shape(
        plan: AgentPlan | dict[str, Any] | None,
    ) -> dict[str, Any]:
        raw = (
            plan.model_dump(mode="json", exclude_none=True)
            if isinstance(plan, AgentPlan)
            else plan or {}
        )
        operations = raw.get("operations", [])
        evidence = raw.get("evidence", [])
        bindings = raw.get("bindingProposals", [])
        return {
            "decision": raw.get("decision"),
            "operations": [
                {
                    "clientOperationId": item.get("clientOperationId"),
                    "sequenceNumber": item.get("sequenceNumber"),
                    "operationType": item.get("operationType"),
                    "documentId": item.get("documentId"),
                    "createdDocumentClientOperationId": item.get(
                        "createdDocumentClientOperationId"
                    ),
                    "blockId": item.get("blockId"),
                    "heading": str(item.get("proposedPlainText") or "")
                    .lstrip()
                    .splitlines()[:1],
                    "contentCharacters": len(
                        str(item.get("proposedPlainText") or "")
                    ),
                }
                for item in operations
                if isinstance(item, dict)
            ],
            "bindingProposalCount": len(bindings) if isinstance(bindings, list) else None,
            "evidence": [
                {
                    "clientOperationId": item.get("clientOperationId"),
                    "filePath": item.get("filePath"),
                    "startLine": item.get("startLine"),
                    "endLine": item.get("endLine"),
                }
                for item in evidence
                if isinstance(item, dict)
            ],
        }

    async def fail_run(self, state: AgentState) -> dict[str, Any]:
        raise PlanValidationError(
            [self._issue_from_dict(item) for item in state.get("validation_errors", [])]
        )

    @staticmethod
    def _issue_from_dict(item: dict[str, str]) -> Any:
        from app.schemas.plans import PlanValidationIssue

        return PlanValidationIssue.model_validate(item)

    @staticmethod
    def _route(state: AgentState) -> str:
        return state["plan_outcome"]

    @staticmethod
    def _repairable_block_keys(
        errors: list[dict[str, str]],
        plan: AgentPlan,
    ) -> list[str]:
        repairable_codes = {
            "UNSUPPORTED_EXTERNAL_RELATION",
            "UNSUPPORTED_INFERRED_SEMANTICS",
            "DOCUMENT_BLOCK_FORBIDDEN_CLAIM",
        }
        keys: list[str] = []
        for error in errors:
            if error.get("code") not in repairable_codes:
                return []
            block_key = DocumentSyncWorkflow._block_key_for_error(error, plan)
            if block_key is None:
                return []
            if block_key not in keys:
                keys.append(block_key)
        return keys

    @staticmethod
    def _error_targets_block(
        error: dict[str, str],
        block_key: str,
        plan: AgentPlan,
    ) -> bool:
        return DocumentSyncWorkflow._block_key_for_error(error, plan) == block_key

    @staticmethod
    def _block_key_for_error(
        error: dict[str, str],
        plan: AgentPlan,
    ) -> str | None:
        path = error.get("path", "")
        direct = re.match(r"operations\.([^\.\[]+)", path)
        if direct:
            return direct.group(1)
        indexed = re.match(r"operations\[(\d+)\]", path)
        if not indexed:
            return None
        index = int(indexed.group(1))
        if index <= 0 or index >= len(plan.operations):
            return None
        return plan.operations[index].clientOperationId
