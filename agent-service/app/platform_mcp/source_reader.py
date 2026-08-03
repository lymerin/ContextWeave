"""平台 MCP 源码读取——批量读取筛选后的文件。

MCP code.read 有 maxCodeLines 限制（400 行）。对于更大的文件，
本模块自动分段读取并拼接完整内容，不依赖调用方感知截断。
"""

from __future__ import annotations

from typing import Any
from uuid import UUID

from app.clients.mcp_client import ReadOnlyMcpClient
from app.schemas.platform_mcp.source_file import (
    SelectedSourceFileBatch,
    SourceFileBatch,
    SourceFileRef,
)

# MCP code.read 的单次行数限制（来自 McpProperties.maxCodeLines 默认值）。
# 超出此限制会被截断，本模块自动分段续读。
_MCP_MAX_LINES = 400


class SourceReader:
    """批量读取源码文件，自动处理 MCP 截断分段续读。"""

    def __init__(
        self,
        client: ReadOnlyMcpClient,
        max_chars: int = 200_000,
    ) -> None:
        self._client = client
        self._max_chars = max_chars

    async def read_batch(
        self,
        workspace_id: UUID,
        repository_id: UUID,
        selection: SelectedSourceFileBatch,
    ) -> SourceFileBatch:
        """读取 SelectedSourceFileBatch 中的全部源码，超限自动分段。"""
        files: list[SourceFileRef] = []
        remaining_chars = self._max_chars

        for path in selection.paths:
            content, language, truncated = await self._read_full(
                workspace_id, repository_id, path
            )
            if len(content) > remaining_chars:
                content = content[:remaining_chars]
                truncated = True
            remaining_chars -= len(content)
            files.append(SourceFileRef(
                file_path=path,
                language=language,
                content=content,
                size_bytes=max(0, len(content.encode("utf-8"))),
                truncated=truncated,
            ))

        return SourceFileBatch(
            repository_id=str(repository_id),
            revision=selection.revision,
            files=tuple(files),
            total_count=len(files),
        )

    async def _read_full(
        self,
        workspace_id: UUID,
        repository_id: UUID,
        path: str,
    ) -> tuple[str, str, bool]:
        """读取单个文件的完整源码——若 MCP 截断则自动分段续读拼接。"""
        base_args = {
            "workspaceId": str(workspace_id),
            "repositoryId": str(repository_id),
            "path": path,
        }

        result = await self._client.call_tool(
            "devcollab.code.read", base_args, "delegated"
        )
        content = str(result.get("content", ""))
        language = str(result.get("language") or "")
        total_lines = int(result.get("totalLines") or 0)
        last_end = int(result.get("endLine") or 0)
        was_truncated = bool(result.get("truncated", False))

        # 自动分段续读：endLine < totalLines 说明还有内容未读到。
        # startLine 和 endLine 必须同时提供。每次读 MCP 单次上限行数。
        while last_end > 0 and last_end < total_lines:
            chunk_args = dict(base_args)
            chunk_args["startLine"] = last_end + 1
            chunk_args["endLine"] = min(last_end + _MCP_MAX_LINES, total_lines)
            chunk = await self._client.call_tool(
                "devcollab.code.read", chunk_args, "delegated"
            )
            chunk_content = str(chunk.get("content", ""))
            if not chunk_content:
                break
            content += chunk_content
            last_end = int(chunk.get("endLine") or last_end)
            was_truncated = was_truncated or bool(chunk.get("truncated", False))

        return content, language, was_truncated
