"""宿主复现：批 1 的 scope 构建 —— 定位 9 个 slot 目标不在快照的根因。"""
import os
import sys

sys.path.insert(0, os.path.abspath("agent-service"))

from app.execution.job_executor import build_execution_scopes
from app.schemas.platform_mcp.source_file import SourceFileBatch, SourceFileRef
from app.source_analysis.code_ast_atom import parse_batch

ROOT = os.path.abspath("agent-service/.pytest-tmp/e2e/flask_src")
FILES = [
    "src/flask/blueprints.py", "src/flask/app.py", "src/flask/ctx.py",
    "src/flask/cli.py", "src/flask/testing.py", "src/flask/globals.py",
    "src/flask/config.py", "src/flask/debughelpers.py",
    "src/flask/sansio/blueprints.py", "src/flask/json/tag.py",
    "src/flask/helpers.py", "src/flask/sessions.py",
    "src/flask/json/__init__.py", "src/flask/templating.py",
    "src/flask/views.py", "src/flask/json/provider.py",
    "src/flask/logging.py", "src/flask/wrappers.py",
    "src/flask/sansio/app.py", "src/flask/sansio/scaffold.py",
]


def load() -> SourceFileBatch:
    files = []
    for p in FILES:
        full = os.path.join(ROOT, *p.split("/"))
        with open(full, encoding="utf-8-sig") as fh:
            content = fh.read()
        files.append(SourceFileRef(file_path=p, language="Python", content=content))
    return SourceFileBatch(
        repository_id="4fff7424-eff5-4e22-80a1-09eccf110267",
        revision="probe", files=tuple(files), total_count=len(FILES),
    )


def read_source(sources: SourceFileBatch, path: str) -> str | None:
    for f in sources.files:
        if f.file_path == path:
            return f.content
    return None


def main() -> None:
    sources = load()
    catalog = parse_batch(sources, lambda p: read_source(sources, p))
    print(f"catalog.symbols: {len(catalog.symbols)}")
    print(f"catalog.modules: {len(catalog.modules)}")
    for m in catalog.modules:
        print(f"  module: {m.file_path}")
    by_file: dict[str, int] = {}
    for s in catalog.symbols:
        f = s.symbol_key.split(":", 2)[1] if ":" in s.symbol_key else "?"
        by_file[f] = by_file.get(f, 0) + 1
    for f, n in sorted(by_file.items()):
        print(f"  file {f}: {n} symbols")

    scopes, graph = build_execution_scopes(catalog, lambda p: read_source(sources, p), FILES)
    print(f"scopes: {len(scopes)}")
    if not scopes:
        print("NO SCOPES")
        return
    scope = scopes[0]
    print(f"scope.entries: {len(scope.entries)}")
    for e in scope.entries:
        print(f"  entry: {e.symbol_key}")
    print(f"scope.related_files: {len(scope.related_files)}")
    for f in sorted(scope.related_files):
        print(f"  rf: {f}")
    for target in (
        "PYTHON:src/flask/sessions.py:NullSession:CLASS",
        "PYTHON:src/flask/sessions.py:SessionMixin:CLASS",
        "PYTHON:src/flask/views.py:View:CLASS",
        "PYTHON:src/flask/wrappers.py:Request:CLASS",
        "PYTHON:src/flask/app.py:Flask:CLASS",
    ):
        found = any(m.symbol_key == target for m in scope.members)
        print(f"  member {target}: {found}")


if __name__ == "__main__":
    main()
