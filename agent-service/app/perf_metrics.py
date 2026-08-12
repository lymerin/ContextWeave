from __future__ import annotations

import json
import os
import time
from collections import Counter, deque
from collections.abc import Iterator
from contextlib import contextmanager
from contextvars import ContextVar, Token
from pathlib import Path
from threading import Lock
from typing import Any

_MAX_SAMPLES = 20_000
_CONTEXT: ContextVar[dict[str, str] | None] = ContextVar(
    "perf_metrics_context", default=None
)


class PerfMetrics:
    def __init__(self) -> None:
        self._lock = Lock()
        self._started_at = _now()
        self._counters: Counter[str] = Counter()
        self._mcp_tool_counts: Counter[str] = Counter()
        self._durations_ms: dict[str, deque[float]] = {}

    def reset(self) -> None:
        with self._lock:
            self._started_at = _now()
            self._counters.clear()
            self._mcp_tool_counts.clear()
            self._durations_ms.clear()
            _truncate_events()

    def increment(self, name: str, amount: int = 1) -> None:
        with self._lock:
            self._counters[name] += amount
            _append_event({"kind": "counter", "name": name, "amount": amount})

    def add_duration(self, name: str, elapsed_ms: float) -> None:
        with self._lock:
            samples = self._durations_ms.setdefault(name, deque(maxlen=_MAX_SAMPLES))
            samples.append(elapsed_ms)
            _append_event({"kind": "duration", "name": name, "elapsed_ms": elapsed_ms})

    def add_backoff(self, seconds: float) -> None:
        with self._lock:
            self._counters["retry_backoff_ms_total"] += int(seconds * 1000)
            _append_event({"kind": "backoff", "elapsed_ms": int(seconds * 1000)})

    def record_mcp_tool(self, name: str, elapsed_ms: float) -> None:
        with self._lock:
            self._counters["mcp_tool_calls"] += 1
            self._mcp_tool_counts[name] += 1
            self._durations_ms.setdefault("mcp_tool", deque(maxlen=_MAX_SAMPLES)).append(
                elapsed_ms
            )
            self._durations_ms.setdefault(
                f"mcp_tool:{name}", deque(maxlen=_MAX_SAMPLES)
            ).append(elapsed_ms)
            _append_event({"kind": "mcp_tool", "name": name, "elapsed_ms": elapsed_ms})

    def record_model_usage(self, usage: dict[str, Any]) -> None:
        fields = {
            "model_prompt_tokens": usage.get("prompt_tokens"),
            "model_completion_tokens": usage.get("completion_tokens"),
            "model_total_tokens": usage.get("total_tokens"),
            "model_prompt_cache_hit_tokens": usage.get("prompt_cache_hit_tokens"),
            "model_prompt_cache_miss_tokens": usage.get("prompt_cache_miss_tokens"),
        }
        normalized = {
            name: int(value)
            for name, value in fields.items()
            if isinstance(value, int | float) and value >= 0
        }
        if not normalized:
            return
        with self._lock:
            for name, value in normalized.items():
                self._counters[name] += value
            self._counters["model_call_usage_count"] += 1
            _append_event({"kind": "model_usage", **normalized})

    def snapshot(self, *, job_id: str | None = None) -> dict[str, Any]:
        file_snapshot = _snapshot_events(job_id=job_id)
        if file_snapshot is not None:
            return file_snapshot
        with self._lock:
            counters = dict(self._counters)
            duration_samples = {
                name: list(samples) for name, samples in self._durations_ms.items()
            }
            mcp_tool_counts = dict(self._mcp_tool_counts)
            started_at = self._started_at

        return _build_snapshot(counters, mcp_tool_counts, duration_samples, started_at)


def snapshot(*, job_id: str | None = None) -> dict[str, Any]:
    return _METRICS.snapshot(job_id=job_id)


def reset() -> None:
    _METRICS.reset()


def increment(name: str, amount: int = 1) -> None:
    _METRICS.increment(name, amount)


def add_backoff(seconds: float) -> None:
    _METRICS.add_backoff(seconds)


def record_mcp_tool(name: str, elapsed_ms: float) -> None:
    _METRICS.record_mcp_tool(name, elapsed_ms)


def record_model_usage(usage: dict[str, Any]) -> None:
    _METRICS.record_model_usage(usage)


def set_context(*, job_id: str, unit_id: str) -> Token[dict[str, str] | None]:
    return _CONTEXT.set({"job_id": job_id, "unit_id": unit_id})


def reset_context(token: Token[dict[str, str] | None]) -> None:
    _CONTEXT.reset(token)


@contextmanager
def stage(name: str) -> Iterator[None]:
    started = time.perf_counter()
    try:
        yield
    finally:
        _METRICS.add_duration(f"stage:{name}", _elapsed_ms(started))


def _summary(samples: list[float]) -> dict[str, float | int]:
    if not samples:
        return {"count": 0, "p50": 0.0, "p95": 0.0, "p99": 0.0, "mean": 0.0}
    ordered = sorted(samples)
    return {
        "count": len(ordered),
        "p50": _percentile(ordered, 0.50),
        "p95": _percentile(ordered, 0.95),
        "p99": _percentile(ordered, 0.99),
        "mean": round(sum(ordered) / len(ordered), 3),
    }


