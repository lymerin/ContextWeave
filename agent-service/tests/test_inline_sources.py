import sys
from datetime import UTC, datetime
from uuid import uuid4

sys.path.insert(0, "agent-service")

from app.execution.job_executor import _build_inline_sources
from app.schemas.document_planner.skeleton import SkeletonSlot, SlotType
from app.schemas.model_context.snapshot import ContextSnapshot, SnapshotManifest
from app.schemas.repository_graph import Relation, RelationCategory, RelationKind
from app.schemas.shaped_context import AtomRef, SourceChunk, StructureBlock


def test_flow_inline_sources_use_outline_and_slot_source_only() -> None:
    entry_key = "PYTHON:src/entry.py:run:FUNCTION"
    helper_key = "PYTHON:src/helper.py:helper:FUNCTION"
    atoms = (
        AtomRef("sym_entry", entry_key, chunk_ids=("chk_entry",)),
        AtomRef("sym_helper", helper_key, chunk_ids=("chk_helper",)),
    )
    chunks = (
        SourceChunk("chk_entry", "src/entry.py", 1, 5, "def run(): pass", (entry_key,)),
        SourceChunk("chk_helper", "src/helper.py", 1, 5, "def helper(): pass", (helper_key,)),
    )
    snap = ContextSnapshot(
        context_id="ctx-1",
        repository_id=uuid4(),
        revision="rev-1",
        snapshot_hash="hash",
        manifest=SnapshotManifest(atom_count=2, source_chunk_count=2),
        frozen_at=datetime.now(UTC).isoformat(),
        atoms=atoms,
        chunks=chunks,
        structure_blocks=(
            StructureBlock(
                "blk-entry",
                atoms=(entry_key,),
                chunks=("chk_entry",),
                entry_label="run",
                description="entry",
            ),
            StructureBlock(
                "blk-helper",
                atoms=(helper_key,),
                chunks=("chk_helper",),
                entry_label="helper",
                description="helper",
            ),
        ),
        relations=(
            Relation(
                "rel-1",
                "sym_entry",
                RelationKind.CALLS,
                target_atom_id="sym_helper",
                category=RelationCategory.INTERNAL,
                file_path="src/entry.py",
                line=2,
            ),
        ),
        entry_paths=("run -> helper",),
        atom_by_id={a.atom_id: a for a in atoms},
        atom_by_symbol={a.symbol_key: a.atom_id for a in atoms},
        chunk_by_id={c.chunk_id: c for c in chunks},
    )

    sources = _build_inline_sources(
        snap,
        (
            SkeletonSlot(
                slot_id="flow:run",
                slot_type=SlotType.FLOW,
                title="Run",
                primary_symbol_key=entry_key,
            ),
        ),
        include_scope=True,
    )

    assert sources[0]["kind"] == "scope_outline"
    assert sources[0]["entry_paths"] == ["run -> helper"]
    assert sources[0]["relations"][0]["target"] == helper_key
    assert [item.get("source") for item in sources[1:]] == ["def run(): pass"]
