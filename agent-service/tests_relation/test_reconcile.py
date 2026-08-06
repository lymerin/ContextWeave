from __future__ import annotations

from uuid import UUID

from app.document_planner.binding_resolver import resolve_bindings
from app.document_planner.document_composer import compose_document
from app.document_planner.evidence_catalog_builder import build as build_evidence
from app.document_planner.plan_validator import assemble_and_validate
from app.document_planner.target_resolver import resolve_targets
from app.model_context_mcp.context_freeze_snapshot import freeze_context
from app.platform_mcp.plan_writer import PlanWriter
from app.schemas.ast_atom import AtomCatalog
from app.schemas.platform_mcp.binding import ExistingBinding
from app.schemas.platform_mcp.document import DocumentBlock, DocumentCandidate, DocumentStructure
from app.schemas.semantic.analysis_result import SemanticAnalysisResult, SemanticGroup
from app.schemas.source_file import SourceFileBatch, SourceFileRef
from app.source_analysis.atom_relation_graph import build_graph
from app.source_analysis.code_ast_atom import parse_batch
from app.source_analysis.graph_entry_scope import discover_scopes
from app.source_analysis.scope_shape_context import shape_context

REPO = UUID("11111111-1111-1111-1111-111111111111")
REV = "rev-1"
DOC = UUID("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
B1 = UUID("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
B9 = UUID("cccccccc-cccc-cccc-cccc-cccccccccccc")


def module_files() -> dict[str, str]:
    return {
        "app/main.py": (
            "from fastapi import FastAPI\n"
            "from app.service import create_order\n"
            "\n"
            "app = FastAPI()\n"
            "\n"
            "@app.post('/orders')\n"
            "def create_order_route():\n"
            "    return create_order()\n"
        ),
        "app/service.py": (
            "from app.util import validate\n"
            "\n"
            "def create_order():\n"
            "    validate()\n"
            "    return {'ok': True}\n"
        ),
        "app/util.py": "def validate():\n    pass\n",
    }


def pipeline() -> tuple[AtomCatalog, dict[str, object], dict[str, str]]:
    files = module_files()
    batch = SourceFileBatch(
        repository_id=REPO,
        revision=REV,
        files=tuple(
            SourceFileRef(file_path=path, language="Python", size_bytes=len(source))
            for path, source in sorted(files.items())
        ),
        total_count=len(files),
    )
    sources = dict(files)

    def reader(path: str) -> str | None:
        return sources.get(path)

    catalog = parse_batch(batch, reader)
    graph = build_graph(catalog, reader)
    scopes = discover_scopes(graph)
    shaped = shape_context(scopes[0], catalog, reader, graph)
    snap = freeze_context(shaped)
    return catalog, snap, sources


def section_for(atom_id: str, kind: str = "HTTP_ENDPOINT") -> SemanticAnalysisResult:
    return SemanticAnalysisResult(
        overall_responsibility="订单模块",
        semantic_groups=[
            SemanticGroup(
                group_id="g1",
                order=1,
                title="接口职责：POST /orders",
                target_kind=kind,
                summary="创建订单",
                primary_atom_ids=[atom_id],
                informed_by_atom_ids=[atom_id],
            )
        ],
    )


def existing_setup(
    symbol_key: str,
) -> tuple[list[DocumentCandidate], list[DocumentStructure], list[ExistingBinding]]:
    candidates = [DocumentCandidate(document_id=DOC, title="订单模块")]
    structures = [
        DocumentStructure(
            document_id=DOC,
            title="订单模块",
            blocks=[
                DocumentBlock(
                    block_id=B1, version=3, sort_order=0,
                    plain_text="接口职责",
                )
            ],
        )
    ]
    bindings = [
        ExistingBinding(
            binding_id=B9, document_id=DOC, block_id=B1,
            file_path="app/service.py", symbol_key=symbol_key,
            binding_role="PRIMARY", binding_ordinal=1,
        )
    ]
    return candidates, structures, bindings


def test_matched_block_becomes_update_block() -> None:
    catalog, snap, _ = pipeline()
    create_order = next(s for s in catalog.symbols if s.name == "create_order")
    candidates, structures, bindings = existing_setup(create_order.symbol_key)

    sections = compose_document(section_for(create_order.atom_id))
    atom_symbol_keys = {a.atom_id: a.symbol_key for a in snap.atoms}
    targets = resolve_targets(
        sections, candidates, structures, bindings,
        atom_symbol_keys=atom_symbol_keys,
    )
    assert len(targets) == 1
    assert targets[0].action == "UPDATE_BLOCK"
    assert targets[0].block_id == B1
    assert targets[0].base_block_version == 3


def test_unmatched_block_becomes_add_block() -> None:
    catalog, snap, _ = pipeline()
    validate = next(s for s in catalog.symbols if s.name == "validate")
    candidates, structures, bindings = existing_setup(
        "PYTHON:app/service.py:create_order:FUNCTION"
    )

    sections = compose_document(section_for(validate.atom_id))
    atom_symbol_keys = {a.atom_id: a.symbol_key for a in snap.atoms}
    targets = resolve_targets(
        sections, candidates, structures, bindings,
        atom_symbol_keys=atom_symbol_keys,
    )
    assert targets[0].action == "ADD_BLOCK"
    assert targets[0].created_block_op_id is not None


def test_no_candidates_creates_document() -> None:
    catalog, snap, _ = pipeline()
    create_order = next(s for s in catalog.symbols if s.name == "create_order")
    sections = compose_document(section_for(create_order.atom_id))
    atom_symbol_keys = {a.atom_id: a.symbol_key for a in snap.atoms}
    targets = resolve_targets(
        sections, [], [], [],
        atom_symbol_keys=atom_symbol_keys,
    )
    assert targets[0].action == "CREATE_DOC"
    assert targets[0].created_document_op_id is not None


def test_stale_binding_detected_and_removed() -> None:
    catalog, snap, _ = pipeline()
    create_order = next(s for s in catalog.symbols if s.name == "create_order")
    validate = next(s for s in catalog.symbols if s.name == "validate")
    candidates, structures, bindings = existing_setup(create_order.symbol_key)
    # 同一 block 上还有一个已过期的 validate 绑定（validate 不属于本 section）
    stale_id = UUID("dddddddd-dddd-dddd-dddd-dddddddddddd")
    bindings.append(ExistingBinding(
        binding_id=stale_id, document_id=DOC, block_id=B1,
        file_path="app/util.py", symbol_key=validate.symbol_key,
        binding_role="SUPPORTING", binding_ordinal=2,
    ))

    sections = compose_document(section_for(create_order.atom_id))
    atom_symbol_keys = {a.atom_id: a.symbol_key for a in snap.atoms}
    targets = resolve_targets(
        sections, candidates, structures, bindings,
        atom_symbol_keys=atom_symbol_keys,
    )
    evidence = build_evidence(snap, catalog)
    binding_sets = resolve_bindings(
        sections, evidence, targets, existing_bindings=bindings,
    )
    stale = binding_sets[0].stale_bindings
    assert len(stale) == 1
    assert stale[0].binding_id == str(stale_id)
    assert stale[0].file_path == "app/util.py"
    assert stale[0].block_id == str(B1)


def test_plan_carries_update_operation_and_writer_maps_wire_fields() -> None:
    catalog, snap, _ = pipeline()
    create_order = next(s for s in catalog.symbols if s.name == "create_order")
    validate = next(s for s in catalog.symbols if s.name == "validate")
    candidates, structures, bindings = existing_setup(create_order.symbol_key)
    stale_id = UUID("dddddddd-dddd-dddd-dddd-dddddddddddd")
    bindings.append(ExistingBinding(
        binding_id=stale_id, document_id=DOC, block_id=B1,
        file_path="app/util.py", symbol_key=validate.symbol_key,
        binding_role="SUPPORTING", binding_ordinal=2,
    ))

    sections = compose_document(section_for(create_order.atom_id))
    atom_symbol_keys = {a.atom_id: a.symbol_key for a in snap.atoms}
    targets = resolve_targets(
        sections, candidates, structures, bindings,
        atom_symbol_keys=atom_symbol_keys,
    )
    evidence = build_evidence(snap, catalog)
    binding_sets = resolve_bindings(
        sections, evidence, targets, existing_bindings=bindings,
    )
    plan = assemble_and_validate(
        sections, binding_sets, evidence,
        context_id=snap.context_id,
        revision=snap.revision,
        snapshot_hash=snap.snapshot_hash,
        section_targets=targets,
    )

    updates = [
        op for op in plan.document_operations
        if op.operation_type == "UPDATE_BLOCK"
    ]
    assert len(updates) == 1
    assert updates[0].block_id == B1
    assert updates[0].base_block_version == 3

    class FakeReviewClient:
        def __init__(self) -> None:
            self.payload: dict | None = None

        async def submit_document_change(
            self, payload: dict, **kwargs: object
        ) -> dict:
            self.payload = payload
            return {"changeRequestId": "review-1", "status": "PENDING"}

    client = FakeReviewClient()
    writer = PlanWriter(client)  # type: ignore[arg-type]
    import asyncio

    asyncio.run(writer.submit(
        plan,
        workspace_id="11111111-1111-1111-1111-111111111111",
        run_id="run-1",
        repository_id="22222222-2222-2222-2222-222222222222",
    ))
    payload = client.payload
    assert payload is not None
    op = payload["operations"][0]
    assert op["operationType"] == "UPDATE_BLOCK"
    assert op["baseBlockVersion"] == 3
    assert op["blockId"] == str(B1)
    removes = [
        p for p in payload["bindingProposals"]
        if p["action"] == "REMOVE_BINDING"
    ]
    assert len(removes) == 1
    assert removes[0]["bindingId"] == str(stale_id)
    assert removes[0]["filePath"] == "app/util.py"
    assert removes[0]["blockId"] == str(B1)


def test_reconcile_is_deterministic() -> None:
    catalog, snap, _ = pipeline()
    create_order = next(s for s in catalog.symbols if s.name == "create_order")
    candidates, structures, bindings = existing_setup(create_order.symbol_key)
    sections = compose_document(section_for(create_order.atom_id))
    atom_symbol_keys = {a.atom_id: a.symbol_key for a in snap.atoms}

    first = resolve_targets(
        sections, candidates, structures, bindings,
        atom_symbol_keys=atom_symbol_keys,
    )
    second = resolve_targets(
        sections, candidates, structures, bindings,
        atom_symbol_keys=atom_symbol_keys,
    )
    assert first == second


def test_used_block_not_matched_twice() -> None:
    catalog, snap, _ = pipeline()
    create_order = next(s for s in catalog.symbols if s.name == "create_order")
    candidates, structures, bindings = existing_setup(create_order.symbol_key)

    result = SemanticAnalysisResult(
        overall_responsibility="订单模块",
        semantic_groups=[
            SemanticGroup(
                group_id="g1", order=1, title="接口职责",
                target_kind="HTTP_ENDPOINT", summary="a",
                primary_atom_ids=[create_order.atom_id],
                informed_by_atom_ids=[create_order.atom_id],
            ),
            SemanticGroup(
                group_id="g2", order=2, title="接口职责 2",
                target_kind="HTTP_ENDPOINT", summary="b",
                primary_atom_ids=[create_order.atom_id],
                informed_by_atom_ids=[create_order.atom_id],
            ),
        ],
    )
    sections = compose_document(result)
    atom_symbol_keys = {a.atom_id: a.symbol_key for a in snap.atoms}
    targets = resolve_targets(
        sections, candidates, structures, bindings,
        atom_symbol_keys=atom_symbol_keys,
    )
    assert targets[0].action == "UPDATE_BLOCK"
    assert targets[1].action == "ADD_BLOCK"  # 同一块只命中一次
