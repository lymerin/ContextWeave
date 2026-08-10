#!/usr/bin/env python
from __future__ import annotations

import argparse
import importlib.util
import json
import os
import subprocess
import sys
import time
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
OUT_DIR = ROOT / "tools" / "perf"
BASELINE_SCRIPT = OUT_DIR / "run-baseline.py"

SWITCHES = (
    "AGENT_MCP_SESSION_POOL_ENABLED",
    "AGENT_DELEGATION_TOKEN_CACHE_ENABLED",
    "AGENT_BATCH_TOOLS_ENABLED",
    "AGENT_INCREMENTAL_READ_ENABLED",
    "AGENT_CODE_READ_BATCH_ENABLED",
    "AGENT_STAGED_PIPELINE_ENABLED",
    "DEVCOLLAB_CODE_METADATA_CACHE_ENABLED",
)

COUNTER_FIELDS = (
    "code_read_batch_calls",
    "code_read_batch_continuations",
    "metadata_cache_hit_count",
    "metadata_cache_miss_count",
    "code_metadata_cache_hit_count",
    "code_metadata_cache_miss_count",
    "incremental_read_skipped_files",
    "incremental_read_skip_count",
)


@dataclass(frozen=True)
class SwitchConfig:
    name: str
    environment: dict[str, str]


def main() -> int:
    args = parse_args()
    if args.rounds_per_config > 1:
        raise RuntimeError("--rounds-per-config is capped at 1 for smoke comparisons")

    baseline = load_baseline()
    configs = select_configs(args.switches)
    client = baseline.ApiClient(args.base_url, agent_prefix=args.agent_prefix)
    code_commit = baseline._git(["rev-parse", "--short", "HEAD"]) or "HEAD"
    branch = baseline._git(["branch", "--show-current"]) or ""
    started_at = datetime.now(UTC).isoformat()

    scenario = baseline.Scenario(
        "switch-comparison",
        args.repo_url,
        args.repo_branch,
        {"type": "PROJECT_INITIALIZATION"},
        "switch comparison",
    )

    workspace_id: str | None = None
    repository_id: str | None = None
    target_revision: str | None = None
    actual_file_count = 0
    stop_reason: str | None = None
    config_results: list[dict[str, Any]] = []

    try:
        _token, workspace_id, repository = baseline.prepare_repository(client, scenario)
        repository_id = str(repository["id"])
        target_revision = repository.get("lastSyncedCommit")
        actual_file_count, _first_path = baseline.list_files(client, workspace_id, repository_id)
        for config in configs:
            config_results.append(
                run_config(
                    baseline,
                    client,
                    config,
                    workspace_id,
                    repository_id,
                    scenario.scope,
                    code_commit=code_commit,
                    args=args,
                )
            )
    except Exception as exc:
        stop_reason = str(exc)[:300]

    output = {
        "schema_version": 1,
        "started_at": started_at,
        "completed_at": datetime.now(UTC).isoformat(),
        "code_commit": code_commit,
        "agent_branch": branch,
        "base_url": args.base_url,
        "agent_prefix": args.agent_prefix,
        "repo_url": args.repo_url,
        "repo_branch": args.repo_branch,
        "workspace_id": workspace_id,
        "repository_id": repository_id,
        "target_revision": target_revision,
        "actual_file_count": actual_file_count,
        "switch_mode": args.switches,
        "rounds_per_config": args.rounds_per_config,
        "switch_application": switch_application(args),
        "configs": config_results,
        "stop_reason": stop_reason,
    }
    output_path = OUT_DIR / f"switch-comparison-{timestamp()}.json"
    output_path.write_text(json.dumps(output, ensure_ascii=False, indent=2), encoding="utf-8")
    print(str(output_path))
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run smoke comparisons for default-off DevCollab perf switches"
    )
    parser.add_argument("--repo-url", required=True)
    parser.add_argument(
        "--repo-branch",
        default=os.getenv("DEVCOLLAB_E2E_REPOSITORY_BRANCH", "main"),
    )
    parser.add_argument(
        "--switches",
        choices=("off", "single", "all"),
        default="all",
        help="off=only all-off; single=all-off plus one switch at a time; all=full matrix",
    )
    parser.add_argument("--rounds-per-config", type=int, default=1)
    parser.add_argument(
        "--base-url",
        default=os.getenv("DEVCOLLAB_BASE_URL", "http://localhost:8088"),
    )
    parser.add_argument(
        "--agent-prefix",
        default=os.getenv("DEVCOLLAB_AGENT_PREFIX", "/agent-api"),
    )
    parser.add_argument(
        "--restart-command",
        default=os.getenv("DEVCOLLAB_SWITCH_RESTART_COMMAND", ""),
        help="Optional command run after each config environment is applied.",
    )
    parser.add_argument(
        "--restart-settle-seconds",
        type=float,
        default=float(os.getenv("DEVCOLLAB_SWITCH_RESTART_SETTLE_SECONDS", "0")),
    )
    parser.add_argument(
        "--timeout-seconds",
        type=float,
        default=float(os.getenv("DEVCOLLAB_PERF_TIMEOUT_SECONDS", "2400")),
    )
    parser.add_argument(
        "--rss-interval-seconds",
        type=float,
        default=float(os.getenv("DEVCOLLAB_PERF_RSS_INTERVAL_SECONDS", "1")),
    )
    parser.add_argument(
        "--rss-pids",
        type=csv_ints,
        default=csv_ints(os.getenv("DEVCOLLAB_PERF_RSS_PIDS", "")),
    )
    parser.add_argument(
        "--rss-containers",
        type=csv,
        default=csv(
            os.getenv(
                "DEVCOLLAB_PERF_RSS_CONTAINERS",
                "devcollab-agent-service,devcollab-agent-worker",
            )
        ),
    )
    return parser.parse_args()


