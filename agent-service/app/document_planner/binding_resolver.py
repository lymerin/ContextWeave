"""绑定解析——PlannedSection[] + PlanningEvidenceCatalog + SectionTarget[] → SectionBindingSet[]。

primary_atom_ids   → PRIMARY 绑定
informed_by 中其余 → SUPPORTING 绑定

所有 file_path / symbol_key / start_line / end_line 从 EvidenceCatalog 查。
当 section_targets 提供时，将对应 SectionTarget 的 created_block_op_id
填入 SectionBinding，使后续计划写入能为新建 Block 的绑定带上正确的 blockId。
"""

from app.schemas.document_planner.evidence import PlanningEvidenceCatalog
from app.schemas.document_planner.plan import (
    PlannedSection,
    SectionBinding,
    SectionBindingSet,
)


def resolve_bindings(
    sections: tuple[PlannedSection, ...],
    evidence: PlanningEvidenceCatalog,
    section_targets: tuple | None = None,
) -> tuple[SectionBindingSet, ...]:
    """从 PlannedSection 的 atom_id 引用解析完整绑定状态。

    规则:
      primary_atom_ids → PRIMARY (ordinal=1)
      informed_by 中其余 → SUPPORTING (ordinal 从 2 连续递增)

    如果提供了 section_targets，每个 SectionBinding 会带上对应
    SectionTarget.created_block_op_id（如 "add_block_0_abc123"），
    使上下游能通过 review 管线为新建 Block 创建带 blockId 的正式绑定。
    """
    target_by_ref: dict[str, object] = {}
    if section_targets:
        target_by_ref = {t.section_ref: t for t in section_targets}

    sets: list[SectionBindingSet] = []

    for section in sections:
        primary_ids = set(section.primary_atom_ids)
        informed_ids = set(section.informed_by_atom_ids)
        target = target_by_ref.get(section.section_ref)
        created_block_op_id: str | None = (
            getattr(target, "created_block_op_id", None) if target else None
        )
        # 文档目标——knowledge-core 要求 binding 指定 document_id 或
        # created_document_op_id 其中之一。
        document_id = getattr(target, "document_id", None) if target else None
        created_document_op_id = (
            getattr(target, "created_document_op_id", None) if target else None
        )

        bindings: list[SectionBinding] = []

        # 一个文档 block 只能有一个 PRIMARY 锚点。primary_atom_ids 的第一个
        # 设为 PRIMARY(ordinal=1)，其余 primary 与 informed 中剩余的一起
        # 作为 SUPPORTING(ordinal 从 2 连续递增)，避免同一 block 出现多个
        # PRIMARY 造成重复绑定。
        primary_ids = list(section.primary_atom_ids)
        informed_ids = list(section.informed_by_atom_ids)

        # 确定 SUPPORTING 顺序：其余 primary 优先，再补 informed 中未覆盖的
        supporting_ids: list[str] = []
        seen: set[str] = set()
        for aid in primary_ids[1:] + informed_ids:
            if aid in seen or aid == primary_ids[0]:
                continue
            seen.add(aid)
            supporting_ids.append(aid)

        # PRIMARY（第一个 primary 作为唯一锚点）
        first_primary = primary_ids[0] if primary_ids else None
        if first_primary:
            atom = evidence.by_atom_id(first_primary)
            if atom is not None:
                bindings.append(SectionBinding(
                    atom_id=first_primary,
                    file_path=atom.file_path,
                    symbol_key=atom.symbol_key,
                    start_line=atom.start_line,
                    end_line=atom.end_line,
                    role="PRIMARY",
                    ordinal=1,
                    created_block_operation_id=created_block_op_id,
                    document_id=document_id,
                    created_document_op_id=created_document_op_id,
                ))

        # SUPPORTING（其余 primary + informed 中剩余）
        for ordinal, atom_id in enumerate(supporting_ids, start=2):
            atom = evidence.by_atom_id(atom_id)
            if atom is None:
                continue
            bindings.append(SectionBinding(
                atom_id=atom_id,
                file_path=atom.file_path,
                symbol_key=atom.symbol_key,
                start_line=atom.start_line,
                end_line=atom.end_line,
                role="SUPPORTING",
                ordinal=ordinal,
                created_block_operation_id=created_block_op_id,
                document_id=document_id,
                created_document_op_id=created_document_op_id,
            ))

        sets.append(SectionBindingSet(
            section_ref=section.section_ref,
            bindings=tuple(bindings),
        ))

    return tuple(sets)
