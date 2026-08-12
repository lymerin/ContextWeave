import sys
from typing import Any

sys.path.insert(0, "agent-service")

import asyncio

import pytest

from app.planning.deepseek_unit_planner import DeepSeekUnitPlanner
from app.providers.base import ModelProvider, ModelProviderError
from app.schemas.unit_plans import UnitPlan, UnitPlanItem


def _unit_plan() -> UnitPlan:
    # primaryFiles 必须存在于 validation_index 的文件列表，
    # 否则 consolidation 边界校验报 UNKNOWN_FILE。
    return UnitPlan(units=[UnitPlanItem(
        name="mod-a",
        kind="GENERIC_MODULE",
        summary="module a",
        primaryFiles=["src/f0.py"],
        groupingEvidence=["e"],
    )])


def _plan_for_files(paths: list[str]) -> UnitPlan:
    return UnitPlan(units=[UnitPlanItem(
        name="mod-all",
        kind="GENERIC_MODULE",
        summary="module all",
        primaryFiles=paths,
        groupingEvidence=["e"],
    )])


def _batch(index: int) -> dict[str, Any]:
    return {
        "repositoryId": "repo-1",
        "revision": "rev-1",
        "topLevelModules": ["src"],
        "files": [{"filePath": f"src/f{index}.py", "eligible": True}],
        "batch": {"index": index, "count": 3},
    }


def _validation_index(batch_count: int) -> dict[str, Any]:
    return {
        "repositoryId": "repo-1",
        "revision": "rev-1",
        "topLevelModules": ["src"],
        "files": [
            {"filePath": f"src/f{i}.py", "eligible": True}
            for i in range(batch_count)
        ],
        "documents": [],
    }


class _ProbeProvider(ModelProvider):
    """记录并发峰值与调用顺序的测试 provider。"""

    def __init__(
        self,
        plan: UnitPlan | None = None,
        *,
        delay: float = 0.05,
        fail_batches: set[int] | None = None,
        fail_code: str = "MODEL_INVALID_RESPONSE",
    ) -> None:
        self._plan = plan or _unit_plan()
        self._delay = delay
        self._fail_batches = fail_batches or set()
        self._fail_code = fail_code
        self._active = 0
        self._peak = 0
        self._lock = asyncio.Lock()
        self.batch_calls: list[int] = []
        self.consolidation_calls: list[dict[str, Any]] = []

    async def plan_project_units(
        self,
        project_index: dict[str, Any],
        *,
        previous_plan: dict[str, Any] | None = None,
        validation_errors: list[dict[str, str]] | None = None,
    ) -> UnitPlan:
        async with self._lock:
            self._active += 1
            self._peak = max(self._peak, self._active)
        try:
            await asyncio.sleep(self._delay)
            batch = project_index.get("batch")
            if batch is not None:
                index = int(batch.get("index", 0))
                self.batch_calls.append(index)
                if index in self._fail_batches:
                    raise ModelProviderError(
                        self._fail_code, f"batch {index} failed"
                    )
                return self._plan
            # consolidation 调用（无 batch 标记）
            self.consolidation_calls.append(project_index)
            return _plan_for_files([
                str(item["filePath"]) for item in project_index.get("files", [])
            ])
        finally:
            async with self._lock:
                self._active -= 1


@pytest.mark.asyncio
async def test_batches_are_planned_concurrently() -> None:
    provider = _ProbeProvider(delay=0.08)
    planner = DeepSeekUnitPlanner(
        provider, max_files_per_unit=10, max_units=50, batch_concurrency=2
    )
    batches = [_batch(i) for i in range(3)]

    await planner.plan(batches, validation_index=_validation_index(3))

    assert provider._peak > 1, (
        f"expected concurrent batch planning, peak was {provider._peak}"
    )
    assert len(provider.batch_calls) == 3


@pytest.mark.asyncio
async def test_batches_are_collected_in_original_order() -> None:
    provider = _ProbeProvider(delay=0.03)
    planner = DeepSeekUnitPlanner(
        provider, max_files_per_unit=10, max_units=50, batch_concurrency=4
    )
    batches = [_batch(i) for i in range(5)]

    await planner.plan(batches, validation_index=_validation_index(5))

    assert provider.batch_calls == [0, 1, 2, 3, 4]
    # consolidation 收到的候选按原 batch 顺序排列
    assert len(provider.consolidation_calls) == 1
    candidates = provider.consolidation_calls[0]["candidateBatchPlans"]
    assert [c["batch"]["index"] for c in candidates] == [0, 1, 2, 3, 4]


@pytest.mark.asyncio
async def test_invalid_batch_is_dropped_without_dropping_others() -> None:
    provider = _ProbeProvider(fail_batches={1})
    planner = DeepSeekUnitPlanner(
        provider, max_files_per_unit=10, max_units=50, batch_concurrency=3
    )
    batches = [_batch(i) for i in range(3)]

    plan = await planner.plan(batches, validation_index=_validation_index(3))

    assert len(provider.consolidation_calls) == 1
    candidates = provider.consolidation_calls[0]["candidateBatchPlans"]
    assert [c["batch"]["index"] for c in candidates] == [0, 2]
    assert len(plan.units) == 1


@pytest.mark.asyncio
async def test_concurrency_one_degrades_to_serial() -> None:
    provider = _ProbeProvider(delay=0.03)
    planner = DeepSeekUnitPlanner(
        provider, max_files_per_unit=10, max_units=50, batch_concurrency=1
    )
    batches = [_batch(i) for i in range(3)]

    await planner.plan(batches, validation_index=_validation_index(3))

    assert provider._peak == 1
    assert provider.batch_calls == [0, 1, 2]


@pytest.mark.asyncio
async def test_hard_provider_error_propagates() -> None:
    provider = _ProbeProvider(fail_batches={0}, fail_code="MODEL_UNAVAILABLE")
    planner = DeepSeekUnitPlanner(
        provider, max_files_per_unit=10, max_units=50, batch_concurrency=2
    )
    batches = [_batch(i) for i in range(3)]

    with pytest.raises(ModelProviderError) as exc:
        await planner.plan(batches, validation_index=_validation_index(3))
    assert exc.value.code == "MODEL_UNAVAILABLE"
