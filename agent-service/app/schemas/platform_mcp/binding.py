"""平台 MCP 绑定模型。"""

from uuid import UUID

from pydantic import BaseModel, ConfigDict


class ExistingBinding(BaseModel):
    """已有绑定——binding.list / binding.list_batch 返回。"""
    model_config = ConfigDict(extra="forbid", strict=True)

    binding_id: UUID
    repository_id: str | None = None
    file_path: str | None = None
    path_pattern: str = ""
    document_id: UUID
    document_title: str | None = None
    block_id: UUID | None = None
    revision: str | None = None
    anchor_kind: str | None = None
    symbol_key: str | None = None
    start_line: int | None = None
    end_line: int | None = None
    binding_role: str = "PRIMARY"
    binding_ordinal: int = 1
