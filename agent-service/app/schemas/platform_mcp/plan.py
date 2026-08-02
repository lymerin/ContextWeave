"""平台 MCP 提交模型——Pydantic strict，MCP 写边界。"""

from uuid import UUID

from pydantic import BaseModel, ConfigDict


class SubmitAgentPlanRequest(BaseModel):
    """提交给 MCP submit_document_change 的请求体。"""
    model_config = ConfigDict(extra="forbid", strict=True)

    client_request_id: str
    workspace_id: UUID
    repository_id: UUID
    revision: str
    summary: str
    rationale: str
    operations: list[dict]
    binding_proposals: list[dict]
    evidence: list[dict]


class SubmitAgentPlanResponse(BaseModel):
    """MCP submit_document_change 返回。"""
    model_config = ConfigDict(extra="forbid", strict=True)

    status: str
    change_request_id: UUID | None = None
