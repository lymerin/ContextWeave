import asyncio
import json
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any
from uuid import UUID

import asyncpg
import pytest
from conftest import (
    FakeDelegationClient,
    FakeMcpClient,
    FakeModelProvider,
    MemoryAgentJobRepository,
    MemoryRunStore,
)
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app
from app.persistence.job_repository import PostgresAgentJobRepository, decode_json_array
from app.providers.base import ModelProviderError
from app.runtime.file_classification import classify_file
from app.runtime.unit_grouping import build_analysis_units
from app.schemas.plans import AgentPlan
from app.worker import AgentWorker

WORKSPACE_ID = "11111111-1111-1111-1111-111111111111"
REPOSITORY_ID = "22222222-2222-2222-2222-222222222222"
AUTH = {"Authorization": "Bearer transient-secret"}


def test_review_id_aggregate_decodes_postgres_json_text_as_an_array() -> None:
    review_id = "a489d027-ac95-42cd-ac8c-21d0d185b503"
    assert decode_json_array(json.dumps([review_id])) == [review_id]


def test_create_current_file_job_returns_202_without_running_model(settings: Settings) -> None:
    model = FakeModelProvider()
    repository = MemoryAgentJobRepository()
    with _client(settings, repository, model=model) as client:
        response = client.post("/api/v1/agent-jobs", json=_payload(), headers=AUTH)
    assert response.status_code == 202
    assert response.json()["status"] == "QUEUED"
    assert response.json()["createdAt"]
    assert model.calls == []
    assert len(repository.jobs) == len(repository.units) == 1
    assert next(iter(repository.jobs.values()))["status"] == "QUEUED"
    assert next(iter(repository.units.values()))["status"] == "PENDING"


@pytest.mark.parametrize(
    "scope",
    [
        {"type": "DIRECTORY", "pathPrefix": "src"},
        {"type": "GIT_CHANGES"},
    ],
)
def test_persistent_job_api_rejects_non_current_file_scope(
    scope: dict[str, Any], client: TestClient
) -> None:
    response = client.post(
        "/api/v1/agent-jobs",
        json={**_payload(), "scope": scope},
        headers=AUTH,
    )
    assert response.status_code == 422


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("userId", WORKSPACE_ID),
        ("status", "QUEUED"),
        ("jobId", WORKSPACE_ID),
        ("revision", "abc"),
        ("Authorization", "secret"),
    ],
)
def test_job_request_forbids_identity_and_runtime_fields(
    field: str, value: str, client: TestClient
) -> None:
    response = client.post(
        "/api/v1/agent-jobs",
        json={**_payload(), field: value},
        headers=AUTH,
    )
    assert response.status_code == 422


def test_job_requires_bearer(client: TestClient) -> None:
    response = client.post("/api/v1/agent-jobs", json=_payload())
    assert response.status_code == 401


def test_database_record_excludes_browser_credentials_and_source(settings: Settings) -> None:
    repository = MemoryAgentJobRepository()
    with _client(settings, repository) as client:
        response = client.post(
            "/api/v1/agent-jobs",
            json={**_payload(), "userInstruction": "核对接口"},
            headers=AUTH,
        )
    stored = json.dumps(list(repository.jobs.values()), default=str, ensure_ascii=False)
    assert response.status_code == 202
    assert "transient-secret" not in stored
    assert "Authorization" not in stored
    assert "class Example" not in stored
    assert "abc" in stored


def test_get_job_uses_delegation_authorization(settings: Settings) -> None:
    repository = MemoryAgentJobRepository()
    delegation = FakeDelegationClient()
    with _client(settings, repository, delegation=delegation) as client:
        created = client.post("/api/v1/agent-jobs", json=_payload(), headers=AUTH)
        response = client.get(
            f"/api/v1/agent-jobs/{created.json()['jobId']}", headers=AUTH
        )
        delegation.authorized = False
        denied = client.get(
            f"/api/v1/agent-jobs/{created.json()['jobId']}", headers=AUTH
        )
    assert response.status_code == 200
    assert response.json()["revision"] == "abc"
    assert response.json()["scopeType"] == "CURRENT_FILE"
    assert response.json()["phase"] is None
    assert denied.status_code == 403


