from typing import Annotated, Literal
from uuid import UUID

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    field_validator,
    model_validator,
)

SemanticKind = Literal[
    "FRONTEND_API_CLIENT",
    "BACKEND_REST_API",
    "BUSINESS_SERVICE",
    "SECURITY",
    "DATA_ACCESS",
    "WORKER_PROCESS",
    "INTEGRATION",
    "INFRASTRUCTURE_CODE",
    "GENERIC_MODULE",
]


class UnitPlanItem(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: str = Field(min_length=1, max_length=300)
    kind: SemanticKind
    summary: str = Field(min_length=1, max_length=2_000)
    primaryFiles: Annotated[list[str], Field(min_length=1)]
    supportingFiles: list[str] = Field(default_factory=list)
    relatedDocumentIds: list[UUID] = Field(default_factory=list)
    groupingEvidence: Annotated[list[str], Field(min_length=1)]
    # 业务视角：一句话业务定位 + 主要入口链路（模块规划 prompt 要求输出，
    # 语义分析阶段继承为业务上下文）
    businessRole: str = Field(default="", max_length=500)
    primaryFlow: str = Field(default="", max_length=1_000)

    @field_validator("name", "summary")
    @classmethod
    def strip_text(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("value must not be blank")
        return normalized

    @field_validator("primaryFiles", "supportingFiles", "groupingEvidence")
    @classmethod
    def normalize_strings(cls, values: list[str]) -> list[str]:
        normalized = [value.strip().replace("\\", "/") for value in values]
        if any(not value for value in normalized):
            raise ValueError("list values must not be blank")
        return list(dict.fromkeys(normalized))

    @model_validator(mode="after")
    def files_must_have_distinct_roles(self) -> "UnitPlanItem":
        supporting = set(self.supportingFiles)
        if any(path in supporting for path in self.primaryFiles):
            raise ValueError("a file cannot be PRIMARY and SUPPORTING in one unit")
        return self


class UnitPlan(BaseModel):
    model_config = ConfigDict(extra="forbid")

    units: Annotated[list[UnitPlanItem], Field(min_length=1)]
