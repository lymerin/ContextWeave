from __future__ import annotations

import asyncio
import logging
import os
import signal
import socket
from datetime import UTC, datetime, timedelta
from typing import Any, cast
from uuid import UUID, uuid4

import asyncpg  # type: ignore[import-untyped]

from app.clients.delegation_client import (
    DelegationClient,
    DelegationClientError,
    KnowledgeCoreDelegationClient,
)
from app.clients.mcp_client import McpClientError, OfficialMcpClient, ReviewMcpClient
from app.config import Settings, get_settings
from app.document_planner.plan_validator import PlanValidationError
from app.execution.job_executor import JobExecutor
from app.model_context_mcp.snapshot_store_registry import SnapshotStoreRegistry
from app.persistence.job_repository import AgentJobRepository, PostgresAgentJobRepository
from app.planning.deepseek_unit_planner import DeepSeekUnitPlanner
from app.planning.unit_plan_validator import UnitPlanValidationError
from app.platform_mcp.binding_reader import BindingReader
from app.platform_mcp.document_reader import DocumentReader
from app.platform_mcp.plan_writer import PlanWriter
from app.platform_mcp.source_reader import SourceReader
from app.platform_mcp.workspace_reader import WorkspaceReader
from app.profiling import MemoryProfileConfig, RuntimeMemoryProfiler
from app.providers.base import ModelProvider, ModelProviderError
from app.providers.deepseek import DeepSeekProvider
from app.runtime.delegated_mcp_client import DelegatedMcpClient
from app.execution.errors import JobExecutionError
from app.runtime.project_discovery import ProjectDiscoveryService
from app.runtime.semantic_planner import materialize_deepseek_units, overlapping_file_count
from app.source_selection.file_filter import SourceFileFilter

LOGGER = logging.getLogger("devcollab.agent.worker")


class ReviewSubmissionError(RuntimeError):
    """提交审查请求失败。"""
    code = "REVIEW_SUBMISSION_FAILED"


RETRYABLE_ERRORS = {
    "MODEL_TIMEOUT",
    "MODEL_UNAVAILABLE",
    "MCP_UNAVAILABLE",
    "DATABASE_UNAVAILABLE",
}
PHASES = {
    "PLANNING": "MODEL_RUNNING",
    "VALIDATING": "VALIDATING",
    "REPAIRING_PLAN": "REPAIRING",
    "SUBMITTING_REVIEW": "SUBMITTING_REVIEW",
}


