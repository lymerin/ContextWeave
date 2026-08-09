#!/usr/bin/env python
from __future__ import annotations

import argparse
import json
import os
import statistics
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[2]
OUT_DIR = ROOT / "tools" / "perf"
TERMINAL_STATUSES = {"COMPLETED", "PARTIALLY_COMPLETED", "FAILED", "CANCELLED"}


@dataclass(frozen=True)
class Scenario:
    name: str
    repo_url: str
    branch: str
    scope: dict[str, str]
    expected_files: str


class ApiClient:
    def __init__(self, base_url: str, *, agent_prefix: str) -> None:
        self._base_url = base_url.rstrip("/")
        self._agent_prefix = "/" + agent_prefix.strip("/")
        self._token: str | None = None
        self._credentials: tuple[str, str] | None = None

    def set_credentials(self, username: str, password: str) -> None:
        self._credentials = (username, password)

    def set_token(self, token: str) -> None:
        self._token = token

    def core(self, path: str, *, method: str = "GET", token: str | None = None,
             body: dict[str, Any] | None = None) -> Any:
        return self._request(path, method=method, token=token or self._token, body=body)

    def agent(self, path: str, *, method: str = "GET", token: str | None = None,
              body: dict[str, Any] | None = None) -> Any:
        return self._request(f"{self._agent_prefix}{path}", method=method, token=token or self._token, body=body)

    def _login(self) -> None:
        """access token 过期后重新登录换发（TTL 30 分钟，实验轮次可能跨期）。"""
        if not self._credentials:
            raise RuntimeError("API credentials not set; cannot refresh token")
        username, password = self._credentials
        data = json.dumps({"username": username, "password": password}).encode("utf-8")
        request = urllib.request.Request(
            f"{self._base_url}/api/v1/auth/login",
            data=data,
            method="POST",
            headers={"Content-Type": "application/json"},
        )
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                payload = json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            message = exc.read().decode("utf-8", errors="replace")[:300]
            raise RuntimeError(f"login refresh failed: {exc.code} {message}") from exc
        except urllib.error.URLError as exc:
            raise RuntimeError(f"login refresh failed: {exc.reason}") from exc
        self._token = str(payload["accessToken"])
        print(json.dumps({"event": "token_refreshed"}, ensure_ascii=False), file=sys.stderr, flush=True)

    def _request(self, path: str, *, method: str, token: str | None,
                 body: dict[str, Any] | None, _refreshed: bool = False) -> Any:
        data = json.dumps(body).encode("utf-8") if body is not None else None
        request = urllib.request.Request(
            f"{self._base_url}{path}",
            data=data,
            method=method,
            headers={
                **({"Authorization": f"Bearer {token}"} if token else {}),
                **({"Content-Type": "application/json"} if data else {}),
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                raw = response.read()
                if response.status == 204 or not raw:
                    return None
                return json.loads(raw.decode("utf-8"))
        except urllib.error.HTTPError as exc:
            message = exc.read().decode("utf-8", errors="replace")[:300]
            # token 过期（401）或授权失败（403）且可换发时，刷新后重试一次
            if not _refreshed and token and exc.code in {401, 403}:
                self._login()
                return self._request(
                    path, method=method, token=self._token, body=body, _refreshed=True
                )
            raise RuntimeError(f"{method} {path} failed: {exc.code} {message}") from exc
        except urllib.error.URLError as exc:
            raise RuntimeError(f"{method} {path} failed: {exc.reason}") from exc


class RssSampler:
    def __init__(self, *, interval_seconds: float, pids: list[int], containers: list[str]) -> None:
        self._interval_seconds = interval_seconds
        self._pids = pids
        self._containers = containers
        self._stop = threading.Event()
        self._peak = 0
        self._thread = threading.Thread(target=self._run, name="perf-rss-sampler", daemon=True)

    def __enter__(self) -> RssSampler:
        self._thread.start()
        return self

    def __exit__(self, *_args: object) -> None:
        self._stop.set()
        self._thread.join(timeout=self._interval_seconds * 2)

    @property
    def peak_bytes(self) -> int:
        return self._peak

    def _run(self) -> None:
        while not self._stop.is_set():
            self._peak = max(self._peak, _rss_bytes(self._pids, self._containers))
            self._stop.wait(self._interval_seconds)


def main() -> int:
    args = parse_args()
    scenarios = select_scenarios(load_scenarios(args), args.scenarios)
    if not scenarios:
        raise RuntimeError("No scenarios selected")

    client = ApiClient(args.base_url, agent_prefix=args.agent_prefix)
    code_commit = _git(["rev-parse", "--short", "perf-baseline"]) or "perf-baseline"
    branch = _git(["branch", "--show-current"]) or ""

    run_started_at = datetime.now(timezone.utc).isoformat()
    for scenario in scenarios:
        records: list[dict[str, Any]] = []
        scenario_meta: dict[str, Any]
        scenario_meta = {
            "schema_version": 1,
            "scenario": scenario.name,
            "expected_files": scenario.expected_files,
            "repo_url": scenario.repo_url,
            "branch": scenario.branch,
            "scope": scenario.scope,
            "code_commit": code_commit,
            "agent_branch": branch,
            "base_url": args.base_url,
            "warmup_rounds": args.warmup,
            "requested_rounds": args.rounds,
            "started_at": run_started_at,
            "stop_reason": None,
        }
        measured_completed = 0
        try:
            _token, workspace_id, repository = prepare_repository(client, scenario)
            file_count, first_path = list_files(client, workspace_id, repository["id"])
            scope = scenario.scope
            if scope["type"] == "CURRENT_FILE" and scope.get("filePath") == "__FIRST_FILE__":
                scope = {"type": "CURRENT_FILE", "filePath": first_path}
            scenario_meta["workspace_id"] = workspace_id
            scenario_meta["repository_id"] = repository["id"]
            scenario_meta["target_revision"] = repository.get("lastSyncedCommit")
            scenario_meta["actual_file_count"] = file_count
            scenario_meta["actual_scope"] = scope

            total_rounds = args.warmup + args.rounds
            for index in range(total_rounds):
                phase = "warmup" if index < args.warmup else "measure"
                round_number = index + 1 if phase == "warmup" else index + 1 - args.warmup
                try:
                    record = run_round(
                        client,
                        workspace_id,
                        repository["id"],
                        scope,
                        scenario=scenario.name,
                        phase=phase,
                        round_number=round_number,
                        timeout_seconds=args.timeout_seconds,
                        rss_interval_seconds=args.rss_interval_seconds,
                        pids=args.rss_pids,
                        containers=args.rss_containers,
                        code_commit=code_commit,
                    )
                except Exception as exc:
                    record = error_record(
                        scenario=scenario.name,
                        phase=phase,
                        round_number=round_number,
                        code_commit=code_commit,
                        error=str(exc),
                    )
                    records.append(record)
                    print(
                        json.dumps(record, ensure_ascii=False, separators=(",", ":")),
                        flush=True,
                    )
                    scenario_meta["stop_reason"] = str(exc)[:300]
                    break
                records.append(record)
                print(json.dumps(record, ensure_ascii=False, separators=(",", ":")), flush=True)
                if phase == "measure":
                    measured_completed += 1
                if record["total_duration_s"] > args.max_round_seconds:
                    scenario_meta["stop_reason"] = (
                        f"round exceeded {args.max_round_seconds}s"
                    )
                    break
        except Exception as exc:
            scenario_meta["stop_reason"] = str(exc)[:300]

        if (
            measured_completed < args.rounds
            and records
            and scenario_meta["stop_reason"] is None
        ):
            scenario_meta["stop_reason"] = f"only {measured_completed} measured rounds completed"
        write_scenario_file(scenario.name, scenario_meta, records)
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run DevCollab agent baseline rounds")
    parser.add_argument("--base-url", default=os.getenv("DEVCOLLAB_BASE_URL", "http://localhost:8088"))
    parser.add_argument("--agent-prefix", default=os.getenv("DEVCOLLAB_AGENT_PREFIX", "/agent-api"))
    parser.add_argument("--scenarios", default=os.getenv("DEVCOLLAB_PERF_SCENARIOS", "small,hundred,stress,incremental"))
    parser.add_argument("--warmup", type=int, default=int(os.getenv("DEVCOLLAB_PERF_WARMUP", "5")))
    parser.add_argument("--rounds", type=int, default=int(os.getenv("DEVCOLLAB_PERF_ROUNDS", "20")))
    parser.add_argument("--timeout-seconds", type=float, default=float(os.getenv("DEVCOLLAB_PERF_TIMEOUT_SECONDS", "2400")))
    parser.add_argument("--max-round-seconds", type=float, default=float(os.getenv("DEVCOLLAB_PERF_MAX_ROUND_SECONDS", "600")))
    parser.add_argument("--rss-interval-seconds", type=float, default=float(os.getenv("DEVCOLLAB_PERF_RSS_INTERVAL_SECONDS", "1")))
    parser.add_argument("--rss-pids", type=_csv_ints, default=_csv_ints(os.getenv("DEVCOLLAB_PERF_RSS_PIDS", "")))
    parser.add_argument("--rss-containers", type=_csv, default=_csv(os.getenv("DEVCOLLAB_PERF_RSS_CONTAINERS", "devcollab-agent-service,devcollab-agent-worker")))
    return parser.parse_args()


def load_scenarios(args: argparse.Namespace) -> dict[str, Scenario]:
    fallback_repo = os.getenv(
        "DEVCOLLAB_E2E_REPOSITORY_URL",
        "https://github.com/XMU-Linjer/devcolab.git",
    )
    fallback_branch = os.getenv("DEVCOLLAB_E2E_REPOSITORY_BRANCH", "main")

    def repo(name: str) -> str:
        return os.getenv(f"DEVCOLLAB_PERF_{name.upper()}_REPO_URL", fallback_repo)

    def branch(name: str) -> str:
        return os.getenv(f"DEVCOLLAB_PERF_{name.upper()}_REPO_BRANCH", fallback_branch)

    incremental_path = os.getenv("DEVCOLLAB_PERF_INCREMENTAL_FILE_PATH", "__FIRST_FILE__")
    return {
        "small": Scenario("small", repo("small"), branch("small"),
                          {"type": "PROJECT_INITIALIZATION"}, "<=20"),
        "hundred": Scenario("hundred", repo("hundred"), branch("hundred"),
                            {"type": "PROJECT_INITIALIZATION"}, "100"),
        "stress": Scenario("stress", repo("stress"), branch("stress"),
                           {"type": "PROJECT_INITIALIZATION"}, "~479"),
        "incremental": Scenario(
            "incremental",
            repo("incremental"),
            branch("incremental"),
            {"type": "CURRENT_FILE", "filePath": incremental_path},
            "1% changed files",
        ),
    }


def select_scenarios(scenarios: dict[str, Scenario], selected: str) -> list[Scenario]:
    names = _csv(selected)
    unknown = [name for name in names if name not in scenarios]
    if unknown:
        raise RuntimeError(f"Unknown scenario(s): {', '.join(unknown)}")
    return [scenarios[name] for name in names]


def prepare_repository(client: ApiClient, scenario: Scenario) -> tuple[str, str, dict[str, Any]]:
    suffix = f"{scenario.name[:4]}-{int(time.time())}-{uuid4().hex[:4]}"
    username = f"perf_{scenario.name[:4]}_{uuid4().hex[:10]}"
    password = f"Perf-{uuid4().hex}!Aa1"
    auth = client.core(
        "/api/v1/auth/register",
        method="POST",
        body={
            "username": username,
            "displayName": f"Perf {scenario.name}",
            "password": password,
        },
    )
    token = str(auth["accessToken"])
    client.set_credentials(username, password)
    client.set_token(token)
    workspace = client.core(
        "/api/v1/workspaces",
        method="POST",
        token=token,
        body={"name": f"Perf {scenario.name} {suffix}"},
    )
    workspace_id = str(workspace["id"])
    repository = client.core(
        f"/api/v1/workspaces/{workspace_id}/git/repositories",
        method="POST",
        token=token,
        body={
            "name": f"Perf {scenario.name}",
            "provider": "GITHUB",
            "remoteUrl": scenario.repo_url,
            "defaultBranch": scenario.branch,
        },
    )
    ready = wait_for(
        lambda: _ready_repository(client, workspace_id, str(repository["id"])),
        timeout_seconds=300,
        interval_seconds=2,
    )
    return token, workspace_id, ready


def _ready_repository(
    client: ApiClient, workspace_id: str, repository_id: str
) -> dict[str, Any] | None:
    repositories = client.core(
        f"/api/v1/workspaces/{workspace_id}/git/repositories",
    )
    for item in repositories:
        if str(item.get("id")) != repository_id:
            continue
        if item.get("syncStatus") == "FAILED":
            raise RuntimeError(f"Repository sync failed: {item.get('lastSyncError')}")
        if item.get("syncStatus") == "READY":
            return item
    return None


def list_files(
    client: ApiClient, workspace_id: str, repository_id: str
) -> tuple[int, str]:
    files = client.core(
        f"/api/v1/workspaces/{workspace_id}/git/repositories/{repository_id}/files",
    )
    if not isinstance(files, list) or not files:
        raise RuntimeError("Repository has no indexed files")
    paths = sorted(
        str(item.get("path") or item.get("filePath") or item.get("sourcePath") or "")
        for item in files
    )
    paths = [path for path in paths if path]
    if not paths:
        raise RuntimeError("Repository file response did not include paths")
    return len(paths), paths[0]


def run_round(
    client: ApiClient,
    workspace_id: str,
    repository_id: str,
    scope: dict[str, str],
    *,
    scenario: str,
    phase: str,
    round_number: int,
    timeout_seconds: float,
    rss_interval_seconds: float,
    pids: list[int],
    containers: list[str],
    code_commit: str,
) -> dict[str, Any]:
    client.agent("/api/v1/perf/metrics/reset", method="POST")
    started = time.perf_counter()
    with RssSampler(
        interval_seconds=rss_interval_seconds, pids=pids, containers=containers
    ) as sampler:
        queued = client.agent(
            "/api/v1/agent-jobs",
            method="POST",
            body={
                "workspaceId": workspace_id,
                "repositoryId": repository_id,
                "scope": scope,
                "userInstruction": "性能基线实验：按现有流程生成或更新工程文档。",
            },
        )
        job_id = str(queued["jobId"])
        job = wait_for(
            lambda: _terminal_job(client, job_id),
            timeout_seconds=timeout_seconds,
            interval_seconds=3,
        )
    metrics = client.agent(f"/api/v1/perf/metrics?jobId={job_id}")
    units = client.agent(f"/api/v1/agent-jobs/{job_id}/units?offset=0&limit=100")

    total_duration_s = time.perf_counter() - started
    queue_wait_s = _seconds_between(job.get("createdAt"), job.get("startedAt"))
    total_units = int(job.get("totalUnits") or units.get("total") or 0)
    throughput = total_units / total_duration_s if total_duration_s > 0 else 0.0
    output_bytes = len(json.dumps({"job": job, "units": units}, ensure_ascii=False).encode("utf-8"))
    return {
        "schema_version": 1,
        "scenario": scenario,
        "phase": phase,
        "round": round_number,
        "job_id": job_id,
        "status": job.get("status"),
        "result": job.get("result"),
        "code_commit": code_commit,
        "job_revision": job.get("revision"),
        "tool_calls": int(metrics["mcp"]["tool_calls"]),
        "session_init_count": int(metrics["mcp"]["session_initialize_count"]),
        "delegation_exchange_count": int(metrics["delegation"]["exchange_count"]),
        "mcp_p95_ms": float(metrics["mcp"]["duration_ms"]["p95"]),
        "unit_throughput_per_s": round(throughput, 6),
        "queue_wait_s": round(queue_wait_s, 3),
        "peak_rss_bytes": sampler.peak_bytes,
        "total_duration_s": round(total_duration_s, 3),
        "output_bytes": output_bytes,
        "rate_limit_429_count": int(metrics["retry"]["rate_limit_429_count"]),
        "retry_count": int(metrics["retry"]["retry_count"]),
        "retry_backoff_ms_total": int(metrics["retry"]["backoff_ms_total"]),
        "total_units": total_units,
        "completed_units": int(job.get("completedUnits") or 0),
        "failed_units": int(job.get("failedUnits") or 0),
        "metrics": metrics,
    }


def error_record(
    *,
    scenario: str,
    phase: str,
    round_number: int,
    code_commit: str,
    error: str,
) -> dict[str, Any]:
    return {
        "schema_version": 1,
        "scenario": scenario,
        "phase": phase,
        "round": round_number,
        "status": "ERROR",
        "result": None,
        "code_commit": code_commit,
        "job_revision": None,
        "tool_calls": 0,
        "session_init_count": 0,
        "delegation_exchange_count": 0,
        "mcp_p95_ms": 0.0,
        "unit_throughput_per_s": 0.0,
        "queue_wait_s": 0.0,
        "peak_rss_bytes": 0,
        "total_duration_s": 0.0,
        "output_bytes": 0,
        "rate_limit_429_count": 0,
        "retry_count": 0,
        "retry_backoff_ms_total": 0,
        "total_units": 0,
        "completed_units": 0,
        "failed_units": 0,
        "error": error[:300],
    }


def _terminal_job(client: ApiClient, job_id: str) -> dict[str, Any] | None:
    job = client.agent(f"/api/v1/agent-jobs/{job_id}")
    return job if job.get("status") in TERMINAL_STATUSES else None


def wait_for(probe: Any, *, timeout_seconds: float, interval_seconds: float) -> Any:
    deadline = time.monotonic() + timeout_seconds
    last_error: Exception | None = None
    while time.monotonic() < deadline:
        try:
            value = probe()
            if value is not None:
                return value
        except Exception as exc:
            last_error = exc
            raise
        time.sleep(interval_seconds)
    if last_error is not None:
        raise last_error
    raise RuntimeError("Timed out waiting for condition")


def write_scenario_file(
    name: str, scenario_meta: dict[str, Any], records: list[dict[str, Any]]
) -> None:
    measured = [record for record in records if record["phase"] == "measure"]
    payload = {
        **scenario_meta,
        "completed_at": datetime.now(timezone.utc).isoformat(),
        "records": records,
        "summary": summarize_records(measured),
    }
    output = OUT_DIR / f"baseline-{name}.json"
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def summarize_records(records: list[dict[str, Any]]) -> dict[str, Any]:
    metrics = [
        "tool_calls",
        "session_init_count",
        "delegation_exchange_count",
        "mcp_p95_ms",
        "unit_throughput_per_s",
        "queue_wait_s",
        "peak_rss_bytes",
        "total_duration_s",
        "output_bytes",
        "rate_limit_429_count",
        "retry_count",
        "retry_backoff_ms_total",
    ]
    return {metric: _series_summary([float(record[metric]) for record in records]) for metric in metrics}


def _series_summary(values: list[float]) -> dict[str, float | int]:
    if not values:
        return {"sample_count": 0, "p50": 0.0, "p95": 0.0, "mean": 0.0}
    ordered = sorted(values)
    return {
        "sample_count": len(values),
        "p50": round(statistics.median(ordered), 3),
        "p95": _percentile(ordered, 0.95),
        "mean": round(statistics.fmean(ordered), 3),
    }


def _percentile(ordered: list[float], quantile: float) -> float:
    if len(ordered) == 1:
        return round(ordered[0], 3)
    index = (len(ordered) - 1) * quantile
    lower = int(index)
    upper = min(lower + 1, len(ordered) - 1)
    weight = index - lower
    return round(ordered[lower] * (1 - weight) + ordered[upper] * weight, 3)


def _seconds_between(start: str | None, end: str | None) -> float:
    if not start or not end:
        return 0.0
    try:
        start_dt = datetime.fromisoformat(start.replace("Z", "+00:00"))
        end_dt = datetime.fromisoformat(end.replace("Z", "+00:00"))
    except ValueError:
        return 0.0
    return max(0.0, (end_dt - start_dt).total_seconds())


def _rss_bytes(pids: list[int], containers: list[str]) -> int:
    return _rss_from_pids(pids) + _rss_from_containers(containers)


def _rss_from_pids(pids: list[int]) -> int:
    if not pids:
        return 0
    try:
        import psutil  # type: ignore[import-not-found]
    except ImportError:
        return 0
    total = 0
    for pid in pids:
        try:
            total += int(psutil.Process(pid).memory_info().rss)
        except psutil.Error:
            continue
    return total


def _rss_from_containers(containers: list[str]) -> int:
    if not containers:
        return 0
    try:
        result = subprocess.run(
            ["docker", "stats", "--no-stream", "--format", "{{.MemUsage}}", *containers],
            cwd=ROOT,
            capture_output=True,
            text=True,
            timeout=10,
            check=False,
        )
    except (OSError, subprocess.SubprocessError):
        return 0
    if result.returncode != 0:
        return 0
    return sum(_parse_mem_usage(line) for line in result.stdout.splitlines())


def _parse_mem_usage(value: str) -> int:
    used = value.split("/", 1)[0].strip()
    units = {
        "KiB": 1024,
        "MiB": 1024 ** 2,
        "GiB": 1024 ** 3,
        "KB": 1000,
        "MB": 1000 ** 2,
        "GB": 1000 ** 3,
        "B": 1,
    }
    for suffix, multiplier in units.items():
        if used.endswith(suffix):
            number = used[: -len(suffix)].strip()
            return int(float(number) * multiplier)
    return 0


def _csv(value: str) -> list[str]:
    return [item.strip() for item in value.split(",") if item.strip()]


def _csv_ints(value: str) -> list[int]:
    return [int(item) for item in _csv(value)]


def _git(args: list[str]) -> str:
    try:
        result = subprocess.run(
            ["git", *args],
            cwd=ROOT,
            capture_output=True,
            text=True,
            timeout=10,
            check=False,
        )
    except (OSError, subprocess.SubprocessError):
        return ""
    return result.stdout.strip() if result.returncode == 0 else ""


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(json.dumps({"error": str(exc)}, ensure_ascii=False), file=sys.stderr)
        raise SystemExit(1)