def _percentile(ordered: list[float], quantile: float) -> float:
    if len(ordered) == 1:
        return round(ordered[0], 3)
    index = (len(ordered) - 1) * quantile
    lower = int(index)
    upper = min(lower + 1, len(ordered) - 1)
    weight = index - lower
    value = ordered[lower] * (1 - weight) + ordered[upper] * weight
    return round(value, 3)


def _elapsed_ms(started: float) -> float:
    return (time.perf_counter() - started) * 1000


def _now() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def _metrics_path() -> Path:
    return Path(
        os.getenv(
            "DEVCOLLAB_PERF_METRICS_PATH",
            ".data/perf/agent-metrics.jsonl",
        )
    )


def _append_event(event: dict[str, Any]) -> None:
    path = _metrics_path()
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        payload = {"ts": _now(), **(_CONTEXT.get() or {}), **event}
        with path.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(payload, ensure_ascii=False, separators=(",", ":")))
            handle.write("\n")
    except OSError:
        return


def _truncate_events() -> None:
    path = _metrics_path()
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("", encoding="utf-8")
    except OSError:
        return


def _snapshot_events(*, job_id: str | None) -> dict[str, Any] | None:
    path = _metrics_path()
    if not path.exists():
        return None
    counters: Counter[str] = Counter()
    mcp_tool_counts: Counter[str] = Counter()
    duration_samples: dict[str, list[float]] = {}
    try:
        for line in path.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            try:
                event = json.loads(line)
            except json.JSONDecodeError:
                continue
            if job_id is not None and event.get("job_id") != job_id:
                continue
            kind = event.get("kind")
            if kind == "counter":
                counters[str(event.get("name", ""))] += int(event.get("amount") or 0)
            elif kind == "duration":
                name = str(event.get("name", ""))
                duration_samples.setdefault(name, []).append(
                    float(event.get("elapsed_ms") or 0)
                )
            elif kind == "backoff":
                counters["retry_backoff_ms_total"] += int(event.get("elapsed_ms") or 0)
            elif kind == "mcp_tool":
                name = str(event.get("name", ""))
                elapsed_ms = float(event.get("elapsed_ms") or 0)
                counters["mcp_tool_calls"] += 1
                mcp_tool_counts[name] += 1
                duration_samples.setdefault("mcp_tool", []).append(elapsed_ms)
                duration_samples.setdefault(f"mcp_tool:{name}", []).append(elapsed_ms)
            elif kind == "model_usage":
                counters["model_call_usage_count"] += 1
                for name in (
                    "model_prompt_tokens",
                    "model_completion_tokens",
                    "model_total_tokens",
                    "model_prompt_cache_hit_tokens",
                    "model_prompt_cache_miss_tokens",
                ):
                    counters[name] += int(event.get(name) or 0)
    except OSError:
        return None
    return _build_snapshot(dict(counters), dict(mcp_tool_counts), duration_samples, _now())


def _build_snapshot(
    counters: dict[str, int],
    mcp_tool_counts: dict[str, int],
    duration_samples: dict[str, list[float]],
    started_at: str,
) -> dict[str, Any]:
    stages = {
        name.removeprefix("stage:"): _summary(samples)
        for name, samples in duration_samples.items()
        if name.startswith("stage:")
    }
    mcp_by_tool = {
        name: {
            "count": count,
            "duration_ms": _summary(duration_samples.get(f"mcp_tool:{name}", [])),
        }
        for name, count in sorted(mcp_tool_counts.items())
    }
    return {
        "schema_version": 1,
        "started_at": started_at,
        "generated_at": _now(),
        "counters": dict(counters),
        "mcp": {
            "tool_calls": counters.get("mcp_tool_calls", 0),
            "session_initialize_count": counters.get("session_initialize_count", 0),
            "duration_ms": _summary(duration_samples.get("mcp_tool", [])),
            "by_tool": mcp_by_tool,
        },
        "delegation": {
            "exchange_count": counters.get("delegation_exchange_count", 0),
        },
        "retry": {
            "rate_limit_429_count": counters.get("rate_limit_429_count", 0),
            "retry_count": counters.get("retry_count", 0),
            "backoff_ms_total": counters.get("retry_backoff_ms_total", 0),
        },
        "model": {
            "usage_count": counters.get("model_call_usage_count", 0),
            "prompt_tokens": counters.get("model_prompt_tokens", 0),
            "completion_tokens": counters.get("model_completion_tokens", 0),
            "total_tokens": counters.get("model_total_tokens", 0),
            "prompt_cache_hit_tokens": counters.get(
                "model_prompt_cache_hit_tokens", 0
            ),
            "prompt_cache_miss_tokens": counters.get(
                "model_prompt_cache_miss_tokens", 0
            ),
        },
        "stages": stages,
    }


_METRICS = PerfMetrics()
