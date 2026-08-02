import sys

sys.path.insert(0, "agent-service")

from app.document_planner.binding_resolver import resolve_bindings
from app.schemas.document_planner.evidence import (
    EvidenceAtom,
    PlanningEvidenceCatalog,
)
from app.schemas.document_planner.plan import PlannedSection


def _evidence() -> PlanningEvidenceCatalog:
    atoms = (
        EvidenceAtom(
            atom_id="sym_aaaa",
            symbol_key="PYTHON:p/s.py:A:CLASS",
            file_path="p/s.py",
            qualified_name="A",
            kind="CLASS",
            start_line=1,
            end_line=10,
        ),
        EvidenceAtom(
            atom_id="sym_bbbb",
            symbol_key="PYTHON:p/s.py:B:CLASS",
            file_path="p/s.py",
            qualified_name="B",
            kind="CLASS",
            start_line=11,
            end_line=20,
        ),
        EvidenceAtom(
            atom_id="sym_cccc",
            symbol_key="PYTHON:p/s.py:C.to_domain:METHOD",
            file_path="p/s.py",
            qualified_name="C.to_domain",
            kind="METHOD",
            start_line=21,
            end_line=30,
        ),
    )
    return PlanningEvidenceCatalog(
        context_id="ctx-1",
        revision="rev-1",
        atoms=atoms,
    )


def test_single_primary_per_block() -> None:
    """一个 block 有多个 primary → 第一个 PRIMARY，其余降为 SUPPORTING。"""
    evidence = _evidence()
    sections = (
        PlannedSection(
            section_ref="g1",
            title="模块A",
            primary_atom_ids=("sym_aaaa", "sym_bbbb"),
            informed_by_atom_ids=("sym_aaaa", "sym_bbbb", "sym_cccc"),
        ),
    )
    targets = (
        type(
            "T",
            (),
            {
                "section_ref": "g1",
                "created_block_op_id": "add_block_0",
                "created_document_op_id": "create_doc_1",
                "document_id": None,
            },
        )(),
    )

    sets = resolve_bindings(sections, evidence, targets)
    bindings = list(sets[0].bindings)

    # PRIMARY 只有一个
    primaries = [b for b in bindings if b.role == "PRIMARY"]
    assert len(primaries) == 1, f"应有 1 个 PRIMARY，实际 {len(primaries)}"
    assert primaries[0].atom_id == "sym_aaaa"
    assert primaries[0].ordinal == 1

    # SUPPORTING 包含其余 primary + informed 剩余
    supporting = [b for b in bindings if b.role == "SUPPORTING"]
    supporting_ids = [b.atom_id for b in supporting]
    assert "sym_bbbb" in supporting_ids  # 第二个 primary 降级为 SUPPORTING
    assert "sym_cccc" in supporting_ids
    # ordinal 从 2 连续递增，不重复
    ordinals = [b.ordinal for b in bindings]
    assert ordinals == sorted(ordinals)
    assert len(ordinals) == len(set(ordinals)), f"ordinal 重复: {ordinals}"

    # 文档关联被填充
    for b in bindings:
        assert b.created_document_op_id == "create_doc_1"
        assert b.created_block_operation_id == "add_block_0"


if __name__ == "__main__":
    test_single_primary_per_block()
    print("PASS: test_single_primary_per_block")
    print("ALL TESTS PASSED")
