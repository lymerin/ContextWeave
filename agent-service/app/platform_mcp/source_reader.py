"""Platform MCP source reader with optional code.read_batch first-page reads."""

from __future__ import annotations

from collections.abc import Mapping
from uuid import UUID

from app import perf_metrics
from app.clients.mcp_client import ReadOnlyMcpClient
from app.schemas.platform_mcp.source_file import (
    SelectedSourceFileBatch,
    SourceFileBatch,
    SourceFileRef,
)

# Matches McpProperties.maxCodeLines. Continuation reads must preserve the
# existing code.read paging boundaries.
_MCP_MAX_LINES = 400
_MAX_BATCH_PATHS = 100


class SourceReader:
    """Read selected source files, continuing through MCP line pages as needed."""

    def __init__(
        self,
        client: ReadOnlyMcpClient,
        max_chars: int = 200_000,
        code_read_batch_enabled: bool = False,
    ) -> None:
        self._client = client
        self._max_chars = max_chars
        self._code_read_batch_enabled = code_read_batch_enabled
        self._cache: dict[tuple[str, str], tuple[str, str, bool]] = {}

    async def read_batch(
        self,
        workspace_id: UUID,
        repository_id: UUID,
        selection: SelectedSourceFileBatch,
        blob_shas: Mapping[str, str | None] | None = None,
    ) -> SourceFileBatch:
        """Read all selected source files and keep the existing local char budget."""
        files: list[SourceFileRef] = []
        remaining_chars = self._max_chars
        batch_results = (
            await self._read_batch_full_cached(workspace_id, repository_id, selection, blob_shas)
            if self._code_read_batch_enabled
            else None
        )

        for path in selection.paths:
            if batch_results is None:
                blob_sha = blob_shas.get(path) if blob_shas else None
                content, language, truncated = await self._read_cached(
                    workspace_id, repository_id, path, blob_sha
                )
            else:
                content, language, truncated = batch_results[path]
            if len(content) > remaining_chars:
                content = content[:remaining_chars]
                truncated = True
            remaining_chars -= len(content)
            files.append(
                SourceFileRef(
                    file_path=path,
                    language=language,
                    content=content,
                    size_bytes=max(0, len(content.encode("utf-8"))),
                    truncated=truncated,
                )
            )

        return SourceFileBatch(
            repository_id=str(repository_id),
            revision=selection.revision,
            files=tuple(files),
            total_count=len(files),
        )

    async def _read_batch_full_cached(
        self,
        workspace_id: UUID,
        repository_id: UUID,
        selection: SelectedSourceFileBatch,
        blob_shas: Mapping[str, str | None] | None,
    ) -> dict[str, tuple[str, str, bool]]:
        results: dict[str, tuple[str, str, bool]] = {}
        missing: list[str] = []
        for path in selection.paths:
            key = self._cache_key(path, blob_shas.get(path) if blob_shas else None)
            if key is not None and key in self._cache:
                results[path] = self._cache[key]
            else:
                missing.append(path)

        for start in range(0, len(missing), _MAX_BATCH_PATHS):
            chunk_paths = missing[start : start + _MAX_BATCH_PATHS]
            fetched = await self._read_batch_chunk(
                workspace_id, repository_id, selection.revision, chunk_paths
            )
            for path in chunk_paths:
                result = fetched[path]
                results[path] = result
                key = self._cache_key(path, blob_shas.get(path) if blob_shas else None)
                if key is not None:
                    self._cache[key] = result

        return results

    async def _read_batch_chunk(
        self,
        workspace_id: UUID,
        repository_id: UUID,
        revision: str,
        paths: list[str],
    ) -> dict[str, tuple[str, str, bool]]:
        if not paths:
            return {}
        perf_metrics.increment("code_read_batch_calls")
        result = await self._client.call_tool(
            "devcollab.code.read_batch",
            {
                "workspaceId": str(workspace_id),
                "repositoryId": str(repository_id),
                "paths": paths,
                "revision": revision,
            },
            "delegated",
        )
        files = result.get("files") or {}
        if not isinstance(files, dict):
            raise ValueError("devcollab.code.read_batch returned invalid files")

        results: dict[str, tuple[str, str, bool]] = {}
        for path in paths:
            item = self._batch_item(files, path)
            content = str(item.get("content", ""))
            language = str(item.get("language") or "")
            total_lines = int(item.get("totalLines") or 0)
            last_end = int(item.get("endLine") or 0)
            was_truncated = bool(item.get("truncated", False))
            content, was_truncated = await self._read_remaining(
                workspace_id,
                repository_id,
                path,
                content,
                last_end,
                total_lines,
                was_truncated,
                count_batch_continuation=True,
            )
            results[path] = (content, language, was_truncated)
        return results

    def _batch_item(self, files: dict, path: str) -> dict:
        item = files.get(path)
        if isinstance(item, dict):
            return item
        for candidate in files.values():
            if isinstance(candidate, dict) and str(candidate.get("path", "")) == path:
                return candidate
        raise ValueError(f"devcollab.code.read_batch omitted {path}")

    async def _read_cached(
        self,
        workspace_id: UUID,
        repository_id: UUID,
        path: str,
        blob_sha: str | None,
    ) -> tuple[str, str, bool]:
        key = self._cache_key(path, blob_sha)
        if key is None:
            return await self._read_full(workspace_id, repository_id, path)
        cached = self._cache.get(key)
        if cached is not None:
            return cached
        result = await self._read_full(workspace_id, repository_id, path)
        self._cache[key] = result
        return result

    async def _read_full(
        self,
        workspace_id: UUID,
        repository_id: UUID,
        path: str,
    ) -> tuple[str, str, bool]:
        """Read one full file through code.read plus continuation pages."""
        base_args = {
            "workspaceId": str(workspace_id),
            "repositoryId": str(repository_id),
            "path": path,
        }

        result = await self._client.call_tool("devcollab.code.read", base_args, "delegated")
        content = str(result.get("content", ""))
        language = str(result.get("language") or "")
        total_lines = int(result.get("totalLines") or 0)
        last_end = int(result.get("endLine") or 0)
        was_truncated = bool(result.get("truncated", False))

        content, was_truncated = await self._read_remaining(
            workspace_id,
            repository_id,
            path,
            content,
            last_end,
            total_lines,
            was_truncated,
            count_batch_continuation=False,
        )
        return content, language, was_truncated

    async def _read_remaining(
        self,
        workspace_id: UUID,
        repository_id: UUID,
        path: str,
        content: str,
        last_end: int,
        total_lines: int,
        was_truncated: bool,
        *,
        count_batch_continuation: bool,
    ) -> tuple[str, bool]:
        base_args = {
            "workspaceId": str(workspace_id),
            "repositoryId": str(repository_id),
            "path": path,
        }
        while last_end > 0 and last_end < total_lines:
            chunk_args = dict(base_args)
            chunk_args["startLine"] = last_end + 1
            chunk_args["endLine"] = min(last_end + _MCP_MAX_LINES, total_lines)
            if count_batch_continuation:
                perf_metrics.increment("code_read_batch_continuations")
            chunk = await self._client.call_tool("devcollab.code.read", chunk_args, "delegated")
            chunk_content = str(chunk.get("content", ""))
            if not chunk_content:
                break
            content += chunk_content
            last_end = int(chunk.get("endLine") or last_end)
            was_truncated = was_truncated or bool(chunk.get("truncated", False))
        return content, was_truncated

    def _cache_key(self, path: str, blob_sha: str | None) -> tuple[str, str] | None:
        if blob_sha is None or not blob_sha.strip():
            return None
        return (path, blob_sha.strip())