def load_baseline() -> Any:
    spec = importlib.util.spec_from_file_location("devcollab_perf_baseline", BASELINE_SCRIPT)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Cannot load {BASELINE_SCRIPT}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def select_configs(mode: str) -> list[SwitchConfig]:
    off_env = {switch: "false" for switch in SWITCHES}
    configs = [SwitchConfig("off", off_env)]
    if mode in {"single", "all"}:
        for switch in SWITCHES:
            env = dict(off_env)
            env[switch] = "true"
            configs.append(SwitchConfig(f"single:{switch}", env))
    if mode == "all":
        configs.append(SwitchConfig("all", {switch: "true" for switch in SWITCHES}))
    return configs


def run_config(
    baseline: Any,
    client: Any,
    config: SwitchConfig,
    workspace_id: str,
    repository_id: str,
    scope: dict[str, str],
    *,
    code_commit: str,
    args: argparse.Namespace,
) -> dict[str, Any]:
    apply_config(config, args)
    records: list[dict[str, Any]] = []
    error: str | None = None
    for round_number in range(1, args.rounds_per_config + 1):
        try:
            record = baseline.run_round(
                client,
                workspace_id,
                repository_id,
                scope,
                scenario=config.name,
                phase="measure",
                round_number=round_number,
                timeout_seconds=args.timeout_seconds,
                rss_interval_seconds=args.rss_interval_seconds,
                pids=args.rss_pids,
                containers=args.rss_containers,
                code_commit=code_commit,
            )
        except Exception as exc:
            error = str(exc)[:300]
            record = baseline.error_record(
                scenario=config.name,
                phase="measure",
                round_number=round_number,
                code_commit=code_commit,
                error=error,
            )
        records.append(record)
        print(json.dumps(config_record_log(config, record), ensure_ascii=False), flush=True)
        if error:
            break

    return {
        "name": config.name,
        "environment": config.environment,
        "records": records,
        "metric_summary": metric_summary(baseline, records),
        "job_result_summary": [job_result_summary(record) for record in records],
        "error": error,
    }


def apply_config(config: SwitchConfig, args: argparse.Namespace) -> None:
    os.environ.update(config.environment)
    if args.restart_command:
        completed = subprocess.run(
            args.restart_command,
            cwd=ROOT,
            env=os.environ.copy(),
            shell=True,
            check=False,
        )
        if completed.returncode != 0:
            raise RuntimeError(f"restart command failed for {config.name}: {completed.returncode}")
    elif len(select_configs(args.switches)) > 1:
        print(
            json.dumps(
                {
                    "event": "switch_environment_recorded",
                    "config": config.name,
                    "note": (
                        "No restart command was provided; running services must already "
                        "match the listed environment for this config to be effective."
                    ),
                },
                ensure_ascii=False,
            ),
            file=sys.stderr,
            flush=True,
        )
    if args.restart_settle_seconds > 0:
        time.sleep(args.restart_settle_seconds)


def metric_summary(baseline: Any, records: list[dict[str, Any]]) -> dict[str, Any]:
    measured = [record for record in records if record.get("status") != "ERROR"]
    summary = baseline.summarize_records(measured)
    summary["counters"] = {
        field: baseline._series_summary(
            [
                float((record.get("metrics") or {}).get("counters", {}).get(field, 0))
                for record in measured
            ]
        )
        for field in COUNTER_FIELDS
    }
    summary["mcp_by_tool"] = {
        tool: baseline._series_summary(values) for tool, values in mcp_tool_counts(measured).items()
    }
    return summary


def mcp_tool_counts(records: list[dict[str, Any]]) -> dict[str, list[float]]:
    values: dict[str, list[float]] = {}
    for record in records:
        by_tool = ((record.get("metrics") or {}).get("mcp") or {}).get("by_tool") or {}
        if not isinstance(by_tool, dict):
            continue
        for tool, payload in by_tool.items():
            if isinstance(payload, dict):
                values.setdefault(str(tool), []).append(float(payload.get("count") or 0))
    return values


def job_result_summary(record: dict[str, Any]) -> dict[str, Any]:
    return {
        "round": record.get("round"),
        "job_id": record.get("job_id"),
        "status": record.get("status"),
        "result": record.get("result"),
        "job_revision": record.get("job_revision"),
        "total_units": record.get("total_units"),
        "completed_units": record.get("completed_units"),
        "failed_units": record.get("failed_units"),
        "output_bytes": record.get("output_bytes"),
        "error": record.get("error"),
    }


def config_record_log(config: SwitchConfig, record: dict[str, Any]) -> dict[str, Any]:
    return {
        "config": config.name,
        "round": record.get("round"),
        "status": record.get("status"),
        "result": record.get("result"),
        "duration_s": record.get("total_duration_s"),
        "tool_calls": record.get("tool_calls"),
        "session_init_count": record.get("session_init_count"),
        "delegation_exchange_count": record.get("delegation_exchange_count"),
    }


def switch_application(args: argparse.Namespace) -> dict[str, Any]:
    return {
        "switches": list(SWITCHES),
        "restart_command_provided": bool(args.restart_command),
        "note": (
            "Agent and knowledge-core switches are process settings. Provide "
            "--restart-command, or restart services externally between configs, "
            "so each listed environment is effective."
        ),
    }


def timestamp() -> str:
    return datetime.now(UTC).strftime("%Y%m%dT%H%M%SZ")


def csv(value: str) -> list[str]:
    return [item.strip() for item in value.split(",") if item.strip()]


def csv_ints(value: str) -> list[int]:
    return [int(item) for item in csv(value)]


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(json.dumps({"error": str(exc)}, ensure_ascii=False), file=sys.stderr)
        raise SystemExit(1) from exc
