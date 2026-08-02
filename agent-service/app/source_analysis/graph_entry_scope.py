"""入口收集与范围圈定——第 2b 层处理器。

输入  RepositoryCodeGraph。
输出  SemanticScope[]（跨文件语义范围）。

处理流程:
  1. 检测入口（HTTP 路由、消息消费者等）
  2. 从每个入口沿调用关系扩展，收集可达符号
  3. 补充参数类型、返回类型依赖
  4. 合并高重叠的入口范围
"""

from __future__ import annotations

import hashlib
from collections import defaultdict, deque
from typing import Any

from app.schemas.ast_atom import SymbolKind
from app.schemas.repository_graph import RelationKind, RepositoryCodeGraph
from app.schemas.scope import (
    EntryKind,
    EntryPoint,
    MemberRole,
    ScopeMember,
    SemanticScope,
)

# 扩展的最大调用深度
_MAX_CALL_DEPTH = 5
# 两个范围合并的最小重叠比例（共享成员 / 较小范围成员数）
_MERGE_OVERLAP_THRESHOLD = 0.6


# ── 入口 ────────────────────────────────────────────────────────────────────


def discover_scopes(graph: RepositoryCodeGraph) -> tuple[SemanticScope, ...]:
    """从 RepositoryCodeGraph 发现所有语义范围。"""
    entries = _detect_entries(graph)
    if not entries:
        return ()

    # 从每个入口展开，得到原始范围
    raw_scopes: list[SemanticScope] = []
    for entry in entries:
        scope = _expand_from_entry(graph, entry)
        if scope.member_count > 0:
            raw_scopes.append(scope)

    # 合并高重叠范围
    merged = _merge_overlapping(raw_scopes)
    return tuple(merged)


# ── 入口检测 ────────────────────────────────────────────────────────────────


def _detect_entries(graph: RepositoryCodeGraph) -> list[EntryPoint]:
    """检测所有业务入口。"""
    entries: list[EntryPoint] = []
    for sym in graph.catalog.symbols:
        if sym.http_method and sym.http_path:
            entries.append(EntryPoint(
                symbol_key=sym.symbol_key,
                kind=EntryKind.HTTP_ROUTE,
                label=f"{sym.http_method} {sym.http_path}",
            ))
        # 未来扩展: MESSAGE_CONSUMER, SCHEDULED_TASK, etc.
    return entries


# ── 从入口展开范围 ──────────────────────────────────────────────────────────


def _expand_from_entry(
    graph: RepositoryCodeGraph,
    entry: EntryPoint,
) -> SemanticScope:
    """从一个入口沿调用关系 BFS 扩展，收集可达符号。"""
    fwd = graph.forward_index
    entry_atom_id = _atom_id_for_key(graph, entry.symbol_key)

    members: dict[str, ScopeMember] = {}
    queue: deque[tuple[str, int]] = deque()
    queue.append((entry_atom_id, 0))
    visited: set[str] = {entry_atom_id}

    while queue:
        current_id, depth = queue.popleft()
        if depth >= _MAX_CALL_DEPTH:
            continue

        out_edges = fwd.get(current_id, ())
        for rel in out_edges:
            if rel.category != "INTERNAL":
                continue
            target = rel.target_atom_id
            if not target or target in visited:
                continue

            if rel.kind in (RelationKind.CALLS, RelationKind.CREATES):
                visited.add(target)
                queue.append((target, depth + 1))
                distance = depth + 1
                role = (
                    MemberRole.DIRECT_CALLEE
                    if distance == 1
                    else MemberRole.INDIRECT_CALLEE
                )
                members[target] = ScopeMember(
                    symbol_key=target,
                    role=role,
                    distance=distance,
                    entry_paths=(entry.symbol_key,),
                )

    # 补充类型依赖
    _collect_type_dependencies(graph, entry_atom_id, entry.symbol_key, members, visited)

    # 入口自身
    members[entry_atom_id] = ScopeMember(
        symbol_key=entry.symbol_key,
        role=MemberRole.ENTRY,
        distance=0,
        entry_paths=(entry.symbol_key,),
    )

    # 边界和未解析
    boundary: set[str] = set()
    unresolved: set[str] = set()
    for aid in visited:
        for rel in fwd.get(aid, ()):
            if rel.category == "BOUNDARY":
                boundary.add(rel.target_external or "")
            elif rel.category == "UNRESOLVED":
                unresolved.add(rel.target_external or "")

    # 收集相关文件
    files: set[str] = set()
    for key in members:
        f = _file_from_key(key)
        if f:
            files.add(f)

    scope_id = _hash_id("scope", entry.symbol_key)
    return SemanticScope(
        scope_id=scope_id,
        entries=(entry,),
        members=tuple(
            sorted(members.values(), key=lambda m: (m.distance, m.symbol_key))
        ),
        boundary=tuple(sorted(boundary)),
        unresolved=tuple(sorted(unresolved)),
        related_files=tuple(sorted(files)),
    )


