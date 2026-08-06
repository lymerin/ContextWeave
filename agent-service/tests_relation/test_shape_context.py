from __future__ import annotations

from uuid import UUID

from app.model_context_mcp.context_freeze_snapshot import freeze_context
from app.model_context_mcp.snapshot_read_service import SnapshotReadService
from app.schemas.ast_atom import AtomCatalog
from app.schemas.source_file import SourceFileBatch, SourceFileRef
from app.source_analysis.atom_relation_graph import build_graph
from app.source_analysis.code_ast_atom import parse_batch
from app.source_analysis.graph_entry_scope import discover_scopes
from app.source_analysis.scope_shape_context import shape_context

REPO = UUID("11111111-1111-1111-1111-111111111111")
REV = "rev-1"


def build(files: dict[str, str]) -> tuple[AtomCatalog, dict[str, str]]:
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
    catalog = parse_batch(batch, lambda path: sources.get(path))
    return catalog, sources


# ── 模块多文件：块按入口分组、跨文件切块 ────────────────────────────────────


def module_files() -> dict[str, str]:
    return {
        "app/main.py": (
            "from fastapi import FastAPI\n"
            "from app.service import create_order\n"
            "from app.schemas import OrderRequest\n"
            "\n"
            "app = FastAPI()\n"
            "\n"
            "@app.post('/orders')\n"
            "def create_order_route(request: OrderRequest):\n"
            "    return create_order(request)\n"
        ),
        "app/service.py": (
            "from app.schemas import OrderRequest\n"
            "from app.util import validate\n"
            "\n"
            "def create_order(request: OrderRequest):\n"
            "    validate(request)\n"
            "    return {'ok': True}\n"
        ),
        "app/schemas.py": "class OrderRequest:\n    pass\n",
        "app/util.py": "def validate(request):\n    pass\n",
    }


def build_module_snapshot(budget_chars: int = 40_000):
    files = module_files()
    catalog, sources = build(files)
    graph = build_graph(catalog, lambda p: sources.get(p))
    scopes = discover_scopes(graph)
    assert len(scopes) == 1, f"expected one module scope, got {len(scopes)}"
    shaped = shape_context(
        scopes[0], catalog, lambda p: sources.get(p), graph,
        budget_chars=budget_chars,
    )
    return shaped, catalog, scopes[0], graph


def test_chunks_span_multiple_files() -> None:
    shaped, _, _, _ = build_module_snapshot()
    files = {c.file_path for c in shaped.chunks}
    assert {"app/main.py", "app/service.py", "app/util.py"} <= files


def test_blocks_grouped_by_entry() -> None:
    shaped, _, _, _ = build_module_snapshot()
    assert len(shaped.structure_blocks) == 1
    block = shaped.structure_blocks[0]
    assert block.entry_label == "POST /orders"
    assert "POST /orders" in block.description
    # 块内原子 = 入口闭包（跨文件）
    assert any("app/service.py" in a for a in block.atoms)


def test_relations_wired_into_snapshot() -> None:
    shaped, catalog, scope, _ = build_module_snapshot()
    assert shaped.relations, "relations must be non-empty"
    scope_atom_ids = {
        next(s for s in catalog.symbols if s.symbol_key == m.symbol_key).atom_id
        for m in scope.members
    }
    assert all(r.source_atom_id in scope_atom_ids for r in shaped.relations)
    snap = freeze_context(shaped)
    assert snap.relations == shaped.relations
    assert snap.relation_by_source
    # 入口原子的出边渲染为 symbol_key
    entry_key = scope.entries[0].symbol_key
    result = SnapshotReadService(snap).get_atom(entry_key)
    assert result is not None
    assert any(
        edge == "CALLS -> PYTHON:app/service.py:create_order:FUNCTION"
        for edge in result.out_edges
    ), result.out_edges


def test_atom_detail_shows_in_edges() -> None:
    shaped, _, _, _ = build_module_snapshot()
    snap = freeze_context(shaped)
    svc = SnapshotReadService(snap)
    result = svc.get_atom("PYTHON:app/service.py:create_order:FUNCTION")
    assert result is not None
    # 入边格式: "KIND <- 来源"（来源用 symbol_key）
    assert any(
        edge == "CALLS <- PYTHON:app/main.py:create_order_route:FUNCTION"
        for edge in result.in_edges
    ), result.in_edges


