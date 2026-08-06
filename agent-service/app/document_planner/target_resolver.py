"""文档目标解析与 reconcile——PlannedSection[] → SectionTarget[]。

决策规则（确定性）:
  1. 无候选文档 → CREATE_DOC + ADD_BLOCK（新建文档）。
  2. 有候选文档 → 匹配现有 Block：
     a. 绑定重叠优先——section 的 primary/informed symbol_key 与 Block 的
        已有绑定 symbol_key 有交集即视为同一区块（跨 revision 稳定身份）；
     b. 无绑定证据时用 target_kind 标题提示兜底。
     匹配成功 → UPDATE_BLOCK（携带 baseBlockVersion 乐观锁），
     不再追加新 Block、不再覆盖人类编辑时产生版本冲突。
     c. 匹配失败 → ADD_BLOCK（文档新增章节）。
  3. 匹配过的 Block 从候选池移除，避免多 section 命中同一块。
"""

from __future__ import annotations

import uuid as _uuid
from dataclasses import dataclass
from uuid import UUID

from app.schemas.document_planner.plan import PlannedSection
from app.schemas.platform_mcp.binding import ExistingBinding
from app.schemas.platform_mcp.document import (
    DocumentBlock,
    DocumentCandidate,
    DocumentStructure,
)


@dataclass
class SectionTarget:
    section_ref: str
    document_id: UUID | None = None
    block_id: UUID | None = None
    created_document_op_id: str | None = None
    created_block_op_id: str | None = None
    base_block_version: int | None = None
    action: str = "APPEND"  # APPEND | CREATE_DOC | ADD_BLOCK | UPDATE_BLOCK


def resolve_targets(
    sections: tuple[PlannedSection, ...],
    candidates: list[DocumentCandidate],
    structures: list[DocumentStructure],
    existing_bindings: list[ExistingBinding],
    atom_symbol_keys: dict[str, str] | None = None,
) -> tuple[SectionTarget, ...]:
    """为每个 PlannedSection 确定文档端目标（含 reconcile 匹配）。"""
    target_doc_id: UUID | None = None
    create_doc_id: str | None = None

    if candidates:
        target_doc_id = candidates[0].document_id
    else:
        create_doc_id = f"create_doc_{_uuid.uuid4().hex[:12]}"

    # 已有文档的 Block 索引（按绑定 symbol_key 与 kind 提示）
    bindings_by_block = _bindings_by_block(existing_bindings)
    block_pool = _blocks_of(target_doc_id, structures)
    used_blocks: set[UUID] = set()

    results: list[SectionTarget] = []
    for i, section in enumerate(sections):
        if not target_doc_id:
            # 新建文档 + 新 Block
            block_op_id = f"add_block_{i}_{_uuid.uuid4().hex[:8]}"
            results.append(SectionTarget(
                section_ref=section.section_ref,
                created_document_op_id=create_doc_id,
                created_block_op_id=block_op_id,
                action="CREATE_DOC",
            ))
            continue

        matched = _match_existing_block(
            section, block_pool, bindings_by_block, used_blocks, atom_symbol_keys
        )
        if matched is not None:
            used_blocks.add(matched.block_id)
            results.append(SectionTarget(
                section_ref=section.section_ref,
                document_id=target_doc_id,
                block_id=matched.block_id,
                base_block_version=matched.version,
                action="UPDATE_BLOCK",
            ))
            continue

        # 已有文档，无匹配 → 新 Block
        block_op_id = f"add_block_{i}_{_uuid.uuid4().hex[:8]}"
        results.append(SectionTarget(
            section_ref=section.section_ref,
            document_id=target_doc_id,
            created_block_op_id=block_op_id,
            action="ADD_BLOCK",
        ))

    return tuple(results)


def _blocks_of(
    target_doc_id: UUID | None,
    structures: list[DocumentStructure],
) -> list[DocumentBlock]:
    if target_doc_id is None:
        return []
    for s in structures:
        if s.document_id == target_doc_id:
            return sorted(s.blocks, key=lambda b: (b.sort_order, str(b.block_id)))
    return []


def _bindings_by_block(
    existing_bindings: list[ExistingBinding],
) -> dict[UUID, set[str]]:
    by_block: dict[UUID, set[str]] = {}
    for b in existing_bindings:
        if b.block_id is None:
            continue
        by_block.setdefault(b.block_id, set())
        if b.symbol_key:
            by_block[b.block_id].add(b.symbol_key)
    return by_block


def _match_existing_block(
    section: PlannedSection,
    block_pool: list[DocumentBlock],
    bindings_by_block: dict[UUID, set[str]],
    used_blocks: set[UUID],
    atom_symbol_keys: dict[str, str] | None,
) -> DocumentBlock | None:
    """确定性匹配现有 Block；匹配过的块不重复命中。"""
    keys = atom_symbol_keys or {}
    primary_keys = {
        keys.get(aid) for aid in section.primary_atom_ids
    } - {None}
    informed_keys = {
        keys.get(aid) for aid in section.informed_by_atom_ids
    } - {None}

    # 1) PRIMARY 绑定重叠（最强信号）
    for block in block_pool:
        if block.block_id in used_blocks:
            continue
        bound = bindings_by_block.get(block.block_id, set())
        if primary_keys and bound & primary_keys:
            return block

    # 2) 任意绑定重叠（primary 或 supporting）
    for block in block_pool:
        if block.block_id in used_blocks:
            continue
        bound = bindings_by_block.get(block.block_id, set())
        if informed_keys and bound & informed_keys:
            return block

    # 3) target_kind 标题提示兜底（无绑定证据的块）
    if section.target_kind:
        for block in block_pool:
            if block.block_id in used_blocks:
                continue
            if bindings_by_block.get(block.block_id):
                continue  # 有绑定的块已由 1/2 判定，不靠标题猜
            if _block_kind_hint(block.plain_text or "") == section.target_kind:
                return block

    return None


def _block_kind_hint(text: str) -> str:
    """从 Block 的 plain_text 标题推断 target_kind。"""
    if not text:
        return ""
    first = text.strip().splitlines()[0] if text.strip() else ""
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
