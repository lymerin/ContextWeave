import sys
from datetime import UTC, datetime
from uuid import uuid4

sys.path.insert(0, "agent-service")

from app.schemas.model_context.snapshot import ContextSnapshot, SnapshotManifest
from app.schemas.semantic.analysis_result import (
    EvidenceRef,
    MemberInterpretation,
    SemanticAnalysisResult,
)
from app.schemas.shaped_context import AtomRef, SourceChunk
from app.semantic.result_validator import ResultValidator


def _snap_with_chunks() -> ContextSnapshot:
    atoms = (
        AtomRef(
            atom_id="sym_aaaa",
            symbol_key="PYTHON:src/flask/ctx.py:AppCtxGlobals:CLASS",
        ),
    )
    chunks = (
        SourceChunk(
            chunk_id="chk_aaaa",
            file_path="src/flask/ctx.py",
            start_line=300,
            end_line=517,
            source="x",
            covers=("PYTHON:src/flask/ctx.py:AppCtxGlobals:CLASS",),
        ),
        SourceChunk(
            chunk_id="chk_bbbb",
            file_path="src/flask/globals.py",
            start_line=17,
            end_line=30,
            source="y",
            covers=("PYTHON:src/flask/globals.py:g:VARIABLE",),
        ),
    )
    now = datetime.now(UTC).isoformat()
    return ContextSnapshot(
        context_id="ctx-1",
        repository_id=uuid4(),
        revision="rev-1",
        snapshot_hash="hash",
        manifest=SnapshotManifest(atom_count=1, source_chunk_count=2),
        frozen_at=now,
        atoms=atoms,
        chunks=chunks,
        atom_by_id={a.atom_id: a for a in atoms},
        atom_by_symbol={a.symbol_key: a.atom_id for a in atoms},
        chunk_by_id={c.chunk_id: c for c in chunks},
    )


def _result_with_source_chunk(source_chunk_id: str | None) -> SemanticAnalysisResult:
    return SemanticAnalysisResult(
        analysis_id="a1",
        context_id="ctx-1",
        revision="rev-1",
        snapshot_hash="hash",
        overall_responsibility="模型职责",
        member_interpretations=[
            MemberInterpretation(
                atom_id="PYTHON:src/flask/ctx.py:AppCtxGlobals:CLASS",
                evidence_refs=[
                    EvidenceRef(
                        atom_id="PYTHON:src/flask/ctx.py:AppCtxGlobals:CLASS",
                        source_chunk_id=source_chunk_id,
                    )
                ],
            )
        ],
    )


def _errors_for(source_chunk_id: str | None) -> list[str]:
    return ResultValidator(_snap_with_chunks()).validate(
        _result_with_source_chunk(source_chunk_id)
    )


def test_hash_chunk_id_is_valid() -> None:
    errors = _errors_for("chk_aaaa")
    assert errors == []


def test_null_source_chunk_id_is_tolerated() -> None:
    errors = _errors_for(None)
    assert errors == []


def test_plain_chunk_ref_exact_match_is_tolerated() -> None:
    errors = _errors_for("src/flask/ctx.py:300-517")
    assert errors == []


def test_plain_chunk_ref_overlapping_range_is_tolerated() -> None:
    # 行号经过裁剪去重，模型引用符号整体范围（300-517）而 chunk 只保留
    # 子范围（300-400）时，区间相交仍视为有效证据。
    errors = _errors_for("src/flask/ctx.py:300-400")
    assert errors == []


def test_plain_chunk_ref_with_invented_lines_is_rejected() -> None:
    # 行号越界/文件不存在 = 编造引用，仍报错并触发 repair。
    errors = _errors_for("src/flask/ctx.py:9999-10000")
    assert any("source_chunk_id not in snapshot" in e for e in errors)
    errors = _errors_for("src/not_exists.py:1-10")
    assert any("source_chunk_id not in snapshot" in e for e in errors)


def test_garbage_chunk_ref_is_rejected() -> None:
    errors = _errors_for("chk__not_a_real_id__")
    assert any("source_chunk_id not in snapshot" in e for e in errors)
