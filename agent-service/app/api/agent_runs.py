from datetime import UTC, datetime
from time import perf_counter
from typing import Any, cast
from uuid import UUID, uuid4

from fastapi import APIRouter, Header, HTTPException, Request, status

from app.clients.mcp_client import McpClientError
from app.clients.run_store import RunStoreError
from app.config import Settings
from app.graph.state import AgentState
from app.graph.workflow import ContextWorkflow
from app.schemas.runs import (
    AgentRunRecord,
    AgentRunResponse,
    CreateAgentRunRequest,
    CreateContextRunRequest,
    QueuedAgentRunResponse,
    RunStatus,
    TraceSummary,
)

router = APIRouter(prefix="/api/v1/agent-runs", tags=["agent-runs"])


@router.post(
    "",
    response_model=QueuedAgentRunResponse,
    status_code=status.HTTP_202_ACCEPTED,
)
async def create_agent_run(
    payload: CreateAgentRunRequest,
    request: Request,
    authorization: str | None = Header(default=None),
) -> QueuedAgentRunResponse:
    authorization = _require_bearer(authorization)
    settings: Settings = request.app.state.settings
    _validate_selected_path_limit(payload.selectedPaths, settings)
    run_id = uuid4()
    now = datetime.now(UTC)
    queued = AgentRunRecord(
        runId=run_id,
        status="QUEUED",
        workspaceId=payload.workspaceId,
        repositoryId=payload.repositoryId,
        selectedPaths=payload.selectedPaths,
        currentNode="queued",
        createdAt=now,
        updatedAt=now,
    )
    try:
        await request.app.state.run_store.save(
            str(run_id),
            queued.model_dump(mode="json"),
            settings.agent_run_ttl_seconds,
        )
    except RunStoreError as exc:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={"code": "REDIS_UNAVAILABLE", "message": str(exc)},
        ) from exc
    request.app.state.run_executor.start(
        run_id=str(run_id),
        workspace_id=str(payload.workspaceId),
        repository_id=str(payload.repositoryId),
        selected_paths=payload.selectedPaths,
        user_instruction=payload.userInstruction,
        authorization=authorization,
        created_at=now.isoformat(),
    )
    return QueuedAgentRunResponse(runId=run_id, status="QUEUED")


@router.post("/context", response_model=AgentRunResponse)
async def create_context_run(
    payload: CreateContextRunRequest,
    request: Request,
    authorization: str | None = Header(default=None),
) -> AgentRunResponse:
    authorization = _require_bearer(authorization)
    settings: Settings = request.app.state.settings
    _validate_selected_path_limit(payload.selectedPaths, settings)

    run_id = uuid4()
    now = datetime.now(UTC)
    running = _response(run_id, "RUNNING", now)
    try:
        await request.app.state.run_store.save(
            str(run_id),
            running.model_dump(mode="json"),
            settings.agent_run_ttl_seconds,
        )
    except RunStoreError as exc:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={"code": "REDIS_UNAVAILABLE", "message": str(exc)},
        ) from exc

    started = perf_counter()
    workflow = ContextWorkflow(request.app.state.mcp_client, settings)
    initial_state: AgentState = {
        "run_id": str(run_id),
        "workspace_id": str(payload.workspaceId),
        "repository_id": str(payload.repositoryId),
        "selected_paths": payload.selectedPaths,
        "user_instruction": payload.userInstruction,
        "authorization": authorization,
        "tool_call_count": 0,
        "code_chars_used": 0,
        "trace_events": [],
        "errors": [],
    }
    try:
        result = await workflow.graph.ainvoke(initial_state)
        events = result.get("trace_events", [])
        response = AgentRunResponse(
            runId=run_id,
            status="CONTEXT_READY",
            contextBundle=result["context_bundle"],
            traceSummary=TraceSummary(
                toolCallsUsed=result.get("tool_call_count", 0),
                durationMs=round((perf_counter() - started) * 1000),
                successfulNodes=[event["node"] for event in events if event.get("success")],
            ),
            createdAt=now,
            updatedAt=datetime.now(UTC),
        )
    except Exception as exc:
        events = initial_state.get("trace_events", [])
        response = AgentRunResponse(
            runId=run_id,
            status="FAILED",
            contextBundle=None,
            traceSummary=TraceSummary(
                toolCallsUsed=len(events),
                durationMs=round((perf_counter() - started) * 1000),
                successfulNodes=[event["node"] for event in events if event.get("success")],
                failedNode=_failed_node(events),
            ),
            error=_safe_error(exc),
            createdAt=now,
            updatedAt=datetime.now(UTC),
        )

    try:
        await request.app.state.run_store.save(
            str(run_id),
            response.model_dump(mode="json"),
            settings.agent_run_ttl_seconds,
        )
    except RunStoreError as exc:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={"code": "REDIS_UNAVAILABLE", "message": str(exc)},
        ) from exc
    return response


@router.get("/{run_id}")
async def get_run(
    run_id: UUID,
    request: Request,
    authorization: str | None = Header(default=None),
) -> dict[str, object]:
    _require_bearer(authorization)
    try:
        payload = await request.app.state.run_store.get(str(run_id))
    except RunStoreError as exc:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={"code": "REDIS_UNAVAILABLE", "message": str(exc)},
        ) from exc
    if payload is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail={"code": "INVALID_REQUEST", "message": "Agent run not found"},
        )
    return cast(dict[str, object], payload)


def _response(run_id: UUID, run_status: RunStatus, now: datetime) -> AgentRunResponse:
    return AgentRunResponse(
        runId=run_id,
        status=run_status,
        contextBundle=None,
        traceSummary=TraceSummary(),
        createdAt=now,
        updatedAt=now,
    )


def _safe_error(exc: Exception) -> dict[str, Any]:
    if isinstance(exc, McpClientError):
        return {"code": exc.code, "message": str(exc)[:300]}
    elif exc.__class__.__name__ == "ToolCallLimitExceededError":
        return {"code": "TOOL_CALL_LIMIT_EXCEEDED", "message": str(exc)[:300]}
    elif isinstance(exc, ValueError):
        return {"code": "INVALID_REQUEST", "message": str(exc)[:300]}
    return {"code": "INTERNAL_ERROR", "message": "Agent context construction failed"}


def _failed_node(events: list[dict[str, Any]]) -> str | None:
    failed = [event for event in events if not event.get("success")]
    return failed[-1]["node"] if failed else None


def _require_bearer(authorization: str | None) -> str:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail={"code": "AUTHENTICATION_REQUIRED", "message": "Bearer token required"},
        )
    return authorization


def _validate_selected_path_limit(
    selected_paths: list[str],
    settings: Settings,
) -> None:
    if len(selected_paths) > settings.agent_max_selected_files:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail={
                "code": "INVALID_REQUEST",
                "message": "selectedPaths exceeds configured file limit",
            },
        )
