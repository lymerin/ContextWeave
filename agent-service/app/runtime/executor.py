import asyncio
from datetime import UTC, datetime
from typing import Any

from app.clients.mcp_client import McpClientError, ReviewMcpClient
from app.clients.run_store import RunStore
from app.config import Settings
from app.graph.document_sync_workflow import (
    DocumentSyncWorkflow,
    ReviewSubmissionError,
)
from app.graph.state import AgentState
from app.planning.validator import PlanValidationError
from app.providers.base import ModelProvider, ModelProviderError


class AgentRunExecutor:
    def __init__(
        self,
        client: ReviewMcpClient,
        provider: ModelProvider,
        store: RunStore,
        settings: Settings,
    ) -> None:
        self._client = client
        self._provider = provider
        self._store = store
        self._settings = settings
        self._tasks: dict[str, asyncio.Task[None]] = {}

    def start(
        self,
        *,
        run_id: str,
        workspace_id: str,
        repository_id: str,
        selected_paths: list[str],
        user_instruction: str | None,
        authorization: str,
        created_at: str,
    ) -> None:
        existing = self._tasks.get(run_id)
        if existing and not existing.done():
            return
        task = asyncio.create_task(
            self._execute(
                run_id=run_id,
                workspace_id=workspace_id,
                repository_id=repository_id,
                selected_paths=selected_paths,
                user_instruction=user_instruction,
                authorization=authorization,
                created_at=created_at,
            ),
            name=f"agent-run-{run_id}",
        )
        self._tasks[run_id] = task
        task.add_done_callback(lambda _: self._tasks.pop(run_id, None))

    async def close(self) -> None:
        tasks = list(self._tasks.values())
        for task in tasks:
            task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)

    async def _execute(
        self,
        *,
        run_id: str,
        workspace_id: str,
        repository_id: str,
        selected_paths: list[str],
        user_instruction: str | None,
        authorization: str,
        created_at: str,
    ) -> None:
        base: dict[str, Any] = {
            "runId": run_id,
            "status": "BUILDING_CONTEXT",
            "workspaceId": workspace_id,
            "repositoryId": repository_id,
            "selectedPaths": selected_paths,
            "currentNode": "load_workspace_context",
            "decision": None,
            "summary": None,
            "changeRequestId": None,
            "errorCode": None,
            "errorMessage": None,
            "createdAt": created_at,
            "updatedAt": self._now(),
        }

        async def on_status(
            status: str,
            node: str,
            updates: dict[str, Any],
        ) -> None:
            base.update(updates)
            base["status"] = status
            base["currentNode"] = node
            base["updatedAt"] = self._now()
            await self._store.save(
                run_id,
                dict(base),
                self._settings.agent_run_ttl_seconds,
            )

        try:
            await on_status("BUILDING_CONTEXT", "load_workspace_context", {})
            workflow = DocumentSyncWorkflow(
                self._client,
                self._provider,
                self._settings,
                on_status,
            )
            initial_state: AgentState = {
                "run_id": run_id,
                "workspace_id": workspace_id,
                "repository_id": repository_id,
                "selected_paths": selected_paths,
                "user_instruction": user_instruction,
                "authorization": authorization,
                "tool_call_count": 0,
                "code_chars_used": 0,
                "trace_events": [],
                "errors": [],
            }
            result = await workflow.graph.ainvoke(initial_state)
            terminal = "REVIEW_SUBMITTED" if result.get("change_request_id") else "NO_CHANGE"
            await on_status(
                terminal,
                "submit_review" if terminal == "REVIEW_SUBMITTED" else "finish_no_change",
                {
                    "decision": result.get("decision"),
                    "summary": result.get("summary"),
                    "changeRequestId": result.get("change_request_id"),
                },
            )
        except asyncio.CancelledError:
            await self._mark_failed(
                base,
                run_id,
                "INTERNAL_ERROR",
                "Agent service stopped before the run completed",
            )
            raise
        except Exception as exc:
            code, message = self._safe_error(exc)
            await self._mark_failed(base, run_id, code, message)

    async def _mark_failed(
        self,
        base: dict[str, Any],
        run_id: str,
        code: str,
        message: str,
    ) -> None:
        base.update(
            {
                "status": "FAILED",
                "errorCode": code,
                "errorMessage": message[:300],
                "updatedAt": self._now(),
            }
        )
        await self._store.save(
            run_id,
            dict(base),
            self._settings.agent_run_ttl_seconds,
        )

    @staticmethod
    def _safe_error(exc: Exception) -> tuple[str, str]:
        if isinstance(exc, ModelProviderError):
            return exc.code, str(exc)[:300]
        if isinstance(exc, PlanValidationError):
            details = exc.safe_details()
            summary = ", ".join(
                f"{item['code']}@{item['path']}" for item in details[:8]
            )
            return (
                "PLAN_VALIDATION_FAILED",
                f"Agent plan failed validation after one repair: {summary}"[:300],
            )
        if isinstance(exc, ReviewSubmissionError):
            return exc.code, str(exc)[:300]
        if isinstance(exc, McpClientError):
            code = (
                exc.code
                if exc.code in {"MCP_PERMISSION_DENIED", "MCP_UNAVAILABLE"}
                else "REVIEW_SUBMISSION_FAILED"
            )
            return code, str(exc)[:300]
        if exc.__class__.__name__ == "ToolCallLimitExceededError":
            return "TOOL_CALL_LIMIT_EXCEEDED", str(exc)[:300]
        if isinstance(exc, ValueError):
            return "INVALID_REQUEST", str(exc)[:300]
        return "INTERNAL_ERROR", "Agent workflow failed"

    @staticmethod
    def _now() -> str:
        return datetime.now(UTC).isoformat()