# ── 预算裁剪 ──────────────────────────────────────────────────────────────


def budget_files() -> dict[str, str]:
    return {
        "main.py": (
            "from fastapi import FastAPI\n"
            "from b import f1\n"
            "\n"
            "app = FastAPI()\n"
            "\n"
            "@app.get('/x')\n"
            "def route():\n"
            "    return f1()\n"
        ),
        "b.py": (
            "def f1():\n"
            "    return f2()\n"
            "\n"
            "def f2():\n"
            "    return f3()\n"
            "\n"
            "def f3():\n"
            "    return 1\n"
        ),
    }


def test_budget_trims_private_only_keeps_public() -> None:
    # 公开符号（骨架槽位覆盖基准）强制保留，预算只裁剪私有辅助符号
    files = budget_files()
    catalog, sources = build(files)
    graph = build_graph(catalog, lambda p: sources.get(p))
    scopes = discover_scopes(graph)
    shaped = shape_context(
        scopes[0], catalog, lambda p: sources.get(p), graph, budget_chars=1
    )
    kept_keys = {a.symbol_key for a in shaped.atoms}
    # 公开符号全部保留（即使超出预算）
    assert "PYTHON:main.py:route:FUNCTION" in kept_keys
    assert "PYTHON:b.py:f1:FUNCTION" in kept_keys
    assert "PYTHON:b.py:f2:FUNCTION" in kept_keys
    assert "PYTHON:b.py:f3:FUNCTION" in kept_keys
    assert not shaped.trimmed_atom_ids
    # 公开符号全部进块
    block_atoms = {a for b in shaped.structure_blocks for a in b.atoms}
    assert "PYTHON:b.py:f2:FUNCTION" in block_atoms
    # freeze 后 manifest 裁剪计数为 0
    snap = freeze_context(shaped)
    assert snap.manifest.trimmed_atom_count == 0


def test_budget_no_trim_when_sufficient() -> None:
    files = budget_files()
    catalog, sources = build(files)
    graph = build_graph(catalog, lambda p: sources.get(p))
    scopes = discover_scopes(graph)
    shaped = shape_context(
        scopes[0], catalog, lambda p: sources.get(p), graph,
        budget_chars=40_000,
    )
    assert not shaped.trimmed_atom_ids


# ── ID 语义化与确定性 ──────────────────────────────────────────────────────


def test_chunk_ids_use_actual_ranges_and_differ() -> None:
    files = {
        "a.py": (
            "class Svc:\n"
            "    def run(self):\n"
            "        return 1\n"
        ),
    }
    catalog, sources = build(files)
    from app.source_analysis.graph_entry_scope import build_file_scopes

    scopes = build_file_scopes(catalog)
    shaped = shape_context(scopes[0], catalog, lambda p: sources.get(p))
    assert len(shaped.chunks) == 2  # 类声明行 + 方法体，互不重叠
    ids = [c.chunk_id for c in shaped.chunks]
    assert len(set(ids)) == 2
    # 实际行范围：类 chunk 1-2（def 行属类），方法 chunk 3-3
    by_line = {c.start_line: c for c in shaped.chunks}
    assert by_line[1].end_line == 2
    assert by_line[3].start_line == 3
    assert by_line[3].end_line == 3


def test_block_ids_stable_across_runs() -> None:
    first = build_module_snapshot()
    second = build_module_snapshot()
    assert [b.block_id for b in first[0].structure_blocks] == [
        b.block_id for b in second[0].structure_blocks
    ]
    assert [c.chunk_id for c in first[0].chunks] == [c.chunk_id for c in second[0].chunks]
    assert first[0].trimmed_atom_ids == second[0].trimmed_atom_ids


def test_block_id_derived_from_entry_key() -> None:
    shaped, _, scope, _ = build_module_snapshot()
    entry_key = scope.entries[0].symbol_key
    import hashlib

    expected = "blk_" + hashlib.sha256(entry_key.encode()).hexdigest()[:24]
    assert shaped.structure_blocks[0].block_id == expected