@pytest.mark.asyncio
async def test_atomic_claim_prevents_two_workers_from_claiming_same_unit() -> None:
    repository = await _seed_repository()
    first, second = await asyncio.gather(
        repository.claim_next_unit("worker-a", 60),
        repository.claim_next_unit("worker-b", 60),
    )
    assert sum(value is not None for value in (first, second)) == 1


@pytest.mark.asyncio
async def test_active_lease_is_not_stolen_and_expired_lease_is_recovered() -> None:
    repository = await _seed_repository()
    claimed = await repository.claim_next_unit("worker-a", 60)
    assert claimed is not None
    assert await repository.claim_next_unit("worker-b", 60) is None
    repository.units[claimed["id"]]["lease_expires_at"] = datetime.now(UTC) - timedelta(seconds=1)
    recovered = await repository.claim_next_unit("worker-b", 60)
    assert recovered is not None
    assert recovered["attempt"] == 2
    assert recovered["worker_id"] == "worker-b"


@pytest.mark.asyncio
async def test_expired_lease_at_max_attempts_becomes_terminal_failure() -> None:
    repository = await _seed_repository()
    unit = next(iter(repository.units.values()))
    unit.update(
        status="RUNNING",
        worker_id="dead-worker",
        attempt=3,
        lease_expires_at=datetime.now(UTC) - timedelta(seconds=1),
    )

    assert await repository.claim_next_unit("replacement-worker", 60) is None
    assert unit["status"] == "FAILED"
    assert unit["error_code"] == "WORKER_LEASE_EXPIRED"
    job = next(iter(repository.jobs.values()))
    assert job["status"] == "FAILED"
    assert job["error_code"] == "WORKER_LEASE_EXPIRED"


@pytest.mark.asyncio
async def test_heartbeat_renews_only_the_current_worker_lease() -> None:
    repository = await _seed_repository()
    claimed = await repository.claim_next_unit("worker-a", 10)
    assert claimed is not None
    before = repository.units[claimed["id"]]["lease_expires_at"]
    assert not await repository.heartbeat(claimed["id"], "worker-b", 60)
    assert await repository.heartbeat(claimed["id"], "worker-a", 60)
    assert repository.units[claimed["id"]]["lease_expires_at"] > before


@pytest.mark.asyncio
async def test_worker_completes_no_change_job_with_short_lived_delegation(
    settings: Settings,
) -> None:
    repository = await _seed_repository()
    delegation = FakeDelegationClient()
    worker = _worker(repository, settings, delegation=delegation)
    claimed = await repository.claim_next_unit("test-worker", 60)
    assert claimed is not None
    await worker._execute(claimed)
    job = next(iter(repository.jobs.values()))
    assert job["status"] == "COMPLETED"
    assert job["result"] == "NO_CHANGE"
    assert delegation.exchanges >= 4


@pytest.mark.asyncio
async def test_worker_does_not_submit_without_program_block_plan(
    settings: Settings,
) -> None:
    plan = AgentPlan.model_validate(
        {
            "decision": "SUBMIT_REVIEW",
            "summary": "需要同步正式文档",
            "rationale": "代码行为已经变化。",
            "operations": [],
            "bindingProposals": [
                {
                    "clientBindingProposalId": "binding-1",
                    "sequenceNumber": 1,
                    "action": "UPSERT_BINDING",
                    "repositoryId": REPOSITORY_ID,
                    "filePath": "src/Example.java",
                    "documentId": "55555555-5555-5555-5555-555555555555",
                    "reason": "代码文件需要关联正式设计文档。",
                }
            ],
            "evidence": [
                {
                    "repositoryId": REPOSITORY_ID,
                    "filePath": "src/Example.java",
                    "startLine": 1,
                    "endLine": 1,
                    "description": "当前实现需要文档关联。",
                }
            ],
        }
    )
    repository = await _seed_repository()
    worker = _worker(
        repository,
        settings,
        provider=FakeModelProvider([plan]),
        mcp=FakeMcpClient(bound=False),
    )
    claimed = await repository.claim_next_unit("test-worker", 60)
    assert claimed is not None
    await worker._execute(claimed)
    job = next(iter(repository.jobs.values()))
    assert job["status"] == "COMPLETED"
    assert job["result"] == "NO_CHANGE"
    assert job["review_request_ids"] == []


