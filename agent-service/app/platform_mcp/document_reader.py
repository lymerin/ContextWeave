"""平台 MCP 文档读取——结构 + 候选 + 定位。"""

from uuid import UUID

from app.clients.mcp_client import ReadOnlyMcpClient
from app.schemas.platform_mcp.document import (
    DocumentBlock,
    DocumentCandidate,
    DocumentStructure,
)


class DocumentReader:
    """读取文档结构和候选文档。"""

    def __init__(self, client: ReadOnlyMcpClient) -> None:
        self._client = client

    async def read_structures(
        self,
        workspace_id: UUID,
        document_ids: list[UUID],
    ) -> list[DocumentStructure]:
        """批量读取文档结构。"""
        results: list[DocumentStructure] = []
        for doc_id in document_ids:
            result = await self._client.call_tool(
                "devcollab.document.get_structure",
                {
                    "workspaceId": str(workspace_id),
                    "documentId": str(doc_id),
                    "includeBlockContent": True,
                },
                "delegated",
            )
            blocks = [
                DocumentBlock(
                    block_id=UUID(b["blockId"]),
                    block_type=str(b.get("blockType", "PARAGRAPH")),
                    sort_order=int(b.get("sortOrder") or 0),
                    version=int(b.get("version") or 1),
                    plain_text=b.get("plainText"),
                    content=b.get("content"),
                )
                for b in result.get("blocks", [])
            ]
            results.append(DocumentStructure(
                document_id=UUID(result.get("documentId", str(doc_id))),
                title=str(result.get("title") or ""),
                document_type=result.get("documentType"),
                blocks=blocks,
            ))
        return results

    async def find_candidates(
        self,
        workspace_id: UUID,
        repository_id: UUID,
        file_path: str,
        limit: int = 5,
    ) -> list[DocumentCandidate]:
        """按文件路径查找候选文档。"""
        result = await self._client.call_tool(
            "devcollab.document.find_candidates",
            {
                "workspaceId": str(workspace_id),
                "repositoryId": str(repository_id),
                "filePath": file_path,
                "limit": limit,
            },
            "delegated",
        )
        return [
            DocumentCandidate(
                document_id=UUID(c["documentId"]),
                title=str(c.get("title", "")),
            )
            for c in result.get("candidates", [])
            if c.get("documentId")
        ]

    async def locate_documents(
        self,
        workspace_id: UUID,
        repository_id: UUID,
        file_paths: list[str],
    ) -> list[DocumentCandidate]:
        """为一批文件定位候选文档（去重）。"""
        seen: set[UUID] = set()
        candidates: list[DocumentCandidate] = []
        for path in file_paths:
            for c in await self.find_candidates(workspace_id, repository_id, path):
                if c.document_id not in seen:
                    seen.add(c.document_id)
                    candidates.append(c)
        return candidates
