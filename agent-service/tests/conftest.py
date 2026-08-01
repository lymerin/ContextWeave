import asyncio
from datetime import UTC, datetime, timedelta
from typing import Any
from uuid import UUID

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app
from app.runtime.semantic_planner import PlannedSemanticUnit
from app.schemas.binding_plans import BindingPlan, BindingSelection
from app.schemas.plans import AgentPlan, BindingAction, Decision
from app.schemas.unit_plans import UnitPlan


class MemoryRunStore:
    def __init__(self) -> None:
        self.values: dict[str, dict[str, object]] = {}
        self.ttls: dict[str, int] = {}

    async def save(self, run_id: str, payload: dict[str, object], ttl: int) -> None:
        self.values[run_id] = payload
        self.ttls[run_id] = ttl

    async def get(self, run_id: str) -> dict[str, object] | None:
        return self.values.get(run_id)

    async def save_job(self, job_id: str, payload: dict[str, object], ttl: int) -> None:
        self.values[f"job:{job_id}"] = payload
        self.ttls[f"job:{job_id}"] = ttl

    async def get_job(self, job_id: str) -> dict[str, object] | None:
        return self.values.get(f"job:{job_id}")


class MemoryAgentJobRepository:
    def __init__(self) -> None:
        self.jobs: dict[UUID, dict[str, Any]] = {}
        self.units: dict[UUID, dict[str, Any]] = {}
        self.lock = asyncio.Lock()
        self.worker_heartbeats: dict[str, datetime] = {}
        self.job_files: dict[UUID, list[dict[str, Any]]] = {}

    async def create_job(self, job: dict[str, Any], unit: dict[str, Any]) -> None:
        async with self.lock:
            now = job["created_at"]
            self.jobs[job["id"]] = {
                **job,
                "status": "QUEUED",
                "result": None,
                "total_units": 1,
                "completed_units": 0,
                "failed_units": 0,
                "review_request_ids": [],
                "error_code": None,
                "error_message": None,
                "created_at": now,
                "updated_at": now,
                "started_at": None,
                "completed_at": None,
                "current_phase": None,
                "phase": None,
                "discovered_file_count": 0,
                "supported_code_count": 0,
                "skipped_file_count": 0,
                "skipped_reason_counts": {},
                "metadata_parsed_count": 0,
                "metadata_failed_count": 0,
                "bound_file_count": 0,
                "unbound_file_count": 0,
                "analysis_unit_count": 0,
                "overlapping_file_count": 0,
                "planner_status": None,
            }
            self.units[unit["id"]] = {
                **unit,
                "job_id": job["id"],
                "ordinal": 1,
                "status": "PENDING",
                "phase": None,
                "attempt": 0,
                "worker_id": None,
                "lease_expires_at": None,
                "next_attempt_at": None,
                "created_at": now,
                "unit_kind": unit.get("unit_kind", "CURRENT_FILE_ANALYSIS"),
                "language_set": [],
                "grouping_reasons": [],
            }

    async def get_job(self, job_id: UUID) -> dict[str, Any] | None:
        job = self.jobs.get(job_id)
        if job is None:
            return None
        unit = next(value for value in self.units.values() if value["job_id"] == job_id)
        semantic = [
            value
            for value in self.units.values()
            if value["job_id"] == job_id
            and value["unit_kind"] == "SEMANTIC_ANALYSIS"
        ]
        return {
            **job,
            "current_phase": job.get("phase") or unit["phase"],
            "planned_unit_count": len(semantic),
            "pending_unit_count": sum(
                item["status"] in {"PENDING", "RETRY_WAITING"} for item in semantic
            ),
            "running_unit_count": sum(
                item["status"] in {"CLAIMED", "RUNNING"} for item in semantic
            ),
            "completed_unit_count": sum(
                item["status"] == "COMPLETED" for item in semantic
            ),
            "failed_unit_count": sum(item["status"] == "FAILED" for item in semantic),
            "no_change_unit_count": sum(
                item.get("result") == "NO_CHANGE" for item in semantic
            ),
            "review_submitted_unit_count": sum(
                item.get("result") == "REVIEW_SUBMITTED" for item in semantic
            ),
            "current_unit_names": [
                str(item.get("display_name"))
                for item in semantic
                if item["status"] in {"CLAIMED", "RUNNING"}
            ],
        }

    async def claim_next_unit(
        self, worker_id: str, lease_seconds: int
    ) -> dict[str, Any] | None:
        async with self.lock:
            now = datetime.now(UTC)
            for unit in self.units.values():
                if (
                    unit["status"] in {"CLAIMED", "RUNNING"}
                    and unit["lease_expires_at"] is not None
                    and unit["lease_expires_at"] <= now
                    and unit["attempt"] >= unit["max_attempts"]
                ):
                    unit.update(
                        status="FAILED",
                        lease_expires_at=None,
                        error_code="WORKER_LEASE_EXPIRED",
                    )
                    self.jobs[unit["job_id"]].update(
                        status="FAILED",
                        failed_units=1,
                        error_code="WORKER_LEASE_EXPIRED",
                    )
                    continue
                available = (
                    unit["status"] in {"PENDING", "RETRY_WAITING"}
                    and (unit["next_attempt_at"] is None or unit["next_attempt_at"] <= now)
                ) or (
                    unit["status"] in {"CLAIMED", "RUNNING"}
                    and unit["lease_expires_at"] is not None
                    and unit["lease_expires_at"] <= now
                )
                if unit["status"] == "READY_FOR_ANALYSIS":
                    continue
                if unit["unit_kind"] == "SEMANTIC_ANALYSIS":
                    candidate_docs = {
                        str(item["documentId"]) for item in unit.get("documents", [])
                    }
                    active_docs = {
                        str(item["documentId"])
                        for candidate in self.units.values()
                        if candidate["id"] != unit["id"]
                        and candidate["status"] in {"CLAIMED", "RUNNING"}
                        for item in candidate.get("documents", [])
                    }
                    if candidate_docs & active_docs:
                        continue
                if not available or unit["attempt"] >= unit["max_attempts"]:
                    continue
                unit.update(
                    status="CLAIMED",
                    phase=(
                        "DISCOVERING_FILES"
                        if unit["unit_kind"] == "PROJECT_DISCOVERY"
                        else "LOADING_CONTEXT"
                    ),
                    worker_id=worker_id,
                    attempt=unit["attempt"] + 1,
                    lease_expires_at=now + timedelta(seconds=lease_seconds),
                )
                job = self.jobs[unit["job_id"]]
                job.update(status="RUNNING", started_at=job["started_at"] or now)
                return {**unit, "job": dict(job)}
        return None

    async def get_unit_context(self, unit_id: UUID) -> dict[str, Any]:
        return dict(self.units[unit_id])

    async def heartbeat(self, unit_id: UUID, worker_id: str, lease_seconds: int) -> bool:
        unit = self.units[unit_id]
        if unit["worker_id"] != worker_id or unit["status"] not in {"CLAIMED", "RUNNING"}:
            return False
        unit.update(
            status="RUNNING",
            lease_expires_at=datetime.now(UTC) + timedelta(seconds=lease_seconds),
        )
        return True

    async def update_phase(self, unit_id: UUID, worker_id: str, phase: str) -> bool:
        unit = self.units[unit_id]
        if unit["worker_id"] != worker_id:
            return False
        unit.update(status="RUNNING", phase=phase)
        self.jobs[unit["job_id"]]["phase"] = phase
        return True

    async def complete_project_discovery(
        self,
        unit_id: UUID,
        worker_id: str,
        files: list[dict[str, Any]],
        units: list[PlannedSemanticUnit],
        stats: dict[str, Any],
        max_attempts: int,
        execution_limit: int,
    ) -> None:
        unit = self.units[unit_id]
        if unit["worker_id"] != worker_id:
            raise RuntimeError("lease lost")
        job_id = unit["job_id"]
        self.job_files[job_id] = list(files)
        unit.update(
            status="COMPLETED",
            phase="COMPLETED",
            result=None,
        )
        now = datetime.now(UTC)
        for execution_index, planned in enumerate(units, 1):
            ordinal = execution_index + 1
            self.units[planned.id] = {
                "id": planned.id,
                "job_id": job_id,
                "ordinal": ordinal,
                "status": (
                    "PENDING"
                    if execution_limit == 0 or execution_index <= execution_limit
                    else "READY_FOR_ANALYSIS"
                ),
                "phase": "EXECUTING_UNITS",
                "attempt": 0,
                "max_attempts": max_attempts,
                "worker_id": None,
                "lease_expires_at": None,
                "next_attempt_at": None,
                "created_at": now,
                "unit_kind": "SEMANTIC_ANALYSIS",
                "semantic_key": planned.semantic_key,
                "display_name": planned.display_name,
                "summary": planned.summary,
                "semantic_kind": planned.semantic_kind,
                "primary_directory": planned.primary_directory,
                "language_set": list(planned.language_set),
                "estimated_size_bytes": planned.estimated_size_bytes,
                "grouping_reasons": list(planned.grouping_reasons),
                "unit_fingerprint": planned.unit_fingerprint,
                "files": [
                    {
                        "filePath": item.file_path,
                        "role": item.role,
                        "relevanceReason": item.relevance_reason,
                        "ordinal": item.ordinal,
                    }
                    for item in planned.files
                ],
                "documents": [
                    {
                        "documentId": item.document_id,
                        "relationship": item.relationship,
                        "source": item.source,
                        "ordinal": item.ordinal,
                    }
                    for item in planned.documents
                ],
            }
        self.jobs[job_id].update(
            status="RUNNING",
            phase="EXECUTING_UNITS",
            planner_status="COMPLETED",
            total_units=len(units),
            completed_units=0,
            failed_units=0,
            review_request_ids=[],
            completed_at=None,
            **stats,
        )

    async def list_semantic_units(
        self, job_id: UUID, offset: int, limit: int
    ) -> tuple[int, list[dict[str, Any]]]:
        values = sorted(
            (
                dict(unit)
                for unit in self.units.values()
                if unit["job_id"] == job_id
                and unit["unit_kind"] == "SEMANTIC_ANALYSIS"
            ),
            key=lambda item: (
                item["semantic_kind"],
                item["primary_directory"],
                item["semantic_key"],
            ),
        )
        return len(values), values[offset:offset + limit]

    async def complete_unit(
        self,
        unit_id: UUID,
        worker_id: str,
        result: str,
        review_request_id: UUID | None,
    ) -> None:
        unit = self.units[unit_id]
        if unit["worker_id"] != worker_id:
            raise RuntimeError("lease lost")
        unit.update(status="COMPLETED", result=result, review_request_id=review_request_id)
        job = self.jobs[unit["job_id"]]
        if unit["unit_kind"] == "SEMANTIC_ANALYSIS":
            self._refresh_project_job(job)
        else:
            job.update(
                status="COMPLETED",
                result=result,
                completed_units=1,
                review_request_ids=[]
                if review_request_id is None
                else [str(review_request_id)],
                completed_at=datetime.now(UTC),
            )

    async def fail_unit(
        self,
        unit_id: UUID,
        worker_id: str,
        error_code: str,
        error_message: str,
        retry_at: datetime | None,
    ) -> None:
        unit = self.units[unit_id]
        if unit["worker_id"] != worker_id:
            return
        unit.update(
            status="RETRY_WAITING" if retry_at else "FAILED",
            next_attempt_at=retry_at,
            error_code=error_code,
        )
        job = self.jobs[unit["job_id"]]
        if unit["unit_kind"] == "SEMANTIC_ANALYSIS":
            self._refresh_project_job(job)
        else:
            job.update(
                status="QUEUED" if retry_at else "FAILED",
                failed_units=0 if retry_at else 1,
                error_code=error_code,
                error_message=error_message,
            )

    def _refresh_project_job(self, job: dict[str, Any]) -> None:
        semantic = [
            item
            for item in self.units.values()
            if item["job_id"] == job["id"]
            and item["unit_kind"] == "SEMANTIC_ANALYSIS"
        ]
        active = [
            item
            for item in semantic
            if item["status"] in {"PENDING", "CLAIMED", "RUNNING", "RETRY_WAITING"}
        ]
        completed = [item for item in semantic if item["status"] == "COMPLETED"]
        failed = [item for item in semantic if item["status"] == "FAILED"]
        executable = [
            item for item in semantic if item["status"] != "READY_FOR_ANALYSIS"
        ]
        review_ids = [
            str(item["review_request_id"])
            for item in completed
            if item.get("review_request_id")
        ]
        job.update(
            total_units=len(semantic),
            completed_units=len(completed),
            failed_units=len(failed),
            review_request_ids=review_ids,
        )
        if active:
            job.update(status="RUNNING", phase="EXECUTING_UNITS")
        elif executable and len(completed) == len(executable):
            job.update(
                status="COMPLETED",
                phase="COMPLETED",
                result="REVIEW_SUBMITTED" if review_ids else "NO_CHANGE",
                completed_at=datetime.now(UTC),
            )
        elif executable and completed and len(completed) + len(failed) == len(executable):
            job.update(
                status="PARTIALLY_COMPLETED",
                phase="COMPLETED",
                result="PARTIALLY_COMPLETED",
                completed_at=datetime.now(UTC),
            )
        else:
            job.update(status="FAILED", phase="COMPLETED", completed_at=datetime.now(UTC))

    async def record_worker_heartbeat(self, worker_id: str) -> None:
        self.worker_heartbeats[worker_id] = datetime.now(UTC)

    async def close(self) -> None:
        return None


