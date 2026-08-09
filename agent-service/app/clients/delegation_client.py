from typing import Any, Protocol
from uuid import UUID

import httpx

from app import perf_metrics


class DelegationClientError(RuntimeError):
    def __init__(self, code: str, message: str, *, retryable: bool = False) -> None:
        super().__init__(message)
        self.code = code
        self.retryable = retryable


class DelegationClient(Protocol):
    async def create(
        self,
        *,
        job_id: UUID,
        workspace_id: UUID,
        repository_id: UUID,
        scope_type: str,
        authorization: str,
    ) -> dict[str, Any]: ...

    async def authorize(
        self, *, delegation_id: UUID, job_id: UUID, authorization: str
    ) -> None: ...

    async def exchange(self, *, delegation_id: UUID, job_id: UUID) -> str: ...


class KnowledgeCoreDelegationClient:
    def __init__(self, base_url: str, service_token: str, timeout_seconds: float) -> None:
        self._base_url = base_url.rstrip("/")
        self._service_token = service_token
        self._timeout = timeout_seconds

    async def create(
        self,
        *,
        job_id: UUID,
        workspace_id: UUID,
        repository_id: UUID,
        scope_type: str,
        authorization: str,
    ) -> dict[str, Any]:
        response = await self._request(
            "POST",
            "/api/v1/agent-delegations",
            headers={"Authorization": authorization},
            json={
                "jobId": str(job_id),
                "workspaceId": str(workspace_id),
                "repositoryId": str(repository_id),
                "scopeType": scope_type,
            },
        )
        return self._json(response)

    async def authorize(
        self, *, delegation_id: UUID, job_id: UUID, authorization: str
    ) -> None:
        await self._request(
            "POST",
            f"/api/v1/agent-delegations/{delegation_id}/authorize",
            headers={"Authorization": authorization},
            json={"jobId": str(job_id)},
        )

    async def exchange(self, *, delegation_id: UUID, job_id: UUID) -> str:
        perf_metrics.increment("delegation_exchange_count")
        response = await self._request(
            "POST",
            f"/api/v1/internal/agent-delegations/{delegation_id}/exchange",
            headers={"X-DevCollab-Service-Token": self._service_token},
            json={"jobId": str(job_id)},
        )
        payload = self._json(response)
        token = payload.get("accessToken")
        if not isinstance(token, str) or not token:
            raise DelegationClientError(
                "MCP_UNAVAILABLE", "Delegation exchange returned no token", retryable=True
            )
        return f"Bearer {token}"

    async def _request(self, method: str, path: str, **kwargs: Any) -> httpx.Response:
        try:
            async with httpx.AsyncClient(timeout=self._timeout) as client:
                response = await client.request(method, f"{self._base_url}{path}", **kwargs)
        except httpx.HTTPError as exc:
            raise DelegationClientError(
                "MCP_UNAVAILABLE", "Delegation service is unavailable", retryable=True
            ) from exc
        if response.status_code in {401, 403}:
            raise DelegationClientError(
                "MCP_PERMISSION_DENIED", "Agent delegation is not authorized"
            )
        if response.status_code == 404:
            raise DelegationClientError("INVALID_SCOPE", "Agent delegation was not found")
        if response.status_code >= 500:
            raise DelegationClientError(
                "MCP_UNAVAILABLE", "Delegation service is unavailable", retryable=True
            )
        if response.status_code >= 400:
            payload = self._json(response)
            raise DelegationClientError(
                str(payload.get("code", "INVALID_SCOPE")),
                str(payload.get("message", "Agent delegation request was rejected")),
            )
        return response

    @staticmethod
    def _json(response: httpx.Response) -> dict[str, Any]:
        try:
            payload = response.json()
        except ValueError as exc:
            raise DelegationClientError(
                "MCP_UNAVAILABLE", "Delegation service returned invalid data", retryable=True
            ) from exc
        return payload if isinstance(payload, dict) else {}
