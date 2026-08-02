"""文档正文组装——semantic_groups → PlannedSection[]。

一个 semantic_group → 一个 PlannedSection。
每个 Section 携带 primary_atom_ids 和 informed_by_atom_ids 作为绑定依据。
"""

from app.schemas.document_planner.plan import PlannedSection
from app.schemas.semantic.analysis_result import SemanticAnalysisResult


def compose_document(
    semantic: SemanticAnalysisResult,
    title: str = "",
) -> tuple[PlannedSection, ...]:
    """从语义结果生成 PlannedSection 列表。

    优先使用 semantic_groups（DeepSeek 分组），
    没有 semantic_groups 时降级为 member_interpretations。
    """
    if semantic.semantic_groups:
        return tuple(
            PlannedSection(
                section_ref=g.group_id or f"section_{i}",
                order=g.order or i,
                title=g.title,
                target_kind=g.target_kind,
                content_markdown=f"## {g.title}\n\n{g.summary}",
                primary_atom_ids=tuple(g.primary_atom_ids),
                informed_by_atom_ids=tuple(g.informed_by_atom_ids),
            )
            for i, g in enumerate(semantic.semantic_groups)
        )

    # 降级：每个 member_interpretation 一个 Section
    return tuple(
        PlannedSection(
            section_ref=f"section_{i}",
            order=i,
            title=m.role or "代码职责",
            target_kind=m.role or "SYMBOL",
            content_markdown=f"## {m.role or '代码职责'}\n\n{m.responsibility}",
            primary_atom_ids=(m.atom_id,),
            informed_by_atom_ids=(m.atom_id,),
        )
        for i, m in enumerate(semantic.member_interpretations)
    )
