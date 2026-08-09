import asyncio
from contextlib import AsyncExitStack
from dataclasses import dataclass
from typing import Any, Callable, Protocol

import httpx
from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client

from app.clients.mcp_client import _authorization_header


class _AsyncContextManager(Protocol):
    async def __aenter__(self) -> Any: ...

    async def __aexit__(self, exc_type: Any, exc: Any, tb: Any) -> Any: ...


HttpClientFactory = Callable[[dict[str, str], float], _AsyncContextManager]
TransportFactory = Callable[[str, Any], _AsyncContextManager]
SessionFactory = Callable[[Any, Any], _AsyncContextManager]


@dataclass
class _SessionEntry:
    authorization: str
    stack: AsyncExitStack
    session: Any


class McpSessionPool:
    """Job-scoped MCP session pool kept independent from current call paths."""

    def __init__(
        self,
        base_url: str,
        timeout_seconds: float,
        *,
        http_client_factory: HttpClientFactory | None = None,
        transport_factory: TransportFactory | None = None,
        session_factory: SessionFactory | None = None,
    ) -> None:
        self._base_url = base_url
        self._timeout_seconds = timeout_seconds
        self._http_client_factory = http_client_factory or self._default_http_client
        self._transport_factory = transport_factory or self._default_transport
        self._session_factory = session_factory or self._default_session
        self._entries: dict[str, _SessionEntry] = {}
        self._lock = asyncio.Lock()

    async def get_session(self, job_id: str, authorization: str) -> Any:
        normalized = _authorization_header(authorization)
        async with self._lock:
            entry = self._entries.get(job_id)
            if entry is not None and entry.authorization == normalized:
                return entry.session
            if entry is not None:
                await self._close_entry(job_id, entry)

            entry = await self._create_entry(normalized)
            self._entries[job_id] = entry
            return entry.session

    async def call_tool(
        self,
        job_id: str,
        name: str,
        arguments: dict[str, Any],
        authorization: str,
    ) -> Any:
        session = await self.get_session(job_id, authorization)
        try:
            return await session.call_tool(name, arguments)
        except Exception:
            await self.invalidate(job_id)
            raise

    async def invalidate(self, job_id: str) -> None:
        async with self._lock:
            entry = self._entries.pop(job_id, None)
        if entry is not None:
            await self._close_entry(job_id, entry)

    async def close_job(self, job_id: str) -> None:
        await self.invalidate(job_id)

    async def aclose(self) -> None:
        async with self._lock:
            entries = list(self._entries.items())
            self._entries.clear()
        for job_id, entry in entries:
            await self._close_entry(job_id, entry)

    async def _create_entry(self, authorization: str) -> _SessionEntry:
        stack = AsyncExitStack()
        try:
            http_client = await stack.enter_async_context(
                self._http_client_factory(
                    {"Authorization": authorization},
                    self._timeout_seconds,
                )
            )
            transport = await stack.enter_async_context(
                self._transport_factory(self._base_url, http_client)
            )
            read_stream, write_stream, _ = transport
            session = await stack.enter_async_context(
                self._session_factory(read_stream, write_stream)
            )
            await session.initialize()
            return _SessionEntry(
                authorization=authorization,
                stack=stack,
                session=session,
            )
        except Exception:
            await stack.aclose()
            raise

    async def _close_entry(self, _job_id: str, entry: _SessionEntry) -> None:
        await entry.stack.aclose()

    @staticmethod
    def _default_http_client(headers: dict[str, str], timeout: float) -> _AsyncContextManager:
        return httpx.AsyncClient(headers=headers, timeout=timeout)

    @staticmethod
    def _default_transport(base_url: str, http_client: Any) -> _AsyncContextManager:
        return streamable_http_client(base_url, http_client=http_client)

    @staticmethod
    def _default_session(read_stream: Any, write_stream: Any) -> _AsyncContextManager:
        return ClientSession(read_stream, write_stream)
