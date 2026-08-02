"""入口收集与范围圈定——第 2b 层处理器。

两条路径:
  build_file_scopes()   直接从 AtomCatalog 构造范围（单文件/少量文件分析）。
                        不依赖关系图，文件中的所有顶层符号即为入口，
                        类方法自动归入所属类的范围。

  discover_scopes()     从 RepositoryCodeGraph 检测 HTTP 路由入口并沿调用图
                        BFS 扩展（项目初始化时的大规模跨文件分析）。
"""

from __future__ import annotations

import hashlib
from collections import defaultdict, deque
from typing import Any

from app.schemas.ast_atom import AtomCatalog, SymbolAtom, SymbolKind
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


# ── 直接范围构造（单文件/少量文件，不依赖关系图）─────────────────────────


def build_file_scopes(catalog: AtomCatalog) -> tuple[SemanticScope, ...]:
    """直接从 AtomCatalog 构造语义范围，不依赖关系图或 BFS 扩展。

    每个顶层类或公开函数成为一个入口，类方法自动归入所属类的范围。
    适用于 CURRENT_FILE_ANALYSIS 和 SEMANTIC_ANALYSIS 路径——
    文件边界已经划好了，不需要再"发现"范围。
    """
    # 按文件分组
    by_file: dict[str, list[SymbolAtom]] = {}
    for sym in catalog.symbols:
        f = _file_from_key(sym.symbol_key)
        if f not in by_file:
            by_file[f] = []
        by_file[f].append(sym)

    scopes: list[SemanticScope] = []
    for file_path, symbols in by_file.items():
        # 收集入口：顶层公开类 + 公开函数
        entries: list[EntryPoint] = []
        top_level_ids: set[str] = set()
        for sym in symbols:
            if _is_private_name(sym.name):
                continue
            if sym.parent_qualified is not None:
                continue  # 方法/类方法，归入所属类的范围
            if sym.kind == SymbolKind.CLASS:
                label = f"model:{sym.qualified_name}" if sym.is_pydantic else sym.qualified_name
                entries.append(EntryPoint(
                    symbol_key=sym.symbol_key,
                    kind=EntryKind.PUBLIC_METHOD,
                    label=label,
                ))
                top_level_ids.add(sym.symbol_key)
            elif sym.kind in (SymbolKind.FUNCTION, SymbolKind.ASYNC_FUNCTION):
                entries.append(EntryPoint(
                    symbol_key=sym.symbol_key,
                    kind=EntryKind.PUBLIC_METHOD,
                    label=f"fn:{sym.qualified_name}",
                ))
                top_level_ids.add(sym.symbol_key)

        # 如果没有顶层入口（极罕见：全是私有函数或只有方法），回退到方法级
        if not entries:
            for sym in symbols:
                if _is_private_name(sym.name):
                    continue
                if sym.kind in (SymbolKind.METHOD, SymbolKind.CLASS_METHOD):
                    entries.append(EntryPoint(
                        symbol_key=sym.symbol_key,
                        kind=EntryKind.PUBLIC_METHOD,
                        label=f"method:{sym.qualified_name}",
                    ))
                    top_level_ids.add(sym.symbol_key)

        if not entries:
            continue

        # 构建成员列表
        entry_keys = {e.symbol_key for e in entries}
        members: list[ScopeMember] = []
        for sym in symbols:
            role: str
            distance: int
            entry_paths: tuple[str, ...]

            if sym.symbol_key in entry_keys:
                role = MemberRole.ENTRY
                distance = 0
                entry_paths = (sym.symbol_key,)
            elif sym.parent_qualified is not None:
                # 方法是某个入口类的成员 → 找到所属入口
                parent_key = _parent_symbol_key(sym, catalog)
                if parent_key and parent_key in entry_keys:
                    role = MemberRole.DIRECT_CALLEE
                    distance = 1
                    entry_paths = (parent_key,)
                else:
                    role = MemberRole.SHARED_DEPENDENCY
                    distance = 2
                    entry_paths = ()
            else:
                role = MemberRole.SHARED_DEPENDENCY
                distance = 2
                entry_paths = ()

            members.append(ScopeMember(
                symbol_key=sym.symbol_key,
                role=role,
                distance=distance,
                entry_paths=entry_paths,
            ))

        scope_id = _hash_id("scope", file_path)
        scopes.append(SemanticScope(
            scope_id=scope_id,
            entries=tuple(entries),
            members=tuple(members),
            related_files=(file_path,),
        ))

    return tuple(scopes)


def _parent_symbol_key(method: SymbolAtom, catalog: AtomCatalog) -> str | None:
    """查找 method 所属的顶层类的 symbol_key。"""
    if method.parent_qualified is None:
        return None
    for sym in catalog.symbols:
        if sym.qualified_name == method.parent_qualified:
            return sym.symbol_key
    return None


# ── 关系图范围发现（项目初始化，需 BFS 扩展）─────────────────────────────


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
    """检测所有业务入口。

    优先级:
      1. HTTP 路由（@router.get/post/...）
      2. 顶层公开类（Pydantic 模型、dataclass、普通类）
      3. 顶层公开函数（模块级函数，不含私有 _ 前缀）
      4. 公开方法（类的 public 方法）

    回退逻辑保证 schemas.py 这类纯模型文件也能生成语义范围。
    """
    entries: list[EntryPoint] = []

    for sym in graph.catalog.symbols:
        # 优先级 1: HTTP 路由
        if sym.http_method and sym.http_path:
            entries.append(EntryPoint(
                symbol_key=sym.symbol_key,
                kind=EntryKind.HTTP_ROUTE,
                label=f"{sym.http_method} {sym.http_path}",
            ))

    # 如果已有 HTTP 路由入口，不再回退 —— HTTP 路由是更强的入口信号
    if entries:
        return entries

    # 回退: 没有 HTTP 路由时，顶层类/函数都是语义入口
    for sym in graph.catalog.symbols:
        if _is_private_name(sym.name):
            continue
        if sym.kind == SymbolKind.CLASS:
            label = sym.qualified_name
            if sym.is_pydantic:
                label = f"model:{sym.qualified_name}"
            entries.append(EntryPoint(
                symbol_key=sym.symbol_key,
                kind=EntryKind.PUBLIC_METHOD,
                label=label,
            ))
        elif sym.kind in (SymbolKind.FUNCTION, SymbolKind.ASYNC_FUNCTION):
            # 仅顶层函数（无 parent_qualified）
            if sym.parent_qualified is None:
                entries.append(EntryPoint(
                    symbol_key=sym.symbol_key,
                    kind=EntryKind.PUBLIC_METHOD,
                    label=f"fn:{sym.qualified_name}",
                ))

    # 如果连顶层类/函数都没有（极罕见），回退到公开方法
    if not entries:
        for sym in graph.catalog.symbols:
            if _is_private_name(sym.name):
                continue
            if sym.kind in (SymbolKind.METHOD, SymbolKind.CLASS_METHOD):
                entries.append(EntryPoint(
                    symbol_key=sym.symbol_key,
                    kind=EntryKind.PUBLIC_METHOD,
                    label=f"method:{sym.qualified_name}",
                ))

    return entries


def _is_private_name(name: str) -> bool:
    """Python 私有命名约定: 以 _ 开头但不以 __ 开头的是模块私有。"""
    return name.startswith("_") and not name.startswith("__")


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
