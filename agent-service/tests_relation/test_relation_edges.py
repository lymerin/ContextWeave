from __future__ import annotations

from uuid import UUID

from app.schemas.ast_atom import AtomCatalog
from app.schemas.repository_graph import (
    Relation,
    RelationCategory,
    RelationKind,
    RepositoryCodeGraph,
)
from app.schemas.source_file import SourceFileBatch, SourceFileRef
from app.source_analysis.atom_relation_graph import build_graph
from app.source_analysis.code_ast_atom import parse_batch

REPO = UUID("11111111-1111-1111-1111-111111111111")
REV = "rev-1"


def build(files: dict[str, str]) -> tuple[AtomCatalog, RepositoryCodeGraph]:
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
    graph = build_graph(catalog, lambda path: sources.get(path))
    return catalog, graph


def internal(
    graph: RepositoryCodeGraph, kind: str
) -> list[Relation]:
    return [
        r
        for r in graph.relations
        if r.category == RelationCategory.INTERNAL and r.kind == kind
    ]


def by_file(graph: RepositoryCodeGraph, category: str, kind: str) -> dict[str, list[Relation]]:
    grouped: dict[str, list[Relation]] = {}
    for r in graph.relations:
        if r.category == category and r.kind == kind:
            grouped.setdefault(r.file_path, []).append(r)
    return grouped


def find(
    graph: RepositoryCodeGraph,
    *,
    category: str,
    kind: str,
    source_file: str,
    target_external: str | None = None,
) -> list[Relation]:
    return [
        r
        for r in graph.relations
        if r.category == category
        and r.kind == kind
        and r.file_path == source_file
        and (target_external is None or r.target_external == target_external)
    ]


# ── 跨文件调用 ──────────────────────────────────────────────────────────


def test_cross_file_call_via_from_import() -> None:
    _, graph = build(
        {
            "a.py": "from b import helper\n\ndef run():\n    return helper()\n",
            "b.py": "def helper():\n    return 1\n",
        }
    )
    edges = find(
        graph, category=RelationCategory.INTERNAL, kind=RelationKind.CALLS,
        source_file="a.py",
    )
    assert len(edges) == 1
    assert edges[0].target_atom_id is not None
    symbol = next(s for s in graph.catalog.symbols if s.atom_id == edges[0].target_atom_id)
    assert symbol.symbol_key.startswith("PYTHON:b.py:")


def test_cross_file_call_via_module_alias() -> None:
    _, graph = build(
        {
            "a.py": "import b\n\ndef run():\n    return b.create()\n",
            "b.py": "def create():\n    return 1\n",
        }
    )
    edges = find(
        graph, category=RelationCategory.INTERNAL, kind=RelationKind.CALLS,
        source_file="a.py",
    )
    assert len(edges) == 1
    symbol = next(s for s in graph.catalog.symbols if s.atom_id == edges[0].target_atom_id)
    assert symbol.name == "create"


def test_relative_submodule_call() -> None:
    _, graph = build(
        {
            "app/runtime/a.py": (
                "from . import service\n"
                "\n"
                "def run():\n"
                "    return service.create()\n"
            ),
            "app/runtime/service.py": "def create():\n    return 1\n",
        }
    )
    edges = find(
        graph, category=RelationCategory.INTERNAL, kind=RelationKind.CALLS,
        source_file="app/runtime/a.py",
    )
    assert len(edges) == 1
    symbol = next(s for s in graph.catalog.symbols if s.atom_id == edges[0].target_atom_id)
    assert symbol.symbol_key.startswith("PYTHON:app/runtime/service.py:")


def test_dotted_module_chain_call() -> None:
    _, graph = build(
        {
            "a.py": "import pkg.mod\n\ndef run():\n    return pkg.mod.run_job()\n",
            "pkg/mod.py": "def run_job():\n    return 1\n",
        }
    )
    edges = find(
        graph, category=RelationCategory.INTERNAL, kind=RelationKind.CALLS,
        source_file="a.py",
    )
    assert len(edges) == 1
    symbol = next(s for s in graph.catalog.symbols if s.atom_id == edges[0].target_atom_id)
    assert symbol.name == "run_job"


# ── 同文件解析 ──────────────────────────────────────────────────────────


def test_same_file_plain_call() -> None:
    _, graph = build(
        {
            "a.py": "def helper():\n    return 1\n\ndef run():\n    return helper()\n",
        }
    )
    edges = find(
        graph, category=RelationCategory.INTERNAL, kind=RelationKind.CALLS,
        source_file="a.py",
    )
    assert len(edges) == 1


def test_same_file_class_static_call() -> None:
    _, graph = build(
        {
            "a.py": (
                "class Config:\n"
                "    @classmethod\n"
                "    def load(cls):\n"
                "        return 1\n"
                "\n"
                "def run():\n"
                "    return Config.load()\n"
            ),
        }
    )
    edges = find(
        graph, category=RelationCategory.INTERNAL, kind=RelationKind.CALLS,
        source_file="a.py",
    )
    assert len(edges) == 1
    symbol = next(s for s in graph.catalog.symbols if s.atom_id == edges[0].target_atom_id)
    assert symbol.qualified_name == "Config.load"


def test_self_method_call() -> None:
    _, graph = build(
        {
            "a.py": (
                "class Svc:\n"
                "    def helper(self):\n"
                "        return 1\n"
                "    def run(self):\n"
                "        return self.helper()\n"
            ),
        }
    )
    edges = find(
        graph, category=RelationCategory.INTERNAL, kind=RelationKind.CALLS,
        source_file="a.py",
    )
    assert len(edges) == 1
    symbol = next(s for s in graph.catalog.symbols if s.atom_id == edges[0].target_atom_id)
    assert symbol.qualified_name == "Svc.helper"


