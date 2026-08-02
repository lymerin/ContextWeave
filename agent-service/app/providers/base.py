"""ModelProvider 协议——所有 DeepSeek 调用的接口。"""

from typing import Any, Protocol

from app.schemas.plans import AgentPlan
from app.schemas.unit_plans import UnitPlan


class ModelProviderError(RuntimeError):
    def __init__(
        self,
        code: str,
        message: str,
        *,
        raw_plan: dict[str, Any] | None = None,
        validation_errors: list[dict[str, str]] | None = None,
    ) -> None:
        super().__init__(message)
        self.code = code
        self.raw_plan = raw_plan
        self.validation_errors = validation_errors or []


class ModelProvider(Protocol):
    # ── PROJECT_DISCOVERY（保留）───────────────────────────────────

    async def plan_project_units(
        self,
        project_index: dict[str, Any],
        *,
        previous_plan: dict[str, Any] | None = None,
        validation_errors: list[dict[str, str]] | None = None,
    ) -> UnitPlan: ...

    # ── 新语义分析（新管线）───────────────────────────────────────

    async def analyze_semantics(
        self,
        request: Any,
        tool_handler: Any,
    ) -> Any: ...
