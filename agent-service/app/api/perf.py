from typing import Any

from fastapi import APIRouter

from app import perf_metrics

router = APIRouter(prefix="/api/v1/perf", tags=["perf"])


@router.get("/metrics")
async def get_metrics() -> dict[str, Any]:
    return perf_metrics.snapshot()


@router.post("/metrics/reset")
async def reset_metrics() -> dict[str, str]:
    perf_metrics.reset()
    return {"status": "RESET"}
