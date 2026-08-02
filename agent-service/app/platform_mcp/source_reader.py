"""平台 MCP 源码读取——批量读取筛选后的文件。"""

from typing import Any
from uuid import UUID

from app.clients.mcp_client import ReadOnlyMcpClient
from app.schemas.platform_mcp.source_file import (
    SelectedSourceFileBatch,
    SourceFileBatch,
    SourceFileRef,
)


class SourceReader:
    """批量读取源码文件。"""

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
        """读取 SelectedSourceFileBatch 中的所有文件源码。"""
        files: list[SourceFileRef] = []
        remaining = self._max_chars

        for path in selection.paths:
            result = await self._client.call_tool(
                "devcollab.code.read",
                {
                    "workspaceId": str(workspace_id),
                    "repositoryId": str(repository_id),
                    "path": path,
                },
                "delegated",
            )
            content = str(result.get("content", ""))
            truncated = bool(result.get("truncated", False))
            if len(content) > remaining:
                content = content[:remaining]
                truncated = True
            remaining -= len(content)

            files.append(SourceFileRef(
                file_path=str(result.get("path", path)),
                language=str(result.get("language") or ""),
                content=content,
                size_bytes=max(0, int(result.get("sizeBytes") or 0)),
                truncated=truncated,
            ))

        return SourceFileBatch(
            repository_id=str(repository_id),
            revision=selection.revision,
            files=tuple(files),
            total_count=len(files),
        )
