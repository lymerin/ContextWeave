import sys
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any
from uuid import UUID, uuid4

sys.path.insert(0, str(Path(__file__).parents[1]))

import pytest
from conftest import (
    FakeDelegationClient,
    FakeMcpClient,
    FakeModelProvider,
    MemoryAgentJobRepository,
)

from app.config import Settings
from app.clients.mcp_client import OfficialMcpClient
from app.providers.base import ModelProviderError
from app.worker import AgentWorker

WORKSPACE_ID = "11111111-1111-1111-1111-111111111111"
REPOSITORY_ID = "22222222-2222-2222-2222-222222222222"
JOB_ID = UUID("bbbbbbbb-0000-0000-0000-000000000001")
UNIT_ID = UUID("bbbbbbbb-0000-0000-0000-000000000002")
DELEGATION_ID = UUID("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
CREATED_BY = UUID("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")


class SimulatedCrash(BaseException):
    pass


class CrashAfterSubmitRepository(MemoryAgentJobRepository):
    def __init__(self) -> None:
        super().__init__()
        self.crash_once = True

    async def complete_unit(
        self,
        unit_id: UUID,
        worker_id: str,
        result: str,
        review_request_id: UUID | None,
    ) -> None:
        if self.crash_once and review_request_id is not None:
            self.crash_once = False
            raise SimulatedCrash("process crashed after review submission")
        await super().complete_unit(unit_id, worker_id, result, review_request_id)


async def _seed_repository(
    repository: MemoryAgentJobRepository | None = None,
) -> MemoryAgentJobRepository:
    repository = repository or MemoryAgentJobRepository()
    now = datetime.now(UTC)
    await repository.create_job(
        {
            "id": JOB_ID,
            "delegation_id": DELEGATION_ID,
            "created_by_user_id": CREATED_BY,
            "workspace_id": UUID(WORKSPACE_ID),
            "repository_id": UUID(REPOSITORY_ID),
            "revision": "abc",
            "scope_type": "CURRENT_FILE",
            "scope_payload": {"type": "CURRENT_FILE", "filePath": "src/Example.java"},
            "user_instruction": None,
            "created_at": now,
        },
        {
            "id": UNIT_ID,
            "max_attempts": 3,
            "unit_kind": "CURRENT_FILE_ANALYSIS",
        },
    )
    return repository


def _worker(repository: MemoryAgentJobRepository, settings: Settings) -> AgentWorker:
    return AgentWorker(
        repository,
        FakeMcpClient(code_content="def example():\n    return 1", language="Python"),
        FakeDelegationClient(),
        FakeModelProvider(),
        settings.model_copy(update={"agent_worker_heartbeat_seconds": 0.01}),
        worker_id="test-worker",
    )


@pytest.mark.asyncio
async def test_model_rate_limited_unit_retries_and_eventually_succeeds(
    settings: Settings,
    monkeypatch: Any,
) -> None:
    repository = await _seed_repository()
    worker = _worker(repository, settings)
    calls = 0

    async def execute_once_rate_limited(self: object, **kwargs: Any) -> dict[str, Any]:
        nonlocal calls
        calls += 1
        if calls == 1:
            raise ModelProviderError("MODEL_RATE_LIMITED", "429 rate limited")
        return {"status": "NO_CHANGE", "change_request_id": None}

    monkeypatch.setattr("app.worker.JobExecutor.execute", execute_once_rate_limited)

    first = await repository.claim_next_unit("test-worker", 60)
    assert first is not None
    before = datetime.now(UTC)
    await worker._execute(first)

    unit = repository.units[UNIT_ID]
    assert unit["status"] == "RETRY_WAITING"
    assert unit["error_code"] == "MODEL_RATE_LIMITED"
    assert unit["next_attempt_at"] is not None
    assert unit["next_attempt_at"] > before

    unit["next_attempt_at"] = datetime.now(UTC) - timedelta(seconds=1)
    second = await repository.claim_next_unit("test-worker", 60)
    assert second is not None
    await worker._execute(second)

    assert calls == 2
    assert repository.units[UNIT_ID]["status"] == "COMPLETED"
    assert repository.jobs[JOB_ID]["status"] == "COMPLETED"


@pytest.mark.asyncio
async def test_review_submission_replay_keeps_stable_run_id_after_crash(
    settings: Settings,
    monkeypatch: Any,
) -> None:
    repository = await _seed_repository(CrashAfterSubmitRepository())
    worker = _worker(repository, settings)
    run_ids: list[str] = []
    created_requests: dict[str, UUID] = {}

    async def submit_review(self: object, **kwargs: Any) -> dict[str, Any]:
        run_id = str(kwargs["run_id"])
        run_ids.append(run_id)
        created_requests.setdefault(run_id, uuid4())
        return {
            "status": "SUBMITTED",
            "change_request_id": str(created_requests[run_id]),
        }

    monkeypatch.setattr("app.worker.JobExecutor.execute", submit_review)

    first = await repository.claim_next_unit("test-worker", 60)
    assert first is not None
    with pytest.raises(SimulatedCrash):
        await worker._execute(first)

    repository.units[UNIT_ID]["lease_expires_at"] = datetime.now(UTC) - timedelta(
        seconds=1
    )
    second = await repository.claim_next_unit("test-worker", 60)
    assert second is not None
    await worker._execute(second)

    assert len(run_ids) == 2
    assert run_ids[0] == run_ids[1]
    assert len(created_requests) == 1
    assert repository.units[UNIT_ID]["status"] == "COMPLETED"


@pytest.mark.asyncio
async def test_applied_idempotent_replay_is_accepted_from_mcp() -> None:
    class AppliedReplayClient(OfficialMcpClient):
        async def _invoke_tool(
            self,
            name: str,
            arguments: dict[str, Any],
            authorization: str,
        ) -> dict[str, Any]:
            return {
                "changeRequestId": "99999999-9999-9999-9999-999999999999",
                "status": "APPLIED",
                "idempotentReplay": True,
            }

    client = AppliedReplayClient("http://mcp.invalid", 1)

    result = await client.submit_document_change(
        {"clientRequestId": "agent-run-fp"},
        workspace_id=WORKSPACE_ID,
        run_id="run",
        authorization="Bearer token",
    )

    assert result["status"] == "APPLIED"
    assert result["idempotentReplay"] is True
