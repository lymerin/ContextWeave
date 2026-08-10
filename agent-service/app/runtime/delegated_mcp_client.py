from typing import Any
from uuid import UUID

import httpx

from app.clients.delegation_client import DelegationClient
from app.clients.mcp_client import McpClientError, ReviewMcpClient, _authorization_header
from app.runtime.delegation_token_cache import DelegationTokenCache
from app.schemas.plans import AgentPlan


class DelegatedMcpClient:
    def __init__(
        self,
        client: ReviewMcpClient,
        delegation_client: DelegationClient,
        *,
        delegation_id: UUID,
        job_id: UUID,
        revision: str,
        knowledge_core_base_url: str | None = None,
        request_timeout_seconds: float = 30,
        token_cache: DelegationTokenCache | None = None,
    ) -> None:
        self._client = client
        self._delegation_client = delegation_client
        self._delegation_id = delegation_id
        self._job_id = job_id
        self._revision = revision
        self._knowledge_core_base_url = (
            knowledge_core_base_url.rstrip("/")
            if knowledge_core_base_url
            else None
        )
        self._request_timeout_seconds = request_timeout_seconds
        self._token_cache = token_cache

    async def call_tool(
        self, name: str, arguments: dict[str, Any], _authorization: str
    ) -> dict[str, Any]:
        authorization = await self._delegated_authorization()
        result = await self._client.call_tool(name, arguments, authorization)
        if name == "devcollab.code.read":
            actual = str(result.get("commitHash") or "")
            if actual.lower() != self._revision.lower():
                raise McpClientError(
                    "REVISION_CHANGED",
                    "Repository revision changed after the Agent job was created",
                )
        return result

    async def read_code_details(
        self,
        *,
        workspace_id: str,
        repository_id: str,
        path: str,
    ) -> dict[str, Any]:
        if self._knowledge_core_base_url is None:
            return {}
        authorization = await self._delegated_authorization()
        try:
            async with httpx.AsyncClient(
                timeout=self._request_timeout_seconds,
                headers={"Authorization": _authorization_header(authorization)},
            ) as client:
                response = await client.get(
                    (
                        f"{self._knowledge_core_base_url}/api/v1/workspaces/"
                        f"{workspace_id}/git/repositories/{repository_id}/source"
                    ),
                    params={"path": path},
                )
        except httpx.HTTPError as exc:
            raise McpClientError(
                "MCP_UNAVAILABLE", "Knowledge Core source details request failed"
            ) from exc
        if response.status_code in {401, 403}:
            raise McpClientError(
                "MCP_PERMISSION_DENIED", "Source details access was denied"
            )
        if response.status_code >= 500:
            raise McpClientError(
                "MCP_UNAVAILABLE", "Knowledge Core source details are unavailable"
            )
        if response.status_code >= 400:
            raise McpClientError(
                "INVALID_REQUEST", "Knowledge Core rejected the source details request"
            )
        payload = response.json()
        if not isinstance(payload, dict):
            raise McpClientError(
                "MCP_UNAVAILABLE", "Knowledge Core returned invalid source details"
            )
        actual = str(payload.get("commitSha") or "")
        if actual.lower() != self._revision.lower():
            raise McpClientError(
                "REVISION_CHANGED",
                "Repository revision changed after the Agent job was created",
            )
        return payload

    async def submit_document_change(
        self,
        plan: AgentPlan,
        *,
        workspace_id: str,
        run_id: str,
        authorization: str,
    ) -> dict[str, Any]:
        del authorization
        delegated = await self._delegated_authorization()
        return await self._client.submit_document_change(
            plan,
            workspace_id=workspace_id,
            run_id=run_id,
            authorization=delegated,
        )

    async def _delegated_authorization(self) -> str:
        if self._token_cache is None:
            return await self._delegation_client.exchange(
                delegation_id=self._delegation_id,
                job_id=self._job_id,
            )
        return await self._token_cache.get(
            delegation_id=self._delegation_id,
            job_id=self._job_id,
            exchange=self._delegation_client.exchange,
        )
