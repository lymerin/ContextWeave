"""平台 MCP 仓库读取——workspace 上下文 + 文件目录。"""

from typing import Any
from uuid import UUID

from app.clients.mcp_client import ReadOnlyMcpClient
from app.schemas.platform_mcp.source_file import RepositoryFileRef
from app.schemas.platform_mcp.workspace import RepositoryRef, WorkspaceContext


class WorkspaceReader:
    """读取仓库上下文和文件目录。"""

    def __init__(self, client: ReadOnlyMcpClient) -> None:
        self._client = client

    async def read_context(
        self,
        workspace_id: UUID,
        repository_id: UUID,
    ) -> WorkspaceContext:
        """读取工作区上下文，校验 repository_id 存在。"""
        result = await self._client.call_tool(
            "devcollab.workspace.get_context",
            {"workspaceId": str(workspace_id)},
            "delegated",
        )
        repos = [
            RepositoryRef(
                repository_id=UUID(r["repositoryId"]),
                name=str(r.get("name", "")),
                default_branch=str(r.get("defaultBranch", "main")),
                last_synced_commit=r.get("lastSyncedCommit"),
            )
            for r in result.get("repositories", [])
        ]
        ctx = WorkspaceContext(workspace_id=workspace_id, repositories=repos)
        if ctx.repository(repository_id) is None:
            raise ValueError(f"repository {repository_id} not in workspace")
        return ctx

    async def list_files(
        self,
        workspace_id: UUID,
        repository_id: UUID,
    ) -> list[RepositoryFileRef]:
        """列出仓库所有文件（元数据，不含源码）。"""
        result = await self._client.call_tool(
            "devcollab.repository.list_files",
            {
                "workspaceId": str(workspace_id),
                "repositoryId": str(repository_id),
                "recursive": True,
            },
            "delegated",
        )
        files: list[RepositoryFileRef] = []
        for item in result.get("files", []):
            path = str(item.get("filePath", ""))
            ext = path.rsplit(".", 1)[-1] if "." in path else ""
            files.append(RepositoryFileRef(
                file_path=path,
                extension=("." + ext) if ext else "",
                size_bytes=max(0, int(item.get("sizeBytes") or 0)),
                language=item.get("language"),
                readable=item.get("readable", True),
            ))
        return files
