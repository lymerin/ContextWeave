"""MCP 上下文工具——五个只读工具暴露给 DeepSeek。

工具列表:
  get_context_overview       范围总览（第一步必须调用）
  get_structure_block        读取结构块（主要阅读单位）
  get_atom_detail            读取原子详情
  trace_structure_path       追踪入口到目标的路径
  search_context_symbols     搜索符号
"""

from __future__ import annotations

from typing import Any

from app.model_context_mcp.snapshot_read_service import SnapshotReadService
from app.model_context_mcp.snapshot_store_registry import SnapshotStoreRegistry


class McpContextTools:
    """暴露五种上下文查询为 MCP 可调用工具。"""

    def __init__(self, registry: SnapshotStoreRegistry) -> None:
        self._registry = registry

    # ── 工具1: 总览 ──────────────────────────────────────────────────

    def get_context_overview(self, context_id: str) -> dict[str, Any]:
        snap = self._registry.get(context_id)
        if snap is None:
            return {"error": "CONTEXT_NOT_FOUND"}

        svc = SnapshotReadService(snap)
        ov = svc.overview()
        return {
            "context_id": ov.context_id,
            "revision": ov.revision,
            "atom_count": ov.atom_count,
            "chunk_count": ov.chunk_count,
            "block_count": ov.block_count,
            "entry_paths": list(ov.entry_paths),
            "block_summaries": list(ov.block_summaries),
        }

    # ── 工具2: 结构块 ────────────────────────────────────────────────

    def get_structure_block(
        self, context_id: str, block_id: str
    ) -> dict[str, Any]:
        snap = self._registry.get(context_id)
        if snap is None:
            return {"error": "CONTEXT_NOT_FOUND"}

        svc = SnapshotReadService(snap)
        result = svc.get_block(block_id)
        if result is None:
            return {"error": "BLOCK_NOT_FOUND"}

        return {
            "block_id": result.block_id,
            "description": result.description,
            "atom_ids": list(result.atom_ids),
            "sources": [
                {
                    "chunk_id": s.chunk_id,
                    "file_path": s.file_path,
                    "start_line": s.start_line,
                    "end_line": s.end_line,
                    "source": s.source,
                }
                for s in result.sources
            ],
            "coverage": sorted(result.coverage),
        }

    # ── 工具3: 原子详情 ──────────────────────────────────────────────

    def get_atom_detail(
        self, context_id: str, symbol_key: str
    ) -> dict[str, Any]:
        snap = self._registry.get(context_id)
        if snap is None:
            return {"error": "CONTEXT_NOT_FOUND"}

        svc = SnapshotReadService(snap)
        result = svc.get_atom(symbol_key)
        if result is None:
            return {"error": "ATOM_NOT_FOUND"}

        return {
            "symbol_key": result.symbol_key,
            "sources": [
                {
                    "chunk_id": s.chunk_id,
                    "file_path": s.file_path,
                    "start_line": s.start_line,
                    "end_line": s.end_line,
                    "source": s.source,
                }
                for s in result.sources
            ],
            "coverage": sorted(result.coverage),
        }

    # ── 工具4: 路径追踪 ──────────────────────────────────────────────

    def trace_structure_path(
        self, context_id: str, entry_label: str
    ) -> dict[str, Any]:
        snap = self._registry.get(context_id)
        if snap is None:
            return {"error": "CONTEXT_NOT_FOUND"}

        svc = SnapshotReadService(snap)
        results = svc.trace_path(entry_label)
        return {
            "entry_label": entry_label,
            "paths": [{"steps": list(r.steps)} for r in results],
            "coverage": sorted(
                {s for r in results for s in r.coverage}
            ),
        }

    # ── 工具5: 符号搜索 ──────────────────────────────────────────────

    def search_context_symbols(
        self, context_id: str, query: str
    ) -> dict[str, Any]:
        snap = self._registry.get(context_id)
        if snap is None:
            return {"error": "CONTEXT_NOT_FOUND"}

        svc = SnapshotReadService(snap)
        result = svc.search(query)
        return {
            "query": result.query,
            "matches": list(result.matches),
            "match_count": result.match_count,
        }
