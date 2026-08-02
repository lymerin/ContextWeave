"""文档目标解析——PlannedSection[] → SectionTarget (document_id + block_id)。

规则:
  已有文档 + 已有 Block (target_kind 匹配) → 复用 document_id + block_id
  已有文档 + 无匹配 Block → 新建 Block (created_block_operation_id)
  无候选文档 → 新建文档 (created_document_operation_id) + 新建 Block
"""

import uuid as _uuid
from dataclasses import dataclass
from uuid import UUID

from app.schemas.document_planner.plan import PlannedSection
from app.schemas.platform_mcp.binding import ExistingBinding
from app.schemas.platform_mcp.document import DocumentCandidate, DocumentStructure


@dataclass
class SectionTarget:
    section_ref: str
    document_id: UUID | None = None
    block_id: UUID | None = None
    created_document_op_id: str | None = None
    created_block_op_id: str | None = None
    action: str = "APPEND"  # APPEND | CREATE_DOC | ADD_BLOCK


def resolve_targets(
    sections: tuple[PlannedSection, ...],
    candidates: list[DocumentCandidate],
    structures: list[DocumentStructure],
    existing_bindings: list[ExistingBinding],
) -> tuple[SectionTarget, ...]:
    """为每个 PlannedSection 确定文档端目标。

    决策:
      1. candidates 非空 → action=APPEND, 取第一个候选 document_id
      2. candidates 为空 → action=CREATE_DOC
      3. 已有文档: 用 target_kind 匹配已有 Block
         匹配上 → 复用 block_id
         匹配不上 → action=ADD_BLOCK
    """
    target_doc_id: UUID | None = None
    create_doc_id: str | None = None

    if candidates:
        target_doc_id = candidates[0].document_id
        action = "APPEND"
    else:
        create_doc_id = f"create_doc_{_uuid.uuid4().hex[:12]}"
        action = "CREATE_DOC"

    # 已有文档的 Block 索引
    doc_blocks: dict[str, UUID] = {}  # target_kind → block_id
    if target_doc_id:
        for s in structures:
            if s.document_id == target_doc_id:
                for b in s.blocks:
                    kind = _block_kind_hint(b.plain_text or "")
                    if kind:
                        doc_blocks[kind] = b.block_id

    results: list[SectionTarget] = []
    for i, section in enumerate(sections):
        if section.target_kind and section.target_kind in doc_blocks:
            # 匹配已有 Block
            results.append(SectionTarget(
                section_ref=section.section_ref,
                document_id=target_doc_id,
                block_id=doc_blocks[section.target_kind],
                action="APPEND",
            ))
        elif target_doc_id:
            # 已有文档，新 Block
            block_op_id = f"add_block_{i}_{_uuid.uuid4().hex[:8]}"
            results.append(SectionTarget(
                section_ref=section.section_ref,
                document_id=target_doc_id,
                created_block_op_id=block_op_id,
                action="ADD_BLOCK",
            ))
        else:
            # 新建文档 + 新 Block
            block_op_id = f"add_block_{i}_{_uuid.uuid4().hex[:8]}"
            results.append(SectionTarget(
                section_ref=section.section_ref,
                created_document_op_id=create_doc_id,
                created_block_op_id=block_op_id,
                action="CREATE_DOC",
            ))

    return tuple(results)


def _block_kind_hint(plain_text: str) -> str:
    """从 Block 的 plain_text 标题推断 target_kind。"""
    if not plain_text:
        return ""
    first = plain_text.strip().splitlines()[0] if plain_text.strip() else ""
    kind_map = {
        "接口职责": "HTTP_ENDPOINT",
        "数据模型": "DATA_MODEL",
        "领域转换": "DATA_CONVERSION",
        "业务规则": "BUSINESS_RULE",
        "响应构造": "RESPONSE_CONSTRUCTION",
        "代码职责": "SYMBOL",
        "类型职责": "SYMBOL",
    }
    for keyword, kind in kind_map.items():
        if keyword in first:
            return kind
    return ""
