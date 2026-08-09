import asyncio

import pytest

from app.runtime.staged_concurrency import (
    AimdConcurrencyController,
    ModelRateLimitError,
    PipelineConfig,
    StageConfig,
    StagedPipeline,
)


@pytest.mark.asyncio
async def test_model_rate_limit_halves_concurrency_and_retries_task():
    calls = 0
    slept = []
    controller = AimdConcurrencyController(
        initial_limit=4,
        min_limit=1,
        max_limit=8,
        stable_successes=4,
    )

    async def passthrough(value):
        return value

    async def model(value):
        nonlocal calls
        calls += 1
        if calls == 1:
            raise ModelRateLimitError("429")
        return value

    async def sleep(seconds):
        slept.append(seconds)

    pipeline = StagedPipeline(
        config=config(),
        read=passthrough,
        parse=passthrough,
        model=model,
        submit=passthrough,
        model_controller=controller,
        sleep=sleep,
    )

    assert await pipeline.run(["unit-1"]) == ["unit-1"]
    assert controller.current_limit == 2
    assert calls == 2
    assert slept


@pytest.mark.asyncio
async def test_stable_model_calls_additively_restore_concurrency():
    controller = AimdConcurrencyController(
        initial_limit=2,
        min_limit=1,
        max_limit=4,
        stable_successes=2,
    )

    await controller.record_success(10)
    await controller.record_success(10)
    await controller.record_success(10)
    await controller.record_success(10)

    assert controller.current_limit == 4


@pytest.mark.asyncio
async def test_bounded_queues_apply_backpressure_without_dropping_tasks():
    async def read(value):
        return f"read:{value}"

    async def parse(value):
        await asyncio.sleep(0)
        return f"parse:{value}"

    async def model(value):
        return f"model:{value}"

    async def submit(value):
        await asyncio.sleep(0)
        return f"submit:{value}"

    pipeline = StagedPipeline(
        config=PipelineConfig(
            read=StageConfig(workers=1, queue_size=1),
            parse=StageConfig(workers=1, queue_size=1),
            model=StageConfig(workers=1, queue_size=1),
            submit=StageConfig(workers=1, queue_size=1),
        ),
        read=read,
        parse=parse,
        model=model,
        submit=submit,
    )

    result = await pipeline.run(range(12))

    assert result == [
        f"submit:model:parse:read:{index}"
        for index in range(12)
    ]


def config():
    return PipelineConfig(
        read=StageConfig(workers=1, queue_size=2),
        parse=StageConfig(workers=1, queue_size=2),
        model=StageConfig(workers=4, queue_size=2),
        submit=StageConfig(workers=1, queue_size=2),
    )