def _collect_type_dependencies(
    graph: RepositoryCodeGraph,
    entry_atom_id: str,
    entry_symbol_key: str,
    members: dict[str, ScopeMember],
    visited: set[str],
) -> None:
    """从入口出发收集所有类型依赖（参数类型、返回类型）。"""
    fwd = graph.forward_index
    seen = set(visited)
    queue: deque[str] = deque(seen)

    while queue:
        current = queue.popleft()
        for rel in fwd.get(current, ()):
            if rel.category != "INTERNAL":
                continue
            if rel.kind not in (RelationKind.PARAMETER_TYPE, RelationKind.RETURN_TYPE):
                continue
            target = rel.target_atom_id
            if not target or target in seen:
                continue
            seen.add(target)
            queue.append(target)
            members[target] = ScopeMember(
                symbol_key=target,
                role=MemberRole.TYPE_DEPENDENCY,
                distance=members.get(current, ScopeMember("", "", 0)).distance + 1,
                entry_paths=(entry_symbol_key,),
            )


# ── 范围合并 ────────────────────────────────────────────────────────────────


def _merge_overlapping(
    scopes: list[SemanticScope],
) -> list[SemanticScope]:
    """合并共享成员比例超过阈值的范围。"""
    if len(scopes) <= 1:
        return scopes

    merged: list[SemanticScope] = []
    used: set[int] = set()

    for i, a in enumerate(scopes):
        if i in used:
            continue
        keys_a = a.member_keys()
        group = [a]
        used.add(i)
        for j, b in enumerate(scopes):
            if j in used:
                continue
            keys_b = b.member_keys()
            shared = keys_a & keys_b
            smaller = min(len(keys_a), len(keys_b))
            if smaller > 0 and len(shared) / smaller >= _MERGE_OVERLAP_THRESHOLD:
                group.append(b)
                used.add(j)
                keys_a |= keys_b

        if len(group) == 1:
            merged.append(a)
        else:
            merged.append(_merge_group(group))

    return merged


def _merge_group(group: list[SemanticScope]) -> SemanticScope:
    """合并一组高重叠范围。"""
    all_entries: list[EntryPoint] = []
    all_members: dict[str, ScopeMember] = {}
    all_boundary: set[str] = set()
    all_unresolved: set[str] = set()
    all_files: set[str] = set()

    for scope in group:
        all_entries.extend(scope.entries)
        for m in scope.members:
            if m.symbol_key not in all_members or m.distance < all_members[m.symbol_key].distance:
                all_members[m.symbol_key] = ScopeMember(
                    symbol_key=m.symbol_key,
                    role=(
                        MemberRole.SHARED_DEPENDENCY
                        if scope.member_keys() & {s.member_keys() for s in group if s != scope}
                        else m.role
                    ),
                    distance=m.distance,
                    entry_paths=m.entry_paths,
                    is_shared=len(group) > 1,
                )
        all_boundary.update(scope.boundary)
        all_unresolved.update(scope.unresolved)
        all_files.update(scope.related_files)

    # 标记共享成员
    member_keys_sets = [s.member_keys() for s in group]
    for key in all_members:
        count = sum(1 for ks in member_keys_sets if key in ks)
        if count > 1:
            all_members[key] = ScopeMember(
                symbol_key=key,
                role=all_members[key].role,
                distance=all_members[key].distance,
                entry_paths=all_members[key].entry_paths,
                is_shared=True,
            )

    scope_id = _hash_id("scope", *(e.symbol_key for e in sorted(all_entries, key=lambda e: e.symbol_key)))
    return SemanticScope(
        scope_id=scope_id,
        entries=tuple(all_entries),
        members=tuple(
            sorted(all_members.values(), key=lambda m: (m.distance, m.symbol_key))
        ),
        boundary=tuple(sorted(all_boundary)),
        unresolved=tuple(sorted(all_unresolved)),
        related_files=tuple(sorted(all_files)),
    )


# ── 辅助 ────────────────────────────────────────────────────────────────────


def _hash_id(prefix: str, *parts: str) -> str:
    return prefix + "_" + hashlib.sha256(
        "\0".join(parts).encode()
    ).hexdigest()[:24]


def _atom_id_for_key(graph: RepositoryCodeGraph, symbol_key: str) -> str:
    for sym in graph.catalog.symbols:
        if sym.symbol_key == symbol_key:
            return sym.atom_id
    return symbol_key  # fallback


def _file_from_key(symbol_key: str) -> str:
    parts = symbol_key.split(":", 2)
    return parts[1] if len(parts) > 1 else ""
