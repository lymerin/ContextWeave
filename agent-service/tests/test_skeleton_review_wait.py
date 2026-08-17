"""骨架 review 等待策略回归测试——占位重试风暴。

缺陷背景：批次单元在骨架 review 审批前反复 claim → 撞
SkeletonReviewPendingError → 重试。claim 时 attempt + 1，撞墙也消耗
attempt：审批晚于 ~6 分钟（60s/300s 阶梯 × 3 次）单元即 FAILED，
占位永远不被替换；审批前 N 个单元高频撞墙形成重试风暴。

修复：
  1. fail_unit 对 SKELETON_REVIEW_PENDING 把 attempt 减回（claim +1 /
     撞墙 -1 → 净零），等待不消耗重试预算；
  2. PENDING 固定 300s 长退避，不再 60s 阶梯风暴。
"""

import sys
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any
from unittest.mock import AsyncMock
from uuid import UUID

sys.path.insert(0, str(Path(__file__).parents[1]))

import pytest
from conftest import (
    FakeDelegationClient,
    FakeMcpClient,
    FakeModelProvider,
    MemoryAgentJobRepository,
)

from app.worker import AgentWorker

WORKSPACE_ID = "11111111-1111-1111-1111-111111111111"
REPOSITORY_ID = "22222222-2222-2222-2222-222222222222"
JOB_ID = UUID("aaaaaaaa-0000-0000-0000-000000000001")
UNIT_ID = UUID("aaaaaaaa-0000-0000-0000-000000000002")
DELEGATION_ID = UUID("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
CREATED_BY = UUID("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")


def _slot_plan(review_id: str = "99999999-9999-9999-9999-999999999999") -> dict[str, Any]:
    return {
        "skeletonReviewId": review_id,
        "batchLabel": "batch-1",
        "slots": [
            {
                "slot_id": "flow:JAVA:src/Example.java:Example:CLASS",
                "slot_type": "FLOW",
                "title": "主要流程：Example",
                "primary_symbol_key": "JAVA:src/Example.java:Example:CLASS",
                "placeholder": "⏳ 占位",
                "sort_order": 0,
                "file_path": "src/Example.java",
            }
        ],
    }


async def _seed_batch_unit() -> MemoryAgentJobRepository:
    repository = MemoryAgentJobRepository()
    now = datetime.now(UTC)
    await repository.create_job(
        {
            "id": JOB_ID,
            "delegation_id": DELEGATION_ID,
            "created_by_user_id": CREATED_BY,
            "workspace_id": UUID(WORKSPACE_ID),
            "repository_id": UUID(REPOSITORY_ID),
            "revision": "abc",
            "scope_type": "DIRECTORY",
            "scope_payload": {"type": "DIRECTORY", "pathPrefix": "src"},
            "user_instruction": None,
            "created_at": now,
        },
        {
            "id": UNIT_ID,
            "max_attempts": 3,
            "unit_kind": "SEMANTIC_ANALYSIS",
            "slot_plan": _slot_plan(),
            "display_name": "示例模块 batch:1",
            "files": [{"filePath": "src/Example.java", "role": "PRIMARY"}],
        },
    )
    return repository


def _worker(
    repository: MemoryAgentJobRepository,
    settings: Any,
    *,
    provider: FakeModelProvider | None = None,
) -> AgentWorker:
    return AgentWorker(
        repository,
        FakeMcpClient(code_content="class Example {}"),
        FakeDelegationClient(),
        provider or FakeModelProvider(),
        settings.model_copy(update={"agent_worker_heartbeat_seconds": 0.01}),
        worker_id="test-worker",
    )


@pytest.mark.asyncio
async def test_pending_collision_does_not_consume_attempt(settings: Any) -> None:
    """PENDING 撞墙不消耗 attempt：无论审批多晚，单元始终有完整重试预算。"""
    repository = await _seed_batch_unit()
    # 已到期的 next_attempt_at：模拟退避等待期已过、worker 再次 claim
    retry_at = datetime.now(UTC) - timedelta(seconds=1)

    for _ in range(10):  # 撞墙 10 次（远多于 max_attempts=3）
        claimed = await repository.claim_next_unit("test-worker", 60)
        assert claimed is not None, "PENDING 撞墙减回 attempt 后单元必须仍可 claim"
        assert claimed["attempt"] == 1  # claim 时 +1
        await repository.fail_unit(
            UNIT_ID, "test-worker", "SKELETON_REVIEW_PENDING", "等待审批", retry_at
        )
        unit = repository.units[UNIT_ID]
        assert unit["status"] == "RETRY_WAITING"
        assert unit["attempt"] == 0  # 撞墙减回，净零
    # 永不 FAILED：占位替换机会始终保留
    assert repository.units[UNIT_ID]["status"] == "RETRY_WAITING"


@pytest.mark.asyncio
async def test_ordinary_failure_still_consumes_attempt(settings: Any) -> None:
    """对照组：普通错误（MODEL_TIMEOUT）仍正常消耗 attempt，耗尽后 FAILED。"""
    repository = await _seed_batch_unit()
    for _ in range(3):
        claimed = await repository.claim_next_unit("test-worker", 60)
        assert claimed is not None
        # 前两次重试已到期，第三次 retry_at=None → FAILED
        retry_at = (
            datetime.now(UTC) - timedelta(seconds=1)
            if claimed["attempt"] < 3
            else None
        )
        await repository.fail_unit(
            UNIT_ID, "test-worker", "MODEL_TIMEOUT", "超时", retry_at
        )
    unit = repository.units[UNIT_ID]
    assert unit["status"] == "FAILED"
    assert unit["attempt"] == 3


@pytest.mark.asyncio
async def test_worker_pending_collision_requeues_with_long_backoff(
    settings: Any, monkeypatch: Any
) -> None:
    """worker 门控：PENDING 撞墙 → RETRY_WAITING + 300s 退避 + attempt 归零。"""
    repository = await _seed_batch_unit()
    worker = _worker(repository, settings)
    # 不真打 knowledge-core，直接桩 review 状态
    async def pending_review(
        workspace_id: UUID, review_id: UUID, **kwargs: Any
    ) -> str:
        return "PENDING"

    monkeypatch.setattr(worker, "_review_status", pending_review)

    claimed = await repository.claim_next_unit("test-worker", 60)
    assert claimed is not None
    before = datetime.now(UTC)
    await worker._execute(claimed)

    unit = repository.units[UNIT_ID]
    assert unit["status"] == "RETRY_WAITING"
    assert unit["error_code"] == "SKELETON_REVIEW_PENDING"
    assert unit["review_request_id"] == UUID("99999999-9999-9999-9999-999999999999")
    assert repository.jobs[JOB_ID]["review_request_ids"] == [
        "99999999-9999-9999-9999-999999999999"
    ]
    assert unit["attempt"] == 0
    # 固定 300s 长退避，而非 60s 阶梯（风暴的根源）
    assert unit["next_attempt_at"] is not None
    wait_seconds = (unit["next_attempt_at"] - before).total_seconds()
    assert 290 <= wait_seconds <= 310


@pytest.mark.asyncio
async def test_approved_review_lets_unit_complete(settings: Any, monkeypatch: Any) -> None:
    """审批 APPLIED 后：单元不再被门控拦截，正常执行完成（占位被替换）。"""
    repository = await _seed_batch_unit()
    worker = _worker(repository, settings)
    statuses = iter(["PENDING", "APPLIED"])

    async def review_status(
        workspace_id: UUID, review_id: UUID, **kwargs: Any
    ) -> str:
        return next(statuses)

    monkeypatch.setattr(worker, "_review_status", review_status)

    # 审批前：撞墙一次（attempt 归零，预算完整）
    claimed = await repository.claim_next_unit("test-worker", 60)
    await worker._execute(claimed)
    assert repository.units[UNIT_ID]["status"] == "RETRY_WAITING"

    # 审批后：把执行短路为成功提交，验证门控放行且单元 COMPLETED
    submitted = {
        "status": "SUBMITTED",
        "change_request_id": "99999999-9999-9999-9999-999999999999",
        "scope_count": 1,
        "submitted": 1,
        "failed": 0,
        "results": [],
    }
    monkeypatch.setattr(
        "app.worker.JobExecutor.execute", AsyncMock(return_value=submitted)
    )
    # 退避等待期已过（模拟审批期间过去了 5 分钟）
    repository.units[UNIT_ID]["next_attempt_at"] = datetime.now(UTC) - timedelta(
        seconds=1
    )
    claimed = await repository.claim_next_unit("test-worker", 60)
    assert claimed is not None, "审批后单元必须仍可 claim（attempt 预算完整）"
    await worker._execute(claimed)

    unit = repository.units[UNIT_ID]
    assert unit["status"] == "COMPLETED"
    assert unit["result"] == "REVIEW_SUBMITTED"


def test_postgres_fail_statement_refunds_pending_attempt() -> None:
    """Postgres SQL 保护：SKELETON_REVIEW_PENDING 撞墙必须减回 attempt。"""
    source = (
        Path(__file__).parents[1]
        / "app"
        / "persistence"
        / "job_repository.py"
    ).read_text(encoding="utf-8")
    assert "WHEN $5::varchar = 'SKELETON_REVIEW_PENDING' THEN GREATEST(attempt - 1, 0)" in source
