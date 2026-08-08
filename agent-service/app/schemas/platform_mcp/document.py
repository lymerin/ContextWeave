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


class DocumentCandidate(BaseModel):
    """候选文档——document.find_candidates 返回。

    existing_binding_count：当前文件集与该文档的 DIRECT_BINDING 命中数。
    只有 >0 才允许复用该文档（模块文档归属必须靠显式绑定，不靠标题/路径猜测）
    ——否则 resolve_targets 会把所有模块都写进 workspace 第一个文档。
    """
    model_config = ConfigDict(extra="forbid", strict=True)

    document_id: UUID
    title: str = ""
    existing_binding_count: int = 0
    matched_block_ids: list[UUID] = []
