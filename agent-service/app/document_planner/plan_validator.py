"""计划校验与组装——校验 Section ↔ BindingSet 一致性 + 组装 AgentPlan。

校验对象:
  - 每个 PlannedSection 有对应的 SectionBindingSet
  - 每个 atom_id 在 PlanningEvidenceCatalog 中存在
  - 源码路径 / symbol_key / 行号与 Catalog 一致
  - 每个 Section 至少一个 PRIMARY
  - 同一 Section 内 atom_id 不重复
  - revision 一致
"""

import uuid as _uuid
from uuid import UUID

from app.schemas.document_planner.evidence import PlanningEvidenceCatalog
from app.schemas.document_planner.plan import (
    AgentPlan,
    PlannedSection,
    PlanOperation,
    SectionBindingSet,
)


class PlanValidationError(ValueError):
    def __init__(self, issues: list[str]) -> None:
        super().__init__("; ".join(issues))
        self.issues = issues


def assemble_and_validate(
    sections: tuple[PlannedSection, ...],
    binding_sets: tuple[SectionBindingSet, ...],
    evidence: PlanningEvidenceCatalog,
    *,
    context_id: str = "",
    revision: str = "",
    snapshot_hash: str = "",
    section_targets: tuple | None = None,
) -> AgentPlan:
    """校验并组装 AgentPlan。"""
    issues: list[str] = []
    set_by_ref = {bs.section_ref: bs for bs in binding_sets}

    for i, section in enumerate(sections):
        ref = section.section_ref
        bs = set_by_ref.get(ref)

        # 1. 每个 Section 有 BindingSet
        if bs is None:
            issues.append(f"section[{i}].section_ref={ref}: no SectionBindingSet")
            continue

        # 2. atom_id 在 Catalog 中存在
        for j, binding in enumerate(bs.bindings):
            atom = evidence.by_atom_id(binding.atom_id)
            if atom is None:
                issues.append(
                    f"section[{i}].bindings[{j}].atom_id={binding.atom_id}: "
                    "not in PlanningEvidenceCatalog"
                )
                continue
            # 3. 路径 / symbol_key / 行号一致性
            if binding.file_path != atom.file_path:
                issues.append(
                    f"section[{i}].bindings[{j}]: file_path mismatch "
                    f"({binding.file_path} != {atom.file_path})"
                )
            if binding.symbol_key != atom.symbol_key:
                issues.append(
                    f"section[{i}].bindings[{j}]: symbol_key mismatch"
                )
            if binding.start_line != atom.start_line or binding.end_line != atom.end_line:
                issues.append(
                    f"section[{i}].bindings[{j}]: line range mismatch"
                )

        # 4. 至少一个 PRIMARY
        primaries = [b for b in bs.bindings if b.role == "PRIMARY"]
        if not primaries:
            issues.append(f"section[{i}].section_ref={ref}: no PRIMARY binding")

        # 5. 同一 Section 内 atom_id 不重复
        seen: set[str] = set()
        for j, binding in enumerate(bs.bindings):
            if binding.atom_id in seen:
                issues.append(
                    f"section[{i}].bindings[{j}]: duplicate atom_id {binding.atom_id}"
                )
            seen.add(binding.atom_id)

    # 6. revision 一致性
    if revision and revision != evidence.revision:
        issues.append(f"revision mismatch: {revision} != {evidence.revision}")

    if issues:
        raise PlanValidationError(issues)

    # ── Target Gate: document_id 完整性 ──────────────────────────
    targets = section_targets or ()
    target_by_ref = {t.section_ref: t for t in targets}
    for i, bs in enumerate(binding_sets):
        t = target_by_ref.get(bs.section_ref)
        if t is None:
            continue
        has_doc = t.document_id is not None or t.created_document_op_id is not None
        has_block = t.block_id is not None or t.created_block_op_id is not None
        if not has_doc:
            issues.append(f"section[{i}].section_ref={bs.section_ref}: missing document target")
        if not has_block:
            issues.append(f"section[{i}].section_ref={bs.section_ref}: missing block target")

    if issues:
        raise PlanValidationError(issues)

    # ── 组装 AgentPlan ───────────────────────────────────────────
    operations: list[PlanOperation] = []
    seq = 1
    seen_create_docs: set[str] = set()

    for target in targets:
        if target.action == "CREATE_DOC" and target.created_document_op_id:
            if target.created_document_op_id not in seen_create_docs:
                seen_create_docs.add(target.created_document_op_id)
                section = next(
                    (s for s in sections if s.section_ref == target.section_ref), None
                )
                operations.append(PlanOperation(
                    client_operation_id=target.created_document_op_id,
                    sequence_number=seq,
                    operation_type="CREATE_DOCUMENT",
                    proposed_document_title=section.title if section else "代码职责说明",
                    proposed_plain_text=section.title if section else "代码职责说明",
                ))
                seq += 1

    for target in targets:
        if target.created_block_op_id:
            section = next(
                (s for s in sections if s.section_ref == target.section_ref), None
            )
            operations.append(PlanOperation(
                client_operation_id=target.created_block_op_id,
                sequence_number=seq,
                operation_type="ADD_BLOCK",
                document_id=target.document_id,
                created_document_op_id=target.created_document_op_id,
                proposed_plain_text=section.content_markdown if section else "",
            ))
            seq += 1

    return AgentPlan(
        plan_id=_uuid.uuid4().hex[:12],
        context_id=context_id,
        revision=revision or evidence.revision,
        snapshot_hash=snapshot_hash,
        summary=sections[0].title if sections else "",
        rationale="语义分析驱动的文档生成与绑定",
        document_operations=tuple(operations),
        planned_sections=sections,
        section_binding_sets=binding_sets,
    )