def test_file_scoped_lookup_avoids_name_collision() -> None:
    """两个文件都有 helper；a.py 的调用必须命中 a.py 自己的 helper。"""
    _, graph = build(
        {
            "a.py": "def helper():\n    return 'a'\n\ndef run():\n    return helper()\n",
            "b.py": "def helper():\n    return 'b'\n",
        }
    )
    edges = find(
        graph, category=RelationCategory.INTERNAL, kind=RelationKind.CALLS,
        source_file="a.py",
    )
    assert len(edges) == 1
    symbol = next(s for s in graph.catalog.symbols if s.atom_id == edges[0].target_atom_id)
    assert symbol.symbol_key.startswith("PYTHON:a.py:")


# ── 实例化 / 外部 / 动态 ────────────────────────────────────────────────


def test_class_instantiation_is_creates() -> None:
    _, graph = build(
        {
            "a.py": "from b import Item\n\ndef make():\n    return Item()\n",
            "b.py": "class Item:\n    pass\n",
        }
    )
    creates = find(
        graph, category=RelationCategory.INTERNAL, kind=RelationKind.CREATES,
        source_file="a.py",
    )
    assert len(creates) == 1


def test_function_call_is_not_creates() -> None:
    _, graph = build(
        {
            "a.py": "from b import build\n\ndef make():\n    return build()\n",
            "b.py": "def build():\n    return 1\n",
        }
    )
    assert not find(
        graph, category=RelationCategory.INTERNAL, kind=RelationKind.CREATES,
        source_file="a.py",
    )
    assert len(
        find(
            graph, category=RelationCategory.INTERNAL, kind=RelationKind.CALLS,
            source_file="a.py",
        )
    ) == 1


def test_external_call_is_boundary() -> None:
    _, graph = build(
        {
            "a.py": "import redis\n\ndef run():\n    return redis.get('key')\n",
        }
    )
    edges = find(
        graph, category=RelationCategory.BOUNDARY, kind=RelationKind.CALLS,
        source_file="a.py",
    )
    assert len(edges) == 1
    assert edges[0].target_external == "redis.get"


def test_dynamic_base_is_unresolved() -> None:
    _, graph = build(
        {
            "a.py": (
                "class Svc:\n"
                "    def run(self, request):\n"
                "        return request.to_domain()\n"
            ),
        }
    )
    edges = find(
        graph, category=RelationCategory.UNRESOLVED, kind=RelationKind.CALLS,
        source_file="a.py",
    )
    assert len(edges) == 1
    assert edges[0].target_external == "request.to_domain"


# ── 类型注解 / 抛出 ─────────────────────────────────────────────────────


def test_return_type_resolves_via_import() -> None:
    _, graph = build(
        {
            "a.py": (
                "from .domain import ReviewResult\n"
                "\n"
                "def convert() -> ReviewResult:\n"
                "    return ReviewResult()\n"
            ),
            "domain.py": "class ReviewResult:\n    pass\n",
        }
    )
    returns = find(
        graph, category=RelationCategory.INTERNAL, kind=RelationKind.RETURN_TYPE, source_file="a.py"
    )
    assert len(returns) == 1
    symbol = next(s for s in graph.catalog.symbols if s.atom_id == returns[0].target_atom_id)
    assert symbol.name == "ReviewResult"


def test_annotation_intrinsics_produce_no_relations() -> None:
    _, graph = build(
        {
            "a.py": "def f(x: dict[str, int]) -> list[Any]:\n    return []\n",
        }
    )
    assert not internal(graph, RelationKind.PARAMETER_TYPE)
    assert not internal(graph, RelationKind.RETURN_TYPE)
    assert not graph.unresolved


def test_throws_resolves_via_import() -> None:
    _, graph = build(
        {
            "a.py": (
                "from app.errors import ValidationError\n"
                "\n"
                "def run():\n"
                "    raise ValidationError('bad')\n"
            ),
            "app/errors.py": "class ValidationError(Exception):\n    pass\n",
        }
    )
    throws = find(
        graph, category=RelationCategory.INTERNAL, kind=RelationKind.THROWS,
        source_file="a.py",
    )
    assert len(throws) == 1


# ── 确定性 ──────────────────────────────────────────────────────────────


def test_graph_build_is_deterministic() -> None:
    files = {
        "a.py": "from b import helper\n\ndef run():\n    return helper()\n",
        "b.py": "def helper():\n    return 1\n",
    }
    _, first = build(files)
    _, second = build(files)

    def key(r: Relation) -> tuple:
        return (
            r.relation_id, r.source_atom_id, r.kind, r.target_atom_id,
            r.target_external, r.category, r.file_path, r.line,
        )

    assert [key(r) for r in first.relations] == [key(r) for r in second.relations]


def test_relation_id_changes_with_line() -> None:
    _, graph = build(
        {
            "a.py": "def f():\n    print(1)\n    print(2)\n",
        }
    )
    # print 是内置，被过滤；用普通函数验证不同行的 ID 不同
    _, graph = build(
        {
            "a.py": (
                "def helper():\n"
                "    return 1\n"
                "\n"
                "def run():\n"
                "    x = helper()\n"
                "    y = helper()\n"
                "    return x + y\n"
            ),
        }
    )
    edges = internal(graph, RelationKind.CALLS)
    assert len(edges) == 2
    assert edges[0].line != edges[1].line
    assert edges[0].relation_id != edges[1].relation_id


def test_contains_relations_have_stable_ids() -> None:
    _, graph = build(
        {
            "a.py": "class Svc:\n    def run(self):\n        pass\n",
        }
    )
    contains = internal(graph, RelationKind.CONTAINS)
    assert len(contains) == 1
    assert contains[0].relation_id.startswith("rel_")
    assert contains[0].target_atom_id is not None