@pytest.mark.asyncio
async def test_model_is_not_called_without_program_block_plan(settings: Settings) -> None:
    repository = await _seed_repository()
    provider = FakeModelProvider([ModelProviderError("MODEL_TIMEOUT", "timeout")])
    worker = _worker(repository, settings, provider=provider)
    claimed = await repository.claim_next_unit("test-worker", 60)
    assert claimed is not None
    await worker._execute(claimed)
    unit = next(iter(repository.units.values()))
    assert unit["status"] == "COMPLETED"
    assert unit["next_attempt_at"] is None
    assert next(iter(repository.jobs.values()))["status"] == "COMPLETED"
    assert provider.block_content_calls == []


@pytest.mark.asyncio
async def test_max_attempt_unit_without_block_plan_completes_without_model(
    settings: Settings,
) -> None:
    repository = await _seed_repository()
    next(iter(repository.units.values()))["attempt"] = 2
    provider = FakeModelProvider([ModelProviderError("MODEL_TIMEOUT", "timeout")])
    worker = _worker(repository, settings, provider=provider)
    claimed = await repository.claim_next_unit("test-worker", 60)
    assert claimed is not None
    assert claimed["attempt"] == 3

    await worker._execute(claimed)

    assert next(iter(repository.units.values()))["status"] == "COMPLETED"
    assert next(iter(repository.jobs.values()))["status"] == "COMPLETED"
    assert next(iter(repository.jobs.values()))["error_code"] is None
    assert provider.block_content_calls == []


@pytest.mark.asyncio
async def test_permission_error_fails_without_retry(settings: Settings) -> None:
    repository = await _seed_repository()
    delegation = FakeDelegationClient()
    delegation.authorized = False
    worker = _worker(repository, settings, delegation=delegation)
    claimed = await repository.claim_next_unit("test-worker", 60)
    assert claimed is not None
    await worker._execute(claimed)
    assert next(iter(repository.units.values()))["status"] == "FAILED"
    assert next(iter(repository.jobs.values()))["error_code"] == "MCP_PERMISSION_DENIED"


def test_postgres_job_json_fields_are_decoded_from_asyncpg_text() -> None:
    decoded = PostgresAgentJobRepository._decode_job(  # type: ignore[arg-type]
        {
            "scope_payload": '{"type":"CURRENT_FILE","filePath":"src/Example.java"}',
            "review_request_ids": '["99999999-9999-9999-9999-999999999999"]',
        }
    )

    assert decoded["scope_payload"]["filePath"] == "src/Example.java"
    assert decoded["review_request_ids"] == [
        "99999999-9999-9999-9999-999999999999"
    ]


@pytest.mark.asyncio
async def test_worker_stops_unit_when_lease_heartbeat_is_lost(
    settings: Settings,
) -> None:
    class LeaseLosingRepository(MemoryAgentJobRepository):
        async def heartbeat(
            self, unit_id: UUID, worker_id: str, lease_seconds: int
        ) -> bool:
            return False

    class SlowProvider(FakeModelProvider):
        async def plan_document_sync(
            self,
            context_bundle: dict[str, Any],
            *,
            previous_plan: dict[str, Any] | None = None,
            validation_errors: list[dict[str, str]] | None = None,
        ) -> AgentPlan:
            await asyncio.sleep(1)
            return await super().plan_document_sync(
                context_bundle,
                previous_plan=previous_plan,
                validation_errors=validation_errors,
            )

    repository = LeaseLosingRepository()
    seeded = await _seed_repository()
    repository.jobs = seeded.jobs
    repository.units = seeded.units
    worker = _worker(repository, settings, provider=SlowProvider())
    claimed = await repository.claim_next_unit("test-worker", 60)
    assert claimed is not None

    await worker._execute(claimed)

    assert next(iter(repository.units.values()))["status"] == "FAILED"
    assert next(iter(repository.jobs.values()))["error_code"] == "INTERNAL_ERROR"


