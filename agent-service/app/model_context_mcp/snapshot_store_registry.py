"""快照仓库——注册、获取、释放、TTL 清理。"""

from __future__ import annotations

import time
from collections import OrderedDict

from app.schemas.model_context.snapshot import ContextSnapshot


class SnapshotStoreRegistry:
    """按 context_id 管理 ContextSnapshot 生命周期。

    register  注册快照。
    acquire   标记分析会话活跃（防止 TTL 清理）。
    release   解除活跃标记。
    get       按 ID 获取快照。
    """

    def __init__(self, ttl_seconds: int = 3600) -> None:
        self._ttl = ttl_seconds
        self._store: OrderedDict[str, _Entry] = OrderedDict()

    def register(self, snapshot: ContextSnapshot) -> None:
        self._evict()
        key = snapshot.context_id
        if key not in self._store:
            self._store[key] = _Entry(snapshot)
        while len(self._store) > 100:
            oldest = next(iter(self._store))
            if self._store[oldest].active_sessions == 0:
                del self._store[oldest]
            else:
                break

    def acquire(self, context_id: str, analysis_id: str) -> ContextSnapshot | None:
        entry = self._store.get(context_id)
        if entry is None:
            return None
        entry.active_sessions.add(analysis_id)
        return entry.snapshot

    def release(self, context_id: str, analysis_id: str) -> None:
        entry = self._store.get(context_id)
        if entry is None:
            return
        entry.active_sessions.discard(analysis_id)

    def get(self, context_id: str) -> ContextSnapshot | None:
        entry = self._store.get(context_id)
        return entry.snapshot if entry else None

    def _evict(self) -> None:
        now = time.monotonic()
        expired = [
            k for k, e in self._store.items()
            if e.active_sessions == 0 and now - e.registered_at > self._ttl
        ]
        for k in expired:
            del self._store[k]


class _Entry:
    def __init__(self, snapshot: ContextSnapshot) -> None:
        self.snapshot = snapshot
        self.registered_at = time.monotonic()
        self.active_sessions: set[str] = set()
