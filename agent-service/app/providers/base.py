from typing import Any, Protocol

from app.schemas.binding_plans import BindingPlan
from app.schemas.document_block_content import (
    DocumentBlockContent,
    DocumentBlockContentPlan,
)
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
    async def generate_document_blocks(
        self,
        context: dict[str, Any],
    ) -> DocumentBlockContentPlan: ...

    async def repair_document_block(
        self,
        context: dict[str, Any],
        *,
        previous_block: dict[str, Any],
        validation_errors: list[dict[str, str]],
    ) -> DocumentBlockContent: ...

    async def plan_document_sync(
        self,
        context_bundle: dict[str, Any],
        *,
        previous_plan: dict[str, Any] | None = None,
        validation_errors: list[dict[str, str]] | None = None,
    ) -> AgentPlan: ...

    async def plan_project_units(
        self,
        project_index: dict[str, Any],
        *,
        previous_plan: dict[str, Any] | None = None,
        validation_errors: list[dict[str, str]] | None = None,
    ) -> UnitPlan: ...

    async def plan_block_bindings(
        self,
        candidates: dict[str, Any],
        *,
        previous_plan: dict[str, Any] | None = None,
        validation_errors: list[dict[str, str]] | None = None,
    ) -> BindingPlan: ...