def test_postgres_claim_statement_uses_skip_locked() -> None:
    source = (
        Path(__file__).parents[1] / "app" / "persistence" / "job_repository.py"
    ).read_text(encoding="utf-8")
    assert "FOR UPDATE OF u SKIP LOCKED" in source
    assert "AND u.attempt < u.max_attempts" in source
    assert "WORKER_LEASE_EXPIRED" in source


def test_postgres_failure_statement_casts_reused_status_parameter() -> None:
    source = (
        Path(__file__).parents[1] / "app" / "persistence" / "job_repository.py"
    ).read_text(encoding="utf-8")
    assert "SET status = $3::varchar" in source
    assert "WHEN $3::varchar = 'FAILED'" in source


def test_default_worker_identity_is_unique_per_process_incarnation(
    settings: Settings,
) -> None:
    repository = MemoryAgentJobRepository()

    first = AgentWorker(
        repository,
        FakeMcpClient(),
        FakeDelegationClient(),
        FakeModelProvider(),
        settings,
    )
    second = AgentWorker(
        repository,
        FakeMcpClient(),
        FakeDelegationClient(),
        FakeModelProvider(),
        settings,
    )

    assert first._worker_id != second._worker_id


def test_temporary_database_connection_error_is_retryable() -> None:
    code, message = AgentWorker._safe_error(
        asyncpg.ConnectionDoesNotExistError("connection was closed")
    )

    assert code == "DATABASE_UNAVAILABLE"
    assert message == "Agent database is temporarily unavailable"


def test_file_classification_and_v2_grouping_foundation_remain_available() -> None:
    files = [
        classify_file(
            {"filePath": "module-a/src/A.java", "sizeBytes": 10, "readable": True},
            max_size_bytes=100,
        ),
        classify_file(
            {"filePath": "module-b/src/B.java", "sizeBytes": 10, "readable": True},
            max_size_bytes=100,
        ),
    ]
    units = build_analysis_units(
        files,
        {},
        source_type="PROJECT_INITIALIZATION",
        max_files=6,
        max_deleted=100,
        max_units=10,
    )
    assert len(units) == 2


def _payload() -> dict[str, Any]:
    return {
        "workspaceId": WORKSPACE_ID,
        "repositoryId": REPOSITORY_ID,
        "scope": {"type": "CURRENT_FILE", "filePath": "src/Example.java"},
        "userInstruction": None,
    }


def _client(
    settings: Settings,
    repository: MemoryAgentJobRepository,
    *,
    delegation: FakeDelegationClient | None = None,
    model: FakeModelProvider | None = None,
) -> TestClient:
    return TestClient(
        create_app(
            settings=settings,
            mcp_client=FakeMcpClient(),
            run_store=MemoryRunStore(),
            model_provider=model or FakeModelProvider(),
            job_repository=repository,
            delegation_client=delegation or FakeDelegationClient(),
        )
    )


async def _seed_repository() -> MemoryAgentJobRepository:
    repository = MemoryAgentJobRepository()
    now = datetime.now(UTC)
    await repository.create_job(
        {
            "id": UUID("aaaaaaaa-0000-0000-0000-000000000001"),
            "delegation_id": UUID("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
            "created_by_user_id": UUID("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
            "workspace_id": UUID(WORKSPACE_ID),
            "repository_id": UUID(REPOSITORY_ID),
            "revision": "abc",
            "scope_type": "CURRENT_FILE",
            "scope_payload": {"type": "CURRENT_FILE", "filePath": "src/Example.java"},
            "user_instruction": None,
            "created_at": now,
        },
        {
            "id": UUID("aaaaaaaa-0000-0000-0000-000000000002"),
            "max_attempts": 3,
            "unit_kind": "CURRENT_FILE_ANALYSIS",
        },
    )
    return repository


def _worker(
    repository: MemoryAgentJobRepository,
    settings: Settings,
    *,
    delegation: FakeDelegationClient | None = None,
    provider: FakeModelProvider | None = None,
    mcp: FakeMcpClient | None = None,
) -> AgentWorker:
    return AgentWorker(
        repository,
        mcp or FakeMcpClient(),
        delegation or FakeDelegationClient(),
        provider or FakeModelProvider(),
        settings.model_copy(update={"agent_worker_heartbeat_seconds": 0.01}),
        worker_id="test-worker",
    )