class FakeDelegationClient:
    def __init__(self) -> None:
        self.delegation_id = UUID("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        self.created_by = UUID("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
        self.revision = "abc"
        self.authorized = True
        self.exchanges = 0

    async def create(self, **_kwargs: Any) -> dict[str, Any]:
        if not self.authorized:
            from app.clients.delegation_client import DelegationClientError

            raise DelegationClientError("MCP_PERMISSION_DENIED", "denied")
        return {
            "delegationId": str(self.delegation_id),
            "createdByUserId": str(self.created_by),
            "revision": self.revision,
        }

    async def authorize(self, **_kwargs: Any) -> None:
        if not self.authorized:
            from app.clients.delegation_client import DelegationClientError

            raise DelegationClientError("MCP_PERMISSION_DENIED", "denied")

    async def exchange(self, **_kwargs: Any) -> str:
        self.exchanges += 1
        if not self.authorized:
            from app.clients.delegation_client import DelegationClientError

            raise DelegationClientError("MCP_PERMISSION_DENIED", "denied")
        return "Bearer short-lived-delegated-token"


class FakeMcpClient:
    def __init__(
        self,
        *,
        bound: bool = True,
        code_content: str = "class Example {}",
    ) -> None:
        self.bound = bound
        self.code_content = code_content
        self.calls: list[tuple[str, dict[str, Any], str]] = []
        self.submissions: list[tuple[AgentPlan, str, str, str]] = []

    async def call_tool(
        self, name: str, arguments: dict[str, Any], authorization: str
    ) -> dict[str, Any]:
        self.calls.append((name, arguments, authorization))
        if name == "devcollab.workspace.get_context":
            return {
                "workspaceId": arguments["workspaceId"],
                "name": "Runtime Workspace",
                "currentUserRole": "ADMIN",
                "repositories": [
                    {
                        "repositoryId": "22222222-2222-2222-2222-222222222222",
                        "name": "devcollab",
                        "provider": "GITHUB",
                        "remoteUrl": "https://example.invalid/devcollab",
                        "defaultBranch": "main",
                        "syncStatus": "SYNCED",
                        "lastSyncedCommit": "abc",
                    }
                ],
            }
        if name == "devcollab.code.read":
            return {
                "workspaceId": arguments["workspaceId"],
                "repositoryId": arguments["repositoryId"],
                "path": arguments["path"],
                "commitHash": "abc",
                "language": "Java",
                "sizeBytes": len(self.code_content),
                "startLine": 1,
                "endLine": 1,
                "totalLines": 1,
                "content": self.code_content,
                "truncated": False,
                "omittedLineCount": 0,
                "omittedCharacterCount": 0,
                "existingBindings": [],
                "existingBindingsAvailable": True,
                "existingBindingsRequested": False,
            }
        if name == "devcollab.binding.list":
            bindings = (
                [
                    {
                        "bindingId": "33333333-3333-3333-3333-333333333333",
                        "pathPattern": arguments["filePath"],
                        "documentId": "44444444-4444-4444-4444-444444444444",
                        "documentTitle": "Bound Design",
                        "blockId": None,
                    }
                ]
                if self.bound
                else []
            )
            return {
                "workspaceId": arguments["workspaceId"],
                "repositoryId": arguments["repositoryId"],
                "filePath": arguments["filePath"],
                "fileHasBindings": bool(bindings),
                "bindings": bindings,
                "truncated": False,
                "omittedBindingCount": 0,
            }
        if name == "devcollab.document.find_candidates":
            return {
                "workspaceId": arguments["workspaceId"],
                "repositoryId": arguments["repositoryId"],
                "filePath": arguments["filePath"],
                "query": None,
                "candidates": [
                    {
                        "documentId": "55555555-5555-5555-5555-555555555555",
                        "title": "Candidate Design",
                        "score": 10,
                        "matchReasons": [],
                        "matchedBlockIds": [],
                        "existingBindingCount": 0,
                    }
                ],
                "truncated": False,
                "omittedCandidateCount": 0,
            }
        if name == "devcollab.document.get_structure":
            return {
                "documentId": arguments["documentId"],
                "workspaceId": arguments["workspaceId"],
                "title": "Design",
                "documentType": "REQUIREMENT",
                "reviewStatus": "DRAFT",
                "updatedAt": "2026-07-27T00:00:00Z",
                "blocks": [],
                "version": 1,
                "truncated": False,
                "omittedBlockCount": 0,
                "omittedCharacterCount": 0,
            }
        if name == "devcollab.repository.list_files":
            return {
                "workspaceId": arguments["workspaceId"],
                "repositoryId": arguments["repositoryId"],
                "revision": "abc",
                "pathPrefix": arguments.get("pathPrefix", ""),
                "recursive": arguments.get("recursive", True),
                "files": [
                    {
                        "filePath": "src/Example.java",
                        "fileName": "Example.java",
                        "extension": "java",
                        "sizeBytes": 16,
                        "language": "Java",
                        "readable": True,
                        "isDirectory": False,
                    }
                ],
                "nextCursor": None,
                "hasMore": False,
            }
        if name == "devcollab.repository.list_changes":
            return {
                "workspaceId": arguments["workspaceId"],
                "repositoryId": arguments["repositoryId"],
                "changeId": "77777777-7777-7777-7777-777777777777",
                "changeType": "COMMIT",
                "commitSha": "abc",
                "files": [
                    {
                        "status": "MODIFIED",
                        "filePath": "src/Example.java",
                        "oldPath": None,
                        "binaryFile": False,
                    }
                ],
                "nextCursor": None,
                "hasMore": False,
            }
        if name == "devcollab.binding.list_batch":
            return {
                "workspaceId": arguments["workspaceId"],
                "repositoryId": arguments["repositoryId"],
                "files": [
                    {
                        "filePath": path,
                        "bindings": [
                            {
                                "bindingId": "33333333-3333-3333-3333-333333333333",
                                "repositoryId": arguments["repositoryId"],
                                "documentId": "44444444-4444-4444-4444-444444444444",
                                "blockId": None,
                                "pathPattern": path,
                            }
                        ]
                        if self.bound
                        else [],
                    }
                    for path in arguments["filePaths"]
                ],
            }
        if name == "devcollab.repository.inspect_code_metadata":
            return {
                "workspaceId": arguments["workspaceId"],
                "repositoryId": arguments["repositoryId"],
                "revision": arguments["revision"],
                "files": [
                    {
                        "filePath": path,
                        "language": "Java",
                        "packageName": "com.example",
                        "moduleKey": "src",
                        "layerHint": "SERVICE",
                        "imports": [],
                        "exportedSymbols": [],
                        "topLevelSymbols": ["Example"],
                        "annotations": [],
                        "routeHints": [],
                        "roleHints": ["SERVICE"],
                        "parseStatus": "PARSED",
                        "errorCode": None,
                    }
                    for path in arguments["filePaths"]
                ],
            }
        raise AssertionError(f"Unexpected tool: {name}")

    async def submit_document_change(
        self,
        plan: AgentPlan,
        *,
        workspace_id: str,
        run_id: str,
        authorization: str,
    ) -> dict[str, Any]:
        self.submissions.append((plan, workspace_id, run_id, authorization))
        return {
            "changeRequestId": "99999999-9999-9999-9999-999999999999",
            "status": "PENDING",
            "createdAt": "2026-07-27T00:00:00Z",
            "idempotentReplay": False,
        }


class FakeModelProvider:
    def __init__(self, plans: list[AgentPlan | Exception] | None = None) -> None:
        self.plans = plans or [
            AgentPlan.model_validate(
                {
                    "decision": "NO_CHANGE",
                    "summary": "No synchronization is needed",
                    "rationale": "The implementation and documentation agree.",
                    "operations": [],
                    "bindingProposals": [],
                    "evidence": [],
                }
            )
        ]
        self.calls: list[dict[str, Any]] = []
        self.unit_plan_calls: list[dict[str, Any]] = []
        self.binding_plan_calls: list[dict[str, Any]] = []
        self.pending_binding_proposals: list[Any] = []
        self.unit_plans: list[UnitPlan | Exception] = [
            UnitPlan.model_validate(
                {
                    "units": [
                        {
                            "name": "示例业务能力",
                            "kind": "BUSINESS_SERVICE",
                            "summary": "说明示例业务服务的职责与边界。",
                            "primaryFiles": ["src/Example.java"],
                            "supportingFiles": [],
                            "relatedDocumentIds": [],
                            "groupingEvidence": ["SERVICE roleHint"],
                        }
                    ]
                }
            )
        ]

    async def plan_document_sync(
        self,
        context_bundle: dict[str, Any],
        *,
        previous_plan: dict[str, Any] | None = None,
        validation_errors: list[dict[str, str]] | None = None,
    ) -> AgentPlan:
        self.calls.append(
            {
                "context": context_bundle,
                "previousPlan": previous_plan,
                "validationErrors": validation_errors,
            }
        )
        item = self.plans[min(len(self.calls) - 1, len(self.plans) - 1)]
        if isinstance(item, Exception):
            raise item
        self.pending_binding_proposals = list(item.bindingProposals)
        return item.model_copy(
            update={
                "decision": (
                    Decision.SUBMIT_REVIEW
                    if item.operations
                    else Decision.NO_CHANGE
                ),
                "bindingProposals": [],
            }
        )

    async def plan_project_units(
        self,
        project_index: dict[str, Any],
        *,
        previous_plan: dict[str, Any] | None = None,
        validation_errors: list[dict[str, str]] | None = None,
    ) -> UnitPlan:
        self.unit_plan_calls.append(
            {
                "projectIndex": project_index,
                "previousPlan": previous_plan,
                "validationErrors": validation_errors,
            }
        )
        item = self.unit_plans[
            min(len(self.unit_plan_calls) - 1, len(self.unit_plans) - 1)
        ]
        if isinstance(item, Exception):
            raise item
        return item

    async def plan_block_bindings(
        self,
        candidates: dict[str, Any],
        *,
        previous_plan: dict[str, Any] | None = None,
        validation_errors: list[dict[str, str]] | None = None,
    ) -> BindingPlan:
        self.binding_plan_calls.append(
            {
                "candidates": candidates,
                "previousPlan": previous_plan,
                "validationErrors": validation_errors,
            }
        )
        selections: list[BindingSelection] = []
        for proposal in self.pending_binding_proposals:
            if proposal.action != BindingAction.UPSERT_BINDING:
                continue
            code = next(
                (
                    item
                    for item in candidates.get("codeCandidates", [])
                    if item.get("filePath") == proposal.filePath
                    and item.get("anchorKind") == proposal.anchorKind
                    and item.get("symbolKey") == proposal.symbolKey
                    and item.get("startLine") == proposal.startLine
                    and item.get("endLine") == proposal.endLine
                ),
                None,
            )
            document = next(
                (
                    item
                    for item in candidates.get("documentAnchorCandidates", [])
                    if item.get("documentId")
                    == (
                        str(proposal.documentId)
                        if proposal.documentId is not None
                        else None
                    )
                    and item.get("createdDocumentClientOperationId")
                    == proposal.createdDocumentClientOperationId
                    and item.get("blockId")
                    == (
                        str(proposal.blockId)
                        if proposal.blockId is not None
                        else None
                    )
                    and item.get("createdBlockClientOperationId")
                    == proposal.createdBlockClientOperationId
                ),
                None,
            )
            if code is None or document is None:
                continue
            selections.append(
                BindingSelection(
                    blockKey=(
                        proposal.createdBlockClientOperationId
                        or document["candidateId"]
                    ),
                    codeCandidateId=code["candidateId"],
                    role=proposal.bindingRole,
                    ordinal=proposal.bindingOrdinal,
                    reason=proposal.reason,
                    confidence=proposal.confidence or 0.9,
                )
            )
        return BindingPlan(selections=selections)


@pytest.fixture
def settings() -> Settings:
    return Settings(
        deepseek_api_key="",
        deepseek_base_url="",
        deepseek_model="",
        agent_max_selected_files=2,
        agent_max_code_chars=40,
        agent_max_bound_documents=5,
        agent_max_candidate_documents=5,
        agent_max_document_structures=3,
        agent_max_tool_calls=12,
        agent_run_ttl_seconds=86400,
    )


@pytest.fixture
def fake_mcp() -> FakeMcpClient:
    return FakeMcpClient()


@pytest.fixture
def run_store() -> MemoryRunStore:
    return MemoryRunStore()


@pytest.fixture
def client(
    settings: Settings,
    fake_mcp: FakeMcpClient,
    run_store: MemoryRunStore,
) -> TestClient:
    with TestClient(
        create_app(
            settings=settings,
            mcp_client=fake_mcp,
            run_store=run_store,
            model_provider=FakeModelProvider(),
            job_repository=MemoryAgentJobRepository(),
            delegation_client=FakeDelegationClient(),
        )
    ) as test_client:
        yield test_client


def request_payload(paths: list[str] | None = None) -> dict[str, Any]:
    return {
        "workspaceId": "11111111-1111-1111-1111-111111111111",
        "repositoryId": "22222222-2222-2222-2222-222222222222",
        "selectedPaths": paths or ["src/Example.java"],
        "userInstruction": "Check documentation alignment",
    }
