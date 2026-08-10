import asyncio
import time
from collections.abc import Awaitable, Callable, Iterable
from dataclasses import dataclass
from typing import Any


StageFunc = Callable[[Any], Awaitable[Any]]
SleepFunc = Callable[[float], Awaitable[None]]


class ModelRateLimitError(RuntimeError):
    pass


@dataclass(frozen=True)
class StageConfig:
    workers: int
    queue_size: int

    def __post_init__(self) -> None:
        if self.workers < 1:
            raise ValueError("workers must be positive")
        if self.queue_size < 1:
            raise ValueError("queue_size must be positive")


@dataclass(frozen=True)
class PipelineConfig:
    read: StageConfig
    parse: StageConfig
    model: StageConfig
    submit: StageConfig


@dataclass(frozen=True)
class WorkItem:
    index: int
    value: Any


class AimdConcurrencyController:
    def __init__(
        self,
        *,
        initial_limit: int = 2,
        min_limit: int = 1,
        max_limit: int = 8,
        latency_threshold_ms: float = 30_000,
        stable_successes: int = 8,
        initial_backoff_seconds: float = 1.0,
        max_backoff_seconds: float = 30.0,
    ) -> None:
        if min_limit < 1 or initial_limit < min_limit or max_limit < initial_limit:
            raise ValueError("expected 1 <= min_limit <= initial_limit <= max_limit")
        self._current_limit = initial_limit
        self._min_limit = min_limit
        self._max_limit = max_limit
        self._latency_threshold_ms = latency_threshold_ms
        self._stable_successes = stable_successes
        self._initial_backoff_seconds = initial_backoff_seconds
        self._max_backoff_seconds = max_backoff_seconds
        self._backoff_seconds = initial_backoff_seconds
        self._stable_count = 0
        self._active = 0
        self._condition = asyncio.Condition()

    @property
    def current_limit(self) -> int:
        return self._current_limit

    @property
    def current_backoff_seconds(self) -> float:
        return self._backoff_seconds

    async def acquire(self) -> None:
        async with self._condition:
            await self._condition.wait_for(lambda: self._active < self._current_limit)
            self._active += 1

    async def release(self) -> None:
        async with self._condition:
            self._active = max(0, self._active - 1)
            self._condition.notify_all()

    async def record_rate_limited(self) -> None:
        async with self._condition:
            self._current_limit = max(self._min_limit, self._current_limit // 2)
            self._stable_count = 0
            self._backoff_seconds = min(
                self._max_backoff_seconds,
                max(self._initial_backoff_seconds, self._backoff_seconds * 2),
            )
            self._condition.notify_all()

    async def record_success(self, latency_ms: float) -> None:
        async with self._condition:
            if latency_ms > self._latency_threshold_ms:
                self._current_limit = max(self._min_limit, self._current_limit // 2)
                self._stable_count = 0
                self._condition.notify_all()
                return

            self._stable_count += 1
            self._backoff_seconds = self._initial_backoff_seconds
            if self._stable_count >= self._stable_successes:
                self._current_limit = min(self._max_limit, self._current_limit + 1)
                self._stable_count = 0
                self._condition.notify_all()


class StagedPipeline:
    def __init__(
        self,
        *,
        config: PipelineConfig,
        read: StageFunc,
        parse: StageFunc,
        model: StageFunc,
        submit: StageFunc,
        model_controller: AimdConcurrencyController | None = None,
        sleep: SleepFunc | None = None,
        clock: Callable[[], float] | None = None,
    ) -> None:
        self._config = config
        self._read = read
        self._parse = parse
        self._model = model
        self._submit = submit
        self._model_controller = model_controller or AimdConcurrencyController(
            initial_limit=config.model.workers,
            max_limit=config.model.workers,
        )
        self._sleep = sleep or asyncio.sleep
        self._clock = clock or time.perf_counter

    @property
    def model_controller(self) -> AimdConcurrencyController:
        return self._model_controller

    async def run(self, items: Iterable[Any]) -> list[Any]:
        input_items = list(items)
        read_queue: asyncio.Queue[WorkItem | None] = asyncio.Queue(
            maxsize=self._config.read.queue_size
        )
        parse_queue: asyncio.Queue[WorkItem | None] = asyncio.Queue(
            maxsize=self._config.parse.queue_size
        )
        model_queue: asyncio.Queue[WorkItem | None] = asyncio.Queue(
            maxsize=self._config.model.queue_size
        )
        submit_queue: asyncio.Queue[WorkItem | None] = asyncio.Queue(
            maxsize=self._config.submit.queue_size
        )
        results: list[Any] = [None] * len(input_items)

        tasks = [
            asyncio.create_task(
                self._produce(input_items, read_queue, self._config.read.workers),
                name="staged-pipeline-producer",
            ),
            asyncio.create_task(self._run_stage(
                "read",
                read_queue,
                parse_queue,
                self._read,
                self._config.read.workers,
                self._config.parse.workers,
                model_stage=False,
                results=None,
            ), name="staged-pipeline-stage-read"),
            asyncio.create_task(self._run_stage(
                "parse",
                parse_queue,
                model_queue,
                self._parse,
                self._config.parse.workers,
                self._config.model.workers,
                model_stage=False,
                results=None,
            ), name="staged-pipeline-stage-parse"),
            asyncio.create_task(self._run_stage(
                "model",
                model_queue,
                submit_queue,
                self._model,
                self._config.model.workers,
                self._config.submit.workers,
                model_stage=True,
                results=None,
            ), name="staged-pipeline-stage-model"),
            asyncio.create_task(self._run_stage(
                "submit",
                submit_queue,
                None,
                self._submit,
                self._config.submit.workers,
                0,
                model_stage=False,
                results=results,
            ), name="staged-pipeline-stage-submit"),
        ]

        try:
            await asyncio.gather(*tasks)
        except BaseException:
            await self._cancel_tasks(tasks)
            raise
        return results

    async def _produce(
        self,
        input_items: list[Any],
        read_queue: asyncio.Queue[WorkItem | None],
        read_workers: int,
    ) -> None:
        for index, item in enumerate(input_items):
            await read_queue.put(WorkItem(index, item))
        for _ in range(read_workers):
            await read_queue.put(None)

    async def _cancel_tasks(self, tasks: list[asyncio.Task[Any]]) -> None:
        for task in tasks:
            if not task.done():
                task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)

    async def _run_stage(
        self,
        stage_name: str,
        in_queue: asyncio.Queue[WorkItem | None],
        out_queue: asyncio.Queue[WorkItem | None] | None,
        func: StageFunc,
        workers: int,
        downstream_workers: int,
        *,
        model_stage: bool,
        results: list[Any] | None,
    ) -> None:
        worker_tasks = [
            asyncio.create_task(
                self._worker(in_queue, out_queue, func, model_stage, results),
                name=f"staged-pipeline-worker-{stage_name}-{index}",
            )
            for index in range(workers)
        ]
        try:
            await asyncio.gather(*worker_tasks)
        except BaseException:
            await self._cancel_tasks(worker_tasks)
            raise
        if out_queue is not None:
            for _ in range(downstream_workers):
                await out_queue.put(None)

    async def _worker(
        self,
        in_queue: asyncio.Queue[WorkItem | None],
        out_queue: asyncio.Queue[WorkItem | None] | None,
        func: StageFunc,
        model_stage: bool,
        results: list[Any] | None,
    ) -> None:
        while True:
            item = await in_queue.get()
            try:
                if item is None:
                    return
                value = await self._call_model_with_aimd(func, item.value) if model_stage else await func(item.value)
                if out_queue is None:
                    if results is not None:
                        results[item.index] = value
                else:
                    await out_queue.put(WorkItem(item.index, value))
            finally:
                in_queue.task_done()

    async def _call_model_with_aimd(self, func: StageFunc, value: Any) -> Any:
        while True:
            await self._model_controller.acquire()
            started = self._clock()
            try:
                result = await func(value)
            except ModelRateLimitError:
                await self._model_controller.record_rate_limited()
                await self._model_controller.release()
                await self._sleep(self._model_controller.current_backoff_seconds)
                continue
            except Exception:
                await self._model_controller.release()
                raise
            latency_ms = (self._clock() - started) * 1000
            await self._model_controller.record_success(latency_ms)
            await self._model_controller.release()
            return result
