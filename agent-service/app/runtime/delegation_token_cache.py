import asyncio
import time
from dataclasses import dataclass
from threading import RLock
from typing import Awaitable, Callable
from uuid import UUID


ExchangeFunc = Callable[..., Awaitable[str]]


@dataclass(frozen=True)
class _CachedToken:
    token: str
    expires_at: float


class DelegationTokenCache:
    MAX_TTL_SECONDS = 300.0

    def __init__(
        self,
        ttl_seconds: float,
        *,
        clock: Callable[[], float] | None = None,
    ) -> None:
        if ttl_seconds <= 0:
            raise ValueError("ttl_seconds must be positive")
        self._ttl_seconds = min(float(ttl_seconds), self.MAX_TTL_SECONDS)
        self._clock = clock or time.monotonic
        self._tokens: dict[str, _CachedToken] = {}
        self._locks: dict[str, asyncio.Lock] = {}
        self._guard = RLock()

    async def get(
        self,
        *,
        delegation_id: UUID | str,
        job_id: UUID | str,
        exchange: ExchangeFunc,
    ) -> str:
        key = str(delegation_id)
        cached = self._read_valid(key)
        if cached is not None:
            return cached

        lock = self._key_lock(key)
        async with lock:
            cached = self._read_valid(key)
            if cached is not None:
                return cached
            token = await exchange(delegation_id=delegation_id, job_id=job_id)
            with self._guard:
                self._tokens[key] = _CachedToken(
                    token=token,
                    expires_at=self._clock() + self._ttl_seconds,
                )
            return token

    def invalidate(self, delegation_id: UUID | str) -> None:
        with self._guard:
            self._tokens.pop(str(delegation_id), None)

    def clear(self) -> None:
        with self._guard:
            self._tokens.clear()

    @property
    def ttl_seconds(self) -> float:
        return self._ttl_seconds

    def _read_valid(self, key: str) -> str | None:
        now = self._clock()
        with self._guard:
            cached = self._tokens.get(key)
            if cached is None:
                return None
            if cached.expires_at <= now:
                self._tokens.pop(key, None)
                return None
            return cached.token

    def _key_lock(self, key: str) -> asyncio.Lock:
        with self._guard:
            lock = self._locks.get(key)
            if lock is None:
                lock = asyncio.Lock()
                self._locks[key] = lock
            return lock
