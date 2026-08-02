import sys
from datetime import datetime, timezone
from uuid import uuid4

sys.path.insert(0, "agent-service")

from app.schemas.model_context.snapshot import ContextSnapshot, SnapshotManifest
from app.schemas.semantic.analysis_result import (
    EvidenceRef,
    ExecutionStep,
    MemberInterpretation,
    SemanticAnalysisResult,
    SemanticGroup,
)
from app.schemas.shaped_context import AtomRef
from app.execution.job_executor import _bind_result_atoms


def _snap() -> ContextSnapshot:
    atoms = (
        AtomRef(
            atom_id="sym_aaaa",
            symbol_key="PYTHON:app/schemas.py:ReviewDocumentRequest:CLASS",
        ),
        AtomRef(
            atom_id="sym_bbbb",
            symbol_key="PYTHON:app/schemas.py:ReviewDocumentRequest.to_domain:METHOD",
        ),
        AtomRef(
            atom_id="sym_cccc",
            symbol_key="PYTHON:app/schemas.py:ReviewBlockRequest:CLASS",
        ),
    )
    now = datetime.now(timezone.utc).isoformat()
    return ContextSnapshot(
        context_id="ctx-1",
        repository_id=uuid4(),
        revision="rev-1",
        snapshot_hash="hash",
        manifest=SnapshotManifest(atom_count=3),
        frozen_at=now,
        atoms=atoms,
        atom_by_id={a.atom_id: a for a in atoms},
        atom_by_symbol={a.symbol_key: a.atom_id for a in atoms},
    )


def test_bind_symbol_key_to_atom_id() -> None:
    snap = _snap()
    result = SemanticAnalysisResult(
        analysis_id="a1",
        context_id="ctx-1",
        revision="rev-1",
        snapshot_hash="hash",
        overall_responsibility="模型职责",
        semantic_groups=[
            SemanticGroup(
                group_id="g1",
                order=1,
                title="t",
                primary_atom_ids=[
                    "PYTHON:app/schemas.py:ReviewDocumentRequest:CLASS"
                ],
                informed_by_atom_ids=[
                    "PYTHON:app/schemas.py:ReviewDocumentRequest:CLASS",
                    "PYTHON:app/schemas.py:ReviewBlockRequest:CLASS",
                ],
                evidence_refs=[
                    EvidenceRef(
                        atom_id="PYTHON:app/schemas.py:ReviewDocumentRequest:CLASS"
                    )
                ],
            )
        ],
        member_interpretations=[
            MemberInterpretation(
                atom_id="PYTHON:app/schemas.py:ReviewDocumentRequest.to_domain:METHOD",
                evidence_refs=[
                    EvidenceRef(
                        atom_id="PYTHON:app/schemas.py:ReviewDocumentRequest:CLASS"
                    )
                ],
            )
        ],
        execution_flow=[
            ExecutionStep(
                step_order=1,
                atom_id="PYTHON:app/schemas.py:ReviewBlockRequest:CLASS",
            )
        ],
    )

    _bind_result_atoms(result, snap)

    g = result.semantic_groups[0]
    assert g.primary_atom_ids == ["sym_aaaa"], g.primary_atom_ids
    assert g.informed_by_atom_ids == ["sym_aaaa", "sym_cccc"]
    assert g.evidence_refs[0].atom_id == "sym_aaaa"

    m = result.member_interpretations[0]
    assert m.atom_id == "sym_bbbb"
    assert m.evidence_refs[0].atom_id == "sym_aaaa"

    e = result.execution_flow[0]
    assert e.atom_id == "sym_cccc"


def test_unbound_symbol_kept_unchanged() -> None:
    # 模型引用了一个不在快照中的 symbol_key——不视为格式错误，保留原值
    # 让下游证据缺失兜底。
    snap = _snap()
    result = SemanticAnalysisResult(
        analysis_id="a1",
        context_id="ctx-1",
        revision="rev-1",
        snapshot_hash="hash",
        overall_responsibility="r",
        semantic_groups=[
            SemanticGroup(
                group_id="g1",
                order=1,
                primary_atom_ids=["PYTHON:app/other.py:Unknown:CLASS"],
                informed_by_atom_ids=["PYTHON:app/other.py:Unknown:CLASS"],
            )
        ],
    )
    _bind_result_atoms(result, snap)
    assert result.semantic_groups[0].primary_atom_ids == [
        "PYTHON:app/other.py:Unknown:CLASS"
    ]
    assert result.semantic_groups[0].informed_by_atom_ids == [
        "PYTHON:app/other.py:Unknown:CLASS"
    ]


def test_failed_sentinel_skipped() -> None:
    snap = _snap()
    result = SemanticAnalysisResult(
        analysis_id="a1",
        context_id="ctx-1",
        revision="rev-1",
        snapshot_hash="hash",
        overall_responsibility="SEMANTIC_ANALYSIS_FAILED",
    )
    _bind_result_atoms(result, snap)  # should not raise
    assert result.overall_responsibility == "SEMANTIC_ANALYSIS_FAILED"


def test_validator_accepts_both() -> None:
    from app.semantic.result_validator import ResultValidator

    snap = _snap()
    v = ResultValidator(snap)
    assert v._check_atom_exists("x", "PYTHON:app/schemas.py:ReviewBlockRequest:CLASS") or not v._errors
    v2 = ResultValidator(snap)
    v2._check_atom_exists("x", "sym_aaaa")
    assert not v2._errors


def test_snapshot_atom_by_symbol_index() -> None:
    snap = _snap()
    assert snap.atom_by_symbol["PYTHON:app/schemas.py:ReviewDocumentRequest:CLASS"] == "sym_aaaa"
    assert snap.atom_by_symbol["PYTHON:app/schemas.py:ReviewBlockRequest:CLASS"] == "sym_cccc"


if __name__ == "__main__":
    for fn in (
        test_bind_symbol_key_to_atom_id,
        test_unbound_symbol_kept_unchanged,
        test_failed_sentinel_skipped,
        test_validator_accepts_both,
        test_snapshot_atom_by_symbol_index,
    ):
        fn()
        print(f"PASS: {fn.__name__}")
    print("ALL TESTS PASSED")
