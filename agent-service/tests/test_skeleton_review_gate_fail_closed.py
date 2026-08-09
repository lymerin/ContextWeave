"""骨架 review 审批门 fail-closed 回归测试（施工计划 1.4）。

缺陷背景：worker._execute 的审批门只识别三种状态——PENDING（重试）、
REJECTED/STALE（NO_CHANGE）。其余一切值直接落入"继续执行"分支：
  - `_review_status` 404 时返回 "MISSING"（骨架 review 刚提交、
    读模型传播未完成即查询 → 404 是常态竞态）
  - 响应缺 status 字段时返回 ""
  - 未预期状态（CANCELLED/PROCESSING）原样透出
fail-open 的后果：审批未 APPLIED 批次就执行 → candidates 查不到绑定
→ 重复创建文档的变更请求。

修复后契约（本测试断言）：
  1. 非 APPLIED 的一切状态（含 MISSING/空串/CANCELLED/PROCESSING）
     → 单元 RETRY_WAITING + SKELETON_REVIEW_PENDING + 300s 退避 + attempt 归零
  2. SKELETON_REVIEW_PENDING 豁免 attempt < max_attempts 预算判定：
     真实失败耗尽预算后撞 PENDING 也必须等待，不能永久 FAILED
     （现有 worker.py:547 的 `attempt < max_attempts` 在第 3 次真实
     失败后再撞 PENDING 时会 fail 而不重试 → 单元永久 FAILED）
修复前：测试 1 的未知状态会执行 JobExecutor（FAILED 或提交），
测试 2 第三次 PENDING 撞墙后单元 FAILED（红）。
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
) -> AgentWorker:
    return AgentWorker(
        repository,
        FakeMcpClient(code_content="class Example {}"),
        FakeDelegationClient(),
        FakeModelProvider(),
        settings.model_copy(update={"agent_worker_heartbeat_seconds": 0.01}),
        worker_id="test-worker",
    )


def _expire_backoff(repository: MemoryAgentJobRepository) -> None:
    """把退避等待期拨到已过期，允许再次 claim。"""
    repository.units[UNIT_ID]["next_attempt_at"] = datetime.now(UTC) - timedelta(
        seconds=1
    )


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "unknown_status",
    ["MISSING", "", "CANCELLED", "PROCESSING"],
)
async def test_unknown_review_status_never_executes_batch(
    settings: Any, monkeypatch: Any, unknown_status: str
) -> None:
    """fail-closed：非 APPLIED 的一切状态都按 PENDING 等待，绝不放行执行。"""
    repository = await _seed_batch_unit()
    worker = _worker(repository, settings)

    async def review_status(
        workspace_id: UUID, review_id: UUID, **kwargs: Any
    ) -> str:
        return unknown_status

    monkeypatch.setattr(worker, "_review_status", review_status)
    # 门控若 fail-open，JobExecutor 会被构造并执行 → 直接炸测试
    async def must_not_execute(*args: Any, **kwargs: Any) -> Any:
        raise AssertionError(
            f"fail-open: 审批门对状态 {unknown_status!r} 放行了批次执行"
        )

    monkeypatch.setattr(
        "app.worker.JobExecutor.execute", AsyncMock(side_effect=must_not_execute)
    )

    claimed = await repository.claim_next_unit("test-worker", 60)
    assert claimed is not None
    before = datetime.now(UTC)
    await worker._execute(claimed)

    unit = repository.units[UNIT_ID]
    assert unit["status"] == "RETRY_WAITING", f"状态 {unknown_status!r} 必须等待而非执行"
    assert unit["error_code"] == "SKELETON_REVIEW_PENDING"
    assert unit["attempt"] == 0  # 等待不消耗重试预算
    assert unit["next_attempt_at"] is not None
    wait_seconds = (unit["next_attempt_at"] - before).total_seconds()
    assert 290 <= wait_seconds <= 310  # 固定 300s 长退避


@pytest.mark.asyncio
async def test_pending_collision_after_consumed_budget_still_waits(
    settings: Any, monkeypatch: Any
) -> None:
    """预算边角：真实失败耗尽 attempt 后再撞 PENDING，必须等待而非永久 FAILED。

    场景：2 次真实失败（attempt 到 2）→ 第 3 次 claim（attempt=3=max_attempts）
    撞 PENDING。worker.py 的 `attempt < max_attempts` 预算判定会判否 → FAILED。
    修复：SKELETON_REVIEW_PENDING 豁免该判定（审批无论多晚都有完整机会）。
    """
    repository = await _seed_batch_unit()

    for _ in range(2):  # 两次真实失败消耗预算
        claimed = await repository.claim_next_unit("test-worker", 60)
        assert claimed is not None
        await repository.fail_unit(
            UNIT_ID,
            "test-worker",
            "MODEL_TIMEOUT",
            "超时",
            datetime.now(UTC) - timedelta(seconds=1),
        )

    worker = _worker(repository, settings)
    async def pending_review(
        workspace_id: UUID, review_id: UUID, **kwargs: Any
    ) -> str:
        return "PENDING"

    monkeypatch.setattr(worker, "_review_status", pending_review)

    claimed = await repository.claim_next_unit("test-worker", 60)
    assert claimed is not None
    assert claimed["attempt"] == 3  # claim 时 +1，触达 max_attempts

    await worker._execute(claimed)

    unit = repository.units[UNIT_ID]
    assert unit["status"] == "RETRY_WAITING", "PENDING 撞墙必须等待，不得因预算判死"
    assert unit["error_code"] == "SKELETON_REVIEW_PENDING"
    assert unit["attempt"] == 2  # 撞墙减回


def test_worker_budget_check_exempts_pending_errors() -> None:
    """实现级保护：预算判定必须豁免 SKELETON_REVIEW_PENDING。

    防止后人把 `attempt < max_attempts` 条件改回不豁免的形态时
    测试 2 失守（该形态在真实失败耗尽预算后会永久 FAILED）。
    """
    source = (
        Path(__file__).parents[1] / "app" / "worker.py"
    ).read_text(encoding="utf-8")
    import re

    # 找到预算判定语句，要求其中显式包含 PENDING 豁免
    assert re.search(
        r"code\s*==\s*[\"']SKELETON_REVIEW_PENDING[\"']\s+or|"
        r"[\"']SKELETON_REVIEW_PENDING[\"']\s*==\s*code\s+or",
        source,
    ), "预算判定必须显式豁免 SKELETON_REVIEW_PENDING"
