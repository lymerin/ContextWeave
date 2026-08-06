"""文档正文组装——semantic_groups → PlannedSection[]。

一个 semantic_group → 一个 PlannedSection。
每个 Section 携带 primary_atom_ids 和 informed_by_atom_ids 作为绑定依据。
"""

from app.schemas.document_planner.plan import PlannedSection
from app.schemas.document_planner.skeleton import SkeletonSlot
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


def compose_slot_sections(
    slots: tuple[SkeletonSlot, ...],
    semantic: SemanticAnalysisResult,
    atom_by_symbol: dict[str, str],
) -> tuple[PlannedSection, ...]:
    """槽位批次组装——模型输出 → 槽位对应的 PlannedSection。

    SYMBOL 槽位：member_interpretations 命中目标原子（速查正文）。
    FLOW/OVERVIEW 槽位：semantic_groups 命中入口原子（叙事正文）。
    section_ref = 槽位 ID（稳定身份），供 reconcile 精确命中占位块；
    找不到解释的槽位跳过（由覆盖校验器在会话阶段拦截，不会走到这里）。
    正文优先用 content_markdown（完整正文），缺省回退摘要。
    atom_by_symbol：symbol_key → atom_id 的权威映射（snapshot.atom_by_symbol）。
    """
    interpretations = {
        m.atom_id: m for m in semantic.member_interpretations if m.atom_id
    }
    group_by_atom: dict[str, object] = {}
    for g in semantic.semantic_groups:
        for aid in (*g.primary_atom_ids, *g.informed_by_atom_ids):
            group_by_atom.setdefault(aid, g)

    sections: list[PlannedSection] = []
    for slot in slots:
        atom_id = atom_by_symbol.get(slot.primary_symbol_key or "")
        if atom_id is None:
            continue
        if slot.slot_type == "SYMBOL":
            interp = interpretations.get(atom_id)
            if interp is None:
                continue
            content = interp.content_markdown or interp.responsibility
            primary_ids = (interp.atom_id,)
        else:  # FLOW / OVERVIEW
            group = group_by_atom.get(atom_id)
            if group is None:
                continue
            content = group.content_markdown or group.summary
            primary_ids = tuple(group.primary_atom_ids)
        # 内容以模型正文为准：模型按基座排版示例以 "## 标题" 开头，
        # 再加前缀会造成双层标题、Tiptap 嵌套超限（8 层校验）。
        body = content if content.startswith("#") else f"## {slot.title}\n\n{content}"
        sections.append(PlannedSection(
            section_ref=slot.slot_id,
            order=slot.sort_order,
            title=slot.title,
            target_kind=slot.slot_type,
            content_markdown=body,
            primary_atom_ids=primary_ids,
            informed_by_atom_ids=primary_ids,
        ))
    return tuple(sections)
