"""平台 MCP 文档模型。"""

from uuid import UUID

from pydantic import BaseModel, ConfigDict


class DocumentBlock(BaseModel):
    """文档块——document.get_structure 返回的单个 block。"""
    model_config = ConfigDict(extra="forbid", strict=True)

    block_id: UUID
    block_type: str = "PARAGRAPH"
    sort_order: int = 0
    version: int = 1
    plain_text: str | None = None
    # content 是 ProseMirror 文档结构的 JSON 字符串（mcp 返回 string）
    content: str | None = None


class DocumentStructure(BaseModel):
    """文档结构——document.get_structure 返回。"""
    model_config = ConfigDict(extra="forbid", strict=True)

    document_id: UUID
    title: str = ""
    document_type: str | None = None
    blocks: list[DocumentBlock] = []
    version: int | None = None


class DocumentCandidate(BaseModel):
    """候选文档——document.find_candidates 返回。"""
    model_config = ConfigDict(extra="forbid", strict=True)

    document_id: UUID
    title: str = ""
