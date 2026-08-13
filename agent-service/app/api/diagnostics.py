from __future__ import annotations

import asyncio
import urllib.error
import urllib.request
from typing import Any

from fastapi import APIRouter, Request

router = APIRouter(prefix="/diagnostics", tags=["diagnostics"])


@router.get("/readiness")
async def readiness(request: Request) -> dict[str, Any]:
    settings = request.app.state.settings
    checks = {
        "database": await _check_database(request.app.state.job_repository),
        "redis": await _check_ping(request.app.state.run_store),
        "knowledgeCore": await _check_http(
            f"{settings.knowledge_core_base_url}/api/v1/auth/me"
        ),
        "mcp": await _check_http(settings.mcp_base_url),
    }
    status = "UP" if all(item["status"] == "UP" for item in checks.values()) else "DEGRADED"
    return {
        "status": status,
        "checks": checks,
        "config": {
            "knowledgeCoreBaseUrl": settings.knowledge_core_base_url,
            "mcpBaseUrl": settings.mcp_base_url,
            "highConcurrencyRolloutPercent": settings.agent_high_concurrency_rollout_percent,
            "projectUnitConcurrency": settings.agent_project_unit_concurrency,
            "planBatchConcurrency": settings.agent_plan_batch_concurrency,
        },
    }


async def _check_database(repository: object) -> dict[str, str]:
    ping = getattr(repository, "ping", None)
    if callable(ping):
        return {"status": "UP" if await ping() else "DOWN"}
    database = getattr(repository, "_database", None)
    if not callable(database):
        return {"status": "UNKNOWN"}
    try:
        pool = await database()
        async with pool.acquire() as connection:
            await connection.fetchval("SELECT 1")
        return {"status": "UP"}
    except Exception as exc:
        return {"status": "DOWN", "detail": exc.__class__.__name__}


async def _check_ping(client: object) -> dict[str, str]:
    ping = getattr(client, "ping", None)
    if not callable(ping):
        return {"status": "UNKNOWN"}
    return {"status": "UP" if await ping() else "DOWN"}


async def _check_http(url: str) -> dict[str, str]:
    try:
        status_code = await asyncio.to_thread(_http_status, url)
        return {
            "status": "UP" if status_code < 500 else "DOWN",
            "httpStatus": str(status_code),
        }
    except OSError as exc:
        return {"status": "DOWN", "detail": exc.__class__.__name__}


def _http_status(url: str) -> int:
    try:
        with urllib.request.urlopen(url, timeout=2) as response:
            return int(response.status)
    except urllib.error.HTTPError as exc:
        return int(exc.code)
