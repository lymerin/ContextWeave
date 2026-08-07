"""探针：复现批 1（模块总览）的 scope 构建，定位 entries 缺失根因。

从 MCP 读取 src/flask 20 个文件 → parse_batch → build_execution_scopes →
打印 scope.entries / members / NullSession 是否在快照中。
"""
import asyncio
import json
import os
import sys

from app.clients.mcp_client import ReadOnlyMcpClient
from app.execution.job_executor import build_execution_scopes
from app.platform_mcp.source_reader import SourceReader
from app.schemas.platform_mcp.source_file import SelectedSourceFileBatch
from app.source_analysis.code_ast_atom import parse_batch

WS = "a7958db9-210f-4e33-8e9d-b75cd5f9d40b"
REPO = "4fff7424-eff5-4e22-80a1-09eccf110267"
REVISION = os.environ.get("REVISION", "")
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


async def main() -> None:
    mcp = ReadOnlyMcpClient(
        base_url=os.environ.get("MCP_BASE_URL", "http://host.docker.internal:8091/mcp"),
        token=os.environ.get("MCP_TOKEN", ""),
    )
    reader = SourceReader(mcp)
    selection = SelectedSourceFileBatch(
        repository_id=REPO,
        revision=REVISION,
        paths=tuple(FILES),
        total_count=len(FILES),
    )
    sources = await reader.read_batch(WS, REPO, selection)
    by_path = {f.file_path: f.content for f in sources.files}

    def read_source(path: str) -> str | None:
        return by_path.get(path)

    catalog = parse_batch(sources, read_source)
    print(f"catalog.symbols: {len(catalog.symbols)}")
    print(f"catalog.modules: {[m.file_path for m in catalog.modules][:30]}")

    scopes, graph = build_execution_scopes(catalog, read_source, FILES)
    print(f"scopes: {len(scopes)}")
    if not scopes:
        print("NO SCOPES")
        return
    scope = scopes[0]
    print(f"scope.entries: {len(scope.entries)}")
    for e in scope.entries:
        print(f"  entry: {e.symbol_key}")
    print(f"scope.members: {len(scope.members)}")
    for target in (
        "PYTHON:src/flask/sessions.py:NullSession:CLASS",
        "PYTHON:src/flask/sessions.py:SessionMixin:CLASS",
        "PYTHON:src/flask/views.py:View:CLASS",
        "PYTHON:src/flask/wrappers.py:Request:CLASS",
        "PYTHON:src/flask/app.py:Flask:CLASS",
    ):
        found = any(m.symbol_key == target for m in scope.members)
        print(f"  member {target}: {found}")
    # 按文件统计 members
    from collections import Counter
    files = Counter(m.symbol_key.split(":", 2)[1] for m in scope.members)
    for f, n in sorted(files.items()):
        print(f"  file {f}: {n} members")


if __name__ == "__main__":
    asyncio.run(main())