class AgentWorker:
    def __init__(
        self,
        repository: AgentJobRepository,
        mcp_client: ReviewMcpClient,
        delegation_client: DelegationClient,
        provider: ModelProvider,
        settings: Settings,
        *,
        worker_id: str | None = None,
        memory_profiler: RuntimeMemoryProfiler | None = None,
    ) -> None:
        self._repository = repository
        self._mcp_client = mcp_client
        self._delegation_client = delegation_client
        self._provider = provider
        self._settings = settings
        self._worker_id = (
            worker_id
            or f"{socket.gethostname()}-{os.getpid()}-{uuid4().hex[:12]}"
        )
        self._stopping = asyncio.Event()
        self._memory_profiler = memory_profiler
        self._registry = SnapshotStoreRegistry()

    def stop(self) -> None:
        self._stopping.set()

    async def run(self) -> None:
        LOGGER.info("Agent worker started workerId=%s", self._worker_id)
        tasks: set[asyncio.Task[None]] = set()
        while not self._stopping.is_set():
            try:
                await self._repository.record_worker_heartbeat(self._worker_id)
                finished = {task for task in tasks if task.done()}
                for task in finished:
                    tasks.remove(task)
                    if task.exception() is not None:
                        LOGGER.error(
                            "Agent worker task failed",
                            exc_info=task.exception(),
                        )
                if len(tasks) >= self._settings.agent_project_unit_concurrency:
                    await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
                    continue
                unit = await self._repository.claim_next_unit(
                    self._worker_id, self._settings.agent_unit_lease_seconds
                )
                if unit is None:
                    if tasks:
                        await asyncio.wait(
                            tasks,
                            timeout=self._settings.agent_worker_poll_seconds,
                            return_when=asyncio.FIRST_COMPLETED,
                        )
                        continue
                    await self._wait_for_poll()
                    continue
                tasks.add(
                    asyncio.create_task(
                        self._execute(unit), name=f"agent-unit-{unit['id']}"
                    )
                )
            except asyncio.CancelledError:
                raise
            except Exception:
                LOGGER.exception("Agent worker loop failed")
                await self._wait_for_poll()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)

    async def _wait_for_poll(self) -> None:
        try:
            await asyncio.wait_for(
                self._stopping.wait(), timeout=self._settings.agent_worker_poll_seconds
            )
        except TimeoutError:
            pass

    async def _execute(self, unit: dict[str, Any]) -> None:
        """Execute a single agent unit of work.

        Three unit kinds exist, each triggered by a different user action:

        ┌─────────────────────┬──────────────────────────────────────────┐
        │ Unit Kind           │ Trigger                                  │
        ├─────────────────────┼──────────────────────────────────────────┤
        │ PROJECT_DISCOVERY   │ User registers a git repository.         │
        │                     │ Scans all files, uses DeepSeek to plan   │
        │                     │ SEMANTIC_ANALYSIS units.  No document    │
        │                     │ is produced here—only planning.          │
        ├─────────────────────┼──────────────────────────────────────────┤
        │ SEMANTIC_ANALYSIS   │ Created by PROJECT_DISCOVERY above.      │
        │                     │ DeepSeek already grouped files into a    │
        │                     │ "semantic module" and associated a       │
        │                     │ target document, so the unit context     │
        │                     │ carries both file_paths and document_ids.│
        │                     │ The workflow analyses the code AND writes │
        │                     │ the document + bindings in one pass.     │
        ├─────────────────────┼──────────────────────────────────────────┤
        │ CURRENT_FILE_ANALYSIS│ User opens a file in the IDE.  Only     │
        │ (default)           │ scope.filePath is known—NO pre-determined │
        │                     │ document.  The workflow must DISCOVER    │
        │                     │ which documents to bind to by querying   │
        │                     │ existing bindings and document candidates│
        │                     │ during context gathering (list_existing_ │
        │                     │ bindings → resolve_documents).           │
        └─────────────────────┴──────────────────────────────────────────┘

        Why SEMANTIC_ANALYSIS pre-determines the document:
          During PROJECT_DISCOVERY, DeepSeek groups related files into
          semantic modules (e.g. "认证模块" containing auth/*.py).
          Each module is assigned a document that will describe it.
          This is a PLANNING decision made by the LLM, not the code—
          the LLM decides what the document boundary should be before
          the detailed analysis runs.

        Why CURRENT_FILE_ANALYSIS does NOT pre-determine the document:
          The user just opened a file.  We don't know yet whether this
          file belongs to an existing document, should create a new one,
          or has no document-worthy content.  The context-gathering phase
          (list_existing_bindings, resolve_documents) answers this.
        """
        job = unit["job"]
        unit_id = UUID(str(unit["id"]))
        job_id = UUID(str(job["id"]))
        heartbeat = asyncio.create_task(
            self._heartbeat(unit_id), name=f"heartbeat-{unit_id}"
        )
        delegated = DelegatedMcpClient(
            self._mcp_client,
            self._delegation_client,
            delegation_id=UUID(str(job["delegation_id"])),
            job_id=job_id,
            revision=str(job["revision"]),
            knowledge_core_base_url=self._settings.knowledge_core_base_url,
            request_timeout_seconds=self._settings.agent_request_timeout_seconds,
        )

        async def on_status(status: str, _node: str, _updates: dict[str, Any]) -> None:
            phase = PHASES.get(status)
            if phase:
                updated = await self._repository.update_phase(
                    unit_id, self._worker_id, phase
                )
                if not updated:
                    raise RuntimeError("Agent unit lease was lost")

        async def run_workflow() -> dict[str, Any]:
            scope = job["scope_payload"]
            if unit.get("unit_kind") == "SEMANTIC_ANALYSIS":
                context = await self._repository.get_unit_context(unit_id)
                selected_paths = [
                    str(item.get("file_path") or item.get("filePath"))
                    for item in context.get("files", [])
                ]
            else:
                selected_paths = [str(scope["filePath"])]

            executor = JobExecutor(
                workspace_reader=WorkspaceReader(delegated),
                source_reader=SourceReader(delegated),
                document_reader=DocumentReader(delegated),
                binding_reader=BindingReader(delegated),
                plan_writer=PlanWriter(delegated),
                file_filter=SourceFileFilter(),
                registry=self._registry,
                provider=self._provider,
            )
            with self._profile_stage("UNIT_EXECUTION", job, job_id, unit_id):
                return await executor.execute(
                    workspace_id=UUID(str(job["workspace_id"])),
                    repository_id=UUID(str(job["repository_id"])),
                    revision=str(job["revision"]),
                    selected_paths=selected_paths,
                    run_id=str(unit_id),  # 每次执行唯一，保证幂等 clientRequestId
                )

        async def run_project_discovery() -> None:
            async def on_phase(phase: str) -> None:
                updated = await self._repository.update_phase(
                    unit_id, self._worker_id, phase
                )
                if not updated:
                    raise RuntimeError("Agent unit lease was lost")

            service = ProjectDiscoveryService(delegated, self._settings, on_phase)
            with self._profile_stage(
                "PROJECT_INDEX", job, job_id, unit_id
            ) as index_stage:
                (
                    files,
                    project_files,
                    planner_batches,
                    project_index,
                    stats,
                ) = await service.execute(
                    job_id=job_id,
                    workspace_id=UUID(str(job["workspace_id"])),
                    repository_id=UUID(str(job["repository_id"])),
                    revision=str(job["revision"]),
                )
                index_stage.attribute("fileCount", len(files))
                index_stage.attribute(
                    "sourceBytes",
                    sum(
                        int(item.get("sizeBytes") or 0)
                        for item in project_index.get("files", [])
                    ),
                )
            execution_limit = self._settings.agent_project_execution_limit
            planner_limit = self._settings.agent_max_analysis_units
            project_index["requestedMaxUnits"] = planner_limit
            for batch in planner_batches:
                batch["requestedMaxUnits"] = planner_limit
                batch["constraints"] = {
                    "maxUnits": planner_limit,
                    "maxFilesPerUnit": (
                        self._settings.agent_max_total_files_per_unit
                    ),
                }
            planner = DeepSeekUnitPlanner(
                self._provider,
                max_files_per_unit=self._settings.agent_max_total_files_per_unit,
                max_units=planner_limit,
                on_phase=on_phase,
            )
            with self._profile_stage(
                "PLANNER", job, job_id, unit_id
            ) as planner_stage:
                unit_plan = (
                    await planner.plan(planner_batches[0])
                    if len(planner_batches) == 1
                    else await planner.plan(
                        planner_batches,
                        validation_index=project_index,
                    )
                )
                planner_stage.attribute("batchCount", len(planner_batches))
                planner_stage.attribute("unitCount", len(unit_plan.units))
            units = materialize_deepseek_units(
                unit_plan,
                project_files,
                job_id=job_id,
                revision=str(job["revision"]),
            )
            stats["analysis_unit_count"] = len(units)
            stats["overlapping_file_count"] = overlapping_file_count(units)
            await self._repository.complete_project_discovery(
                unit_id,
                self._worker_id,
                files,
                units,
                stats,
                self._settings.agent_unit_max_attempts,
                execution_limit,
            )

        workflow_task: asyncio.Task[dict[str, Any]] | None = None
        discovery_task: asyncio.Task[None] | None = None
        try:
            if unit.get("unit_kind") == "PROJECT_DISCOVERY":
                discovery_task = asyncio.create_task(
                    run_project_discovery(), name=f"project-discovery-{unit_id}"
                )
                async with asyncio.timeout(self._settings.agent_unit_timeout_seconds):
                    done, _pending = await asyncio.wait(
                        {
                            cast(asyncio.Task[Any], discovery_task),
                            cast(asyncio.Task[Any], heartbeat),
                        },
                        return_when=asyncio.FIRST_COMPLETED,
                    )
                    if heartbeat in done:
                        discovery_task.cancel()
                        await asyncio.gather(discovery_task, return_exceptions=True)
                        heartbeat.result()
                        raise RuntimeError("Agent unit heartbeat stopped unexpectedly")
                    discovery_task.result()
                return
            workflow_task = asyncio.create_task(
                run_workflow(), name=f"workflow-{unit_id}"
            )
            async with asyncio.timeout(self._settings.agent_unit_timeout_seconds):
                done, _pending = await asyncio.wait(
                    {workflow_task, heartbeat},
                    return_when=asyncio.FIRST_COMPLETED,
                )
                if heartbeat in done:
                    workflow_task.cancel()
                    await asyncio.gather(workflow_task, return_exceptions=True)
                    heartbeat.result()
                    raise RuntimeError("Agent unit heartbeat stopped unexpectedly")
                result = workflow_task.result()
            review_id_value = result.get("change_request_id")
            review_id = UUID(str(review_id_value)) if review_id_value else None
            exec_status = result.get("status", "")

            # ── 三条持久化路径，对应两种不同的数据库写入 ──────────
            # complete_unit → unit.status=COMPLETED, job.status=COMPLETED
            # fail_unit     → unit.status=FAILED (或 RETRY_WAITING),
            #                  job.status=FAILED (或 QUEUED)
            #
            # JobExecutor 返回 FAILED 时，Bounded Repair Loop 已穷尽，
            # 不应再重试——但没有 change_request_id 也不等于 NO_CHANGE。
            if exec_status == "FAILED":
                failed_count = result.get("failed", 0)
                scope_count = result.get("scope_count", 0)
                await self._repository.fail_unit(
                    unit_id,
                    self._worker_id,
                    error_code="EXECUTION_FAILED",
                    error_message=(
                        f"Agent execution failed: {failed_count}/{scope_count} "
                        f"scope(s) failed after Bounded Repair"
                    ),
                    retry_at=None,  # Bounded Repair 已穷尽，不重试
                )
            elif review_id:
                await self._repository.complete_unit(
                    unit_id, self._worker_id, "REVIEW_SUBMITTED", review_id
                )
            else:
                await self._repository.complete_unit(
                    unit_id, self._worker_id, "NO_CHANGE", None
                )
        except Exception as exc:
            LOGGER.exception(
                "Agent unit execution failed jobId=%s unitId=%s unitKind=%s",
                job_id,
                unit_id,
                unit.get("unit_kind"),
            )
            if unit.get("unit_kind") == "PROJECT_DISCOVERY" and isinstance(
                exc, TimeoutError
            ):
                code, message = (
                    "PROJECT_DISCOVERY_TIMEOUT",
                    "Project discovery exceeded the configured unit timeout",
                )
            else:
                code, message = self._safe_error(exc)
            attempt = int(unit["attempt"])
            max_attempts = int(unit["max_attempts"])
            retry_at = None
            if code in RETRYABLE_ERRORS and attempt < max_attempts:
                delay = 60 if attempt == 1 else 300
                retry_at = datetime.now(UTC) + timedelta(seconds=delay)
            await self._repository.fail_unit(
                unit_id, self._worker_id, code, message, retry_at
            )
        finally:
            if discovery_task is not None and not discovery_task.done():
                discovery_task.cancel()
                await asyncio.gather(discovery_task, return_exceptions=True)
            if workflow_task is not None and not workflow_task.done():
                workflow_task.cancel()
                await asyncio.gather(workflow_task, return_exceptions=True)
            heartbeat.cancel()
            await asyncio.gather(heartbeat, return_exceptions=True)

    def _profile_stage(
        self,
        name: str,
        job: dict[str, Any],
        job_id: UUID,
        unit_id: UUID,
    ) -> Any:
        if self._memory_profiler is None:
            return _NoopProfileStage()
        return self._memory_profiler.stage(
            name,
            job_id=str(job_id),
            repository_id=str(job["repository_id"]),
            revision=str(job["revision"]),
            unit_id=str(unit_id),
        )

    async def _heartbeat(self, unit_id: UUID) -> None:
        while True:
            await asyncio.sleep(self._settings.agent_worker_heartbeat_seconds)
            await self._repository.record_worker_heartbeat(self._worker_id)
            renewed = await self._repository.heartbeat(
                unit_id, self._worker_id, self._settings.agent_unit_lease_seconds
            )
            if not renewed:
                raise RuntimeError("Agent unit lease was lost")

    @staticmethod
    def _safe_error(exc: Exception) -> tuple[str, str]:
        if isinstance(exc, TimeoutError):
            return "MODEL_TIMEOUT", "Agent unit timed out"
        if isinstance(exc, ModelProviderError):
            return exc.code, str(exc)[:300]
        if isinstance(exc, PlanValidationError):
            return "PLAN_VALIDATION_FAILED", "Agent plan failed validation after one repair"
        if isinstance(exc, UnitPlanValidationError):
            return (
                "UNIT_PLAN_VALIDATION_FAILED",
                str(exc)[:300],
            )
        if isinstance(exc, ReviewSubmissionError):
            return "REVIEW_CONFLICT", str(exc)[:300]
        if isinstance(exc, DelegationClientError):
            return exc.code, str(exc)[:300]
        if isinstance(exc, McpClientError):
            return exc.code, str(exc)[:300]
        if isinstance(exc, JobExecutionError):
            return exc.code, str(exc)[:300]
        if isinstance(exc, ValueError) and str(exc) == "UNIT_LIMIT_EXCEEDED":
            return "UNIT_LIMIT_EXCEEDED", "Analysis unit limit exceeded"
        if isinstance(
            exc,
            (
                asyncpg.PostgresConnectionError,
                asyncpg.CannotConnectNowError,
                asyncpg.ConnectionDoesNotExistError,
                asyncpg.InterfaceError,
                ConnectionError,
            ),
        ):
            return "DATABASE_UNAVAILABLE", "Agent database is temporarily unavailable"
        return "INTERNAL_ERROR", "Agent unit failed"


