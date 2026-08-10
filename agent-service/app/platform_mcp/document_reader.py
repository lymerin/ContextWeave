"""Platform MCP document reader."""

from uuid import UUID

from app.clients.mcp_client import ReadOnlyMcpClient
from app.schemas.platform_mcp.document import (
    DocumentBlock,
    DocumentCandidate,
    DocumentStructure,
)


_MAX_BATCH_ITEMS = 100


class DocumentReader:
    """Read document structures and document candidates."""

    def __init__(
        self,
        client: ReadOnlyMcpClient,
        *,
        batch_tools_enabled: bool = False,
    ) -> None:
        self._client = client
        self._batch_tools_enabled = batch_tools_enabled

    async def read_structures(
        self,
        workspace_id: UUID,
        document_ids: list[UUID],
    ) -> list[DocumentStructure]:
        if not document_ids:
            return []
        if self._batch_tools_enabled and len(document_ids) > _MAX_BATCH_ITEMS:
            raise ValueError("documentIds exceeds the batch tool limit")
        if self._batch_tools_enabled:
            result = await self._client.call_tool(
                "devcollab.document.get_structure_batch",
                {
                    "workspaceId": str(workspace_id),
                    "documentIds": [str(doc_id) for doc_id in document_ids],
                    "includeBlockContent": True,
                },
                "delegated",
            )
            return [
                self._structure_from_result(item, fallback_id=document_ids[index])
                for index, item in enumerate(result.get("structures", []))
            ]

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
            results.append(self._structure_from_result(result, fallback_id=doc_id))
        return results

    async def find_candidates(
        self,
        workspace_id: UUID,
        repository_id: UUID,
        file_path: str,
        limit: int = 5,
    ) -> list[DocumentCandidate]:
        if self._batch_tools_enabled:
            by_path = await self._find_candidates_batch(
                workspace_id, repository_id, [file_path], limit
            )
            return by_path.get(file_path.replace("\\", "/"), [])

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
        return self._candidates_from_items(result.get("candidates", []))

    async def locate_documents(
        self,
        workspace_id: UUID,
        repository_id: UUID,
        file_paths: list[str],
    ) -> list[DocumentCandidate]:
        if not file_paths:
            return []
        if self._batch_tools_enabled and len(file_paths) > _MAX_BATCH_ITEMS:
            raise ValueError("paths exceeds the batch tool limit")

        seen: set[UUID] = set()
        candidates: list[DocumentCandidate] = []
        if self._batch_tools_enabled:
            batch = await self._find_candidates_batch(
                workspace_id, repository_id, file_paths, 5
            )
            candidate_groups = [
                batch.get(path.replace("\\", "/"), []) for path in file_paths
            ]
        else:
            candidate_groups = [
                await self.find_candidates(workspace_id, repository_id, path)
                for path in file_paths
            ]

        for group in candidate_groups:
            for candidate in group:
                if candidate.document_id not in seen:
                    seen.add(candidate.document_id)
                    candidates.append(candidate)
        return candidates

    async def _find_candidates_batch(
        self,
        workspace_id: UUID,
        repository_id: UUID,
        file_paths: list[str],
        limit: int,
    ) -> dict[str, list[DocumentCandidate]]:
        if not file_paths:
            return {}
        result = await self._client.call_tool(
            "devcollab.document.find_candidates_batch",
            {
                "workspaceId": str(workspace_id),
                "repositoryId": str(repository_id),
                "paths": file_paths,
                "limit": limit,
            },
            "delegated",
        )
        raw = result.get("candidatesByPath") or {}
        if not isinstance(raw, dict):
            return {}
        return {
            str(path).replace("\\", "/"): self._candidates_from_items(items)
            for path, items in raw.items()
        }

    def _structure_from_result(
        self,
        result: dict,
        *,
        fallback_id: UUID,
    ) -> DocumentStructure:
        blocks = [
            DocumentBlock(
                block_id=UUID(block["blockId"]),
                block_type=str(block.get("blockType", "PARAGRAPH")),
                sort_order=int(block.get("sortOrder") or 0),
                version=(
                    int(block["version"])
                    if block.get("version") is not None else 1
                ),
                plain_text=block.get("plainText"),
                content=block.get("content"),
            )
            for block in result.get("blocks", [])
        ]
        return DocumentStructure(
            document_id=UUID(result.get("documentId", str(fallback_id))),
            title=str(result.get("title") or ""),
            document_type=result.get("documentType"),
            blocks=blocks,
        )

    def _candidates_from_items(self, items: object) -> list[DocumentCandidate]:
        return [
            DocumentCandidate(
                document_id=UUID(candidate["documentId"]),
                title=str(candidate.get("title", "")),
                existing_binding_count=int(candidate.get("existingBindingCount") or 0),
                matched_block_ids=[
                    UUID(block_id)
                    for block_id in (candidate.get("matchedBlockIds") or [])
                ],
            )
            for candidate in (items if isinstance(items, list) else [])
            if candidate.get("documentId")
        ]
