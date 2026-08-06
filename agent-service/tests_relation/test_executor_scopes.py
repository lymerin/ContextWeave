from __future__ import annotations

from uuid import UUID

from app.execution.job_executor import _merge_module_scopes, build_execution_scopes
from app.schemas.source_file import SourceFileBatch, SourceFileRef
from app.source_analysis.code_ast_atom import parse_batch

REPO = UUID("11111111-1111-1111-1111-111111111111")
REV = "rev-1"


def build_catalog(files: dict[str, str]):
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
    return parse_batch(batch, lambda path: sources.get(path)), sources


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


def test_module_mode_merges_to_single_scope() -> None:
    catalog, sources = build_catalog(module_files())
    paths = sorted(sources)
    scopes, graph = build_execution_scopes(
        catalog, lambda p: sources.get(p), paths
    )
    assert len(scopes) == 1
    assert graph is not None
    scope = scopes[0]
    # 入口并集：路由入口 + 其余文件回退入口
    labels = {e.label for e in scope.entries}
    assert "POST /orders" in labels
    # 成员跨文件
    files = {m.symbol_key.split(":")[1] for m in scope.members}
    assert "app/main.py" in files and "app/service.py" in files


def test_single_file_keeps_per_file_scopes() -> None:
    catalog, sources = build_catalog({"a.py": "def f():\n    return 1\n"})
    scopes, graph = build_execution_scopes(
        catalog, lambda p: sources.get(p), ["a.py"]
    )
    assert len(scopes) == 1
    assert scopes[0].related_files == ("a.py",)
    assert graph is not None


def test_module_mode_falls_back_when_no_entries() -> None:
    # 全是私有函数（_ 前缀）→ discover_scopes 无入口 → 回退 build_file_scopes 仍合并为单 scope
    catalog, sources = build_catalog(
        {
            "a.py": "def _helper():\n    return 1\n",
            "b.py": "def _other():\n    return 2\n",
        }
    )
    scopes, _ = build_execution_scopes(
        catalog, lambda p: sources.get(p), ["a.py", "b.py"]
    )
    # 无任何符号 → 空 scope（EMPTY_SCOPE 由上层处理）
    assert len(scopes) == 0


def test_merge_is_deterministic() -> None:
    catalog, sources = build_catalog(module_files())
    paths = sorted(sources)
    def reader(path: str) -> str | None:
        return sources.get(path)

    first_scopes, _ = build_execution_scopes(catalog, reader, paths)
    second_scopes, _ = build_execution_scopes(catalog, reader, paths)
    first, second = first_scopes[0], second_scopes[0]
    assert first.scope_id == second.scope_id
    assert first.members == second.members
    assert first.entries == second.entries


def test_merge_keeps_minimum_distance() -> None:
    from app.schemas.scope import EntryPoint, ScopeMember, SemanticScope

    shared = "PYTHON:a.py:shared:FUNCTION"
    scope_a = SemanticScope(
        scope_id="s1",
        entries=(EntryPoint("PYTHON:a.py:e1:FUNCTION", "PUBLIC_METHOD", "e1"),),
        members=(
            ScopeMember(shared, "INDIRECT_CALLEE", 3, ("PYTHON:a.py:e1:FUNCTION",)),
        ),
    )
    scope_b = SemanticScope(
        scope_id="s2",
        entries=(EntryPoint("PYTHON:b.py:e2:FUNCTION", "PUBLIC_METHOD", "e2"),),
        members=(
            ScopeMember(shared, "DIRECT_CALLEE", 1, ("PYTHON:b.py:e2:FUNCTION",)),
        ),
    )
    merged = _merge_module_scopes((scope_a, scope_b))
    member = next(m for m in merged.members if m.symbol_key == shared)
    assert member.distance == 1
    assert member.entry_paths == ("PYTHON:b.py:e2:FUNCTION",)
    assert len(merged.entries) == 2