async def main() -> None:
    settings = get_settings()
    logging.basicConfig(level=logging.INFO)
    repository = await PostgresAgentJobRepository.connect(settings.agent_database_url)
    profiler = RuntimeMemoryProfiler(
        MemoryProfileConfig(
            enabled=settings.devcollab_memory_profile_enabled,
            run_id=settings.devcollab_memory_profile_run_id,
            output_dir=settings.devcollab_memory_profile_output_dir,
            interval_ms=settings.devcollab_memory_profile_interval_ms,
            queue_capacity=settings.devcollab_memory_profile_queue_capacity,
        ),
        "agent-worker",
    )
    worker = AgentWorker(
        repository,
        OfficialMcpClient(settings.mcp_base_url, settings.agent_request_timeout_seconds),
        KnowledgeCoreDelegationClient(
            settings.knowledge_core_base_url,
            settings.agent_internal_service_token,
            settings.agent_delegation_timeout_seconds,
        ),
        DeepSeekProvider(
            api_key=settings.deepseek_api_key,
            base_url=settings.deepseek_base_url,
            model=settings.deepseek_model,
            connect_timeout_seconds=settings.agent_model_connect_timeout_seconds,
            request_timeout_seconds=settings.agent_model_request_timeout_seconds,
        ),
        settings,
        memory_profiler=profiler,
    )
    loop = asyncio.get_running_loop()
    for name in ("SIGINT", "SIGTERM"):
        if hasattr(signal, name):
            loop.add_signal_handler(getattr(signal, name), worker.stop)
    try:
        await worker.run()
    finally:
        try:
            await repository.close()
        finally:
            profiler.close()


class _NoopProfileStage:
    def __enter__(self) -> _NoopProfileStage:
        return self

    def __exit__(self, *_args: object) -> None:
        return None

    def attribute(self, _key: str, _value: object) -> _NoopProfileStage:
        return self


if __name__ == "__main__":
    asyncio.run(main())
