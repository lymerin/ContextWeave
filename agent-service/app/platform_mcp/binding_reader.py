"""平台 MCP 绑定读取。"""

from uuid import UUID

from app.clients.mcp_client import ReadOnlyMcpClient
from app.schemas.platform_mcp.binding import ExistingBinding


class BindingReader:
    """读取已有绑定关系。"""

    def __init__(self, client: ReadOnlyMcpClient) -> None:
        self._client = client

    async def read_for_file(
        self,
        workspace_id: UUID,
        repository_id: UUID,
        file_path: str,
    ) -> list[ExistingBinding]:
        """读取单个文件的已有绑定。"""
        result = await self._client.call_tool(
            "devcollab.binding.list",
            {
                "workspaceId": str(workspace_id),
                "repositoryId": str(repository_id),
                "filePath": file_path,
            },
            "delegated",
        )
        return [_parse(item) for item in result.get("bindings", [])]

    async def read_batch(
        self,
        workspace_id: UUID,
        repository_id: UUID,
        file_paths: list[str],
    ) -> list[ExistingBinding]:
        """批量读取多个文件的已有绑定。"""
        result = await self._client.call_tool(
            "devcollab.binding.list_batch",
            {
                "workspaceId": str(workspace_id),
                "repositoryId": str(repository_id),
                "filePaths": file_paths,
            },
            "delegated",
        )
        bindings: list[ExistingBinding] = []
        for group in result.get("files", []):
            for item in group.get("bindings", []):
                bindings.append(_parse(item))
        return bindings


def _parse(item: dict) -> ExistingBinding:
    return ExistingBinding(
        binding_id=UUID(str(item.get("bindingId", ""))),
        repository_id=item.get("repositoryId"),
        path_pattern=str(item.get("pathPattern", "")),
        document_id=UUID(str(item.get("documentId", ""))),
        document_title=item.get("documentTitle"),
        block_id=UUID(str(item["blockId"])) if item.get("blockId") else None,
        revision=item.get("revision"),
        anchor_kind=item.get("anchorKind"),
        symbol_key=item.get("symbolKey"),
        start_line=item.get("startLine"),
        end_line=item.get("endLine"),
        binding_role=str(item.get("bindingRole", "PRIMARY")),
        binding_ordinal=int(item.get("bindingOrdinal") or 1),
    )
