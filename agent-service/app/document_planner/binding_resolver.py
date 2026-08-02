"""绑定解析——PlannedSection[] + PlanningEvidenceCatalog → SectionBindingSet[]。

primary_atom_ids   → PRIMARY 绑定
informed_by 中其余 → SUPPORTING 绑定

所有 file_path / symbol_key / start_line / end_line 从 EvidenceCatalog 查。
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
) -> tuple[SectionBindingSet, ...]:
    """从 PlannedSection 的 atom_id 引用解析完整绑定状态。

    规则:
      primary_atom_ids → PRIMARY (ordinal=1)
      informed_by 中其余 → SUPPORTING (ordinal 从 2 连续递增)
    """
    sets: list[SectionBindingSet] = []

    for section in sections:
        primary_ids = set(section.primary_atom_ids)
        informed_ids = set(section.informed_by_atom_ids)
        all_ids = primary_ids | informed_ids

        bindings: list[SectionBinding] = []

        # PRIMARY
        for atom_id in section.primary_atom_ids:
            atom = evidence.by_atom_id(atom_id)
            if atom is None:
                continue
            bindings.append(SectionBinding(
                atom_id=atom_id,
                file_path=atom.file_path,
                symbol_key=atom.symbol_key,
                start_line=atom.start_line,
                end_line=atom.end_line,
                role="PRIMARY",
                ordinal=1,
            ))

        # SUPPORTING（剩余的全部 informed_by）
        supporting = [aid for aid in section.informed_by_atom_ids if aid not in primary_ids]
        for ordinal, atom_id in enumerate(supporting, start=2):
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
            ))

        sets.append(SectionBindingSet(
            section_ref=section.section_ref,
            bindings=tuple(bindings),
        ))

    return tuple(sets)
