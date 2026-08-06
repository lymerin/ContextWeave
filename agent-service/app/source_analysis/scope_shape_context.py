"""上下文整形——第 2c 层处理器。

输入  SemanticScope + AtomCatalog + RepositoryCodeGraph + 源码读取函数。
输出  ShapedCodeContext（去重、排序、切块、预算裁剪后的可导航代码上下文）。

核心规则:
  1. 源码去重——重叠行范围不重复展示。
  2. 预算裁剪——按结构距离优先级确定性裁剪；ENTRY/DIRECT_CALLEE（d≤1）强制保留。
  3. 块按入口分组——每个块 = 一个入口的跨文件可达闭包（DS 的主要阅读单位）。
  4. 块 ID 由入口 symbol_key 哈希（跨 revision 稳定）；chunk ID 用实际行范围。
  5. 关系边随快照交付（get_atom_detail 的入边/出边数据源）。
"""

from __future__ import annotations

import hashlib
from collections import defaultdict
from collections.abc import Callable

from app.schemas.ast_atom import AtomCatalog, SymbolAtom
from app.schemas.repository_graph import Relation, RepositoryCodeGraph
from app.schemas.scope import ScopeMember, SemanticScope
from app.schemas.shaped_context import (
    AtomRef,
    ShapedCodeContext,
    SourceChunk,
    StructureBlock,
)

# 每个结构块最多包含的原子数
_MAX_ATOMS_PER_BLOCK = 20
# 快照源码字符预算（与 agent_max_code_chars 默认值一致；由调用方按配置传入）
_DEFAULT_BUDGET_CHARS = 40_000
# 源码字符估算：每行平均字符数（用于裁剪决策，不参与实际切块）
_EST_CHARS_PER_LINE = 35


def shape_context(
    scope: SemanticScope,
    catalog: AtomCatalog,
    source_reader: Callable[[str], str | None],
    graph: RepositoryCodeGraph | None = None,
    *,
    budget_chars: int = _DEFAULT_BUDGET_CHARS,
) -> ShapedCodeContext:
    """将 SemanticScope 整形为 ShapedCodeContext。

    graph 提供跨文件关系边（可为 None，此时快照不带 relations）。
    budget_chars 为快照源码总字符预算，超出按结构距离确定性裁剪。
    """
    by_key: dict[str, SymbolAtom] = {s.symbol_key: s for s in catalog.symbols}

    # 0. 预算裁剪（确定性，按 distance 优先级）
    kept_symbols, trimmed = _apply_budget(scope, by_key, budget_chars)
    kept_keys = frozenset(s.symbol_key for s in kept_symbols)

    # 1. 源码去重切块（只对保留符号）
    chunks = _build_chunks(kept_symbols, source_reader)
    chunk_by_id = {c.chunk_id: c for c in chunks}

    # 2. 原子引用
    atoms = _build_atom_refs(scope, catalog, chunks, kept_keys)

    # 3. 结构块（按入口分组）
    blocks = _build_blocks(scope, atoms, chunk_by_id)

    # 4. 入口路径（只保留未裁剪成员）
    entry_paths = _build_entry_paths(scope, kept_keys)

    # 5. 关系边（只收本范围内原子发起的）
    relations = _scope_relations(graph, {a.atom_id for a in atoms})

    return ShapedCodeContext(
        repository_id=catalog.repository_id,
        revision=catalog.revision,
        scope_id=scope.scope_id,
        atoms=tuple(atoms),
        chunks=chunks,
        structure_blocks=tuple(blocks),
        relations=relations,
        trimmed_atom_ids=tuple(sorted(trimmed)),
        entry_paths=tuple(entry_paths),
    )


# ── 预算裁剪 ────────────────────────────────────────────────────────────────


def _apply_budget(
    scope: SemanticScope,
    by_key: dict[str, SymbolAtom],
    budget_chars: int,
) -> tuple[list[SymbolAtom], list[str]]:
    """按结构距离确定性裁剪。

    distance ≤ 1（ENTRY/DIRECT_CALLEE）强制保留，不参与裁剪；
    其余按 (distance, file, start_line) 排序贪心填充预算；
    超出预算的符号记入 trimmed（仍可经 get_atom_detail 按需读取？否——
    裁剪原子不进入快照，DS 只能看到 trimmed 计数）。
    """
    members = [m for m in scope.members if m.symbol_key in by_key]
    ordered = sorted(
        members,
        key=lambda m: (
            m.distance,
            _file_from_key(m.symbol_key),
            by_key[m.symbol_key].start_line,
        ),
    )
    kept: list[SymbolAtom] = []
    trimmed: list[str] = []
    used = 0
    for member in ordered:
        sym = by_key[member.symbol_key]
        estimate = _estimate_chars(sym)
        # 公开符号（骨架槽位的覆盖基准）强制保留；预算只裁剪私有辅助符号
        public = not _is_private(sym.name)
        if member.distance <= 1 or public or used + estimate <= budget_chars:
            kept.append(sym)
            used += estimate
        else:
            trimmed.append(member.symbol_key)
    return kept, trimmed


def _estimate_chars(sym: SymbolAtom) -> int:
    body_lines = max(0, sym.body_end_line - sym.body_start_line + 1)
    return body_lines * _EST_CHARS_PER_LINE + len(sym.signature)


def _is_private(name: str) -> bool:
    return name.startswith("_") and not name.startswith("__")


# ── 源码去重 ────────────────────────────────────────────────────────────────


def _build_chunks(
    symbols: list[SymbolAtom],
    source_reader: Callable[[str], str | None],
) -> tuple[SourceChunk, ...]:
    """为范围中的所有符号构建去重源码片段。"""
    # 按文件分组
    by_file: dict[str, list[SymbolAtom]] = defaultdict(list)
    for sym in symbols:
        f = _file_from_key(sym.symbol_key)
        if f:
            by_file[f].append(sym)

    chunks: list[SourceChunk] = []
    for file, file_symbols in by_file.items():
        source = source_reader(file)
        if source is None:
            continue
        lines = source.splitlines()

        # 按起始行倒序排序：先处理最内层符号（方法先于类），
        # 外层符号再裁剪掉内层已占用的行，保证方法有自己的 chunk
        file_symbols.sort(key=lambda s: (-s.start_line, s.end_line))

        covered_ranges: list[tuple[int, int]] = []
        for sym in file_symbols:
            start = sym.body_start_line
            end = sym.body_end_line
            if start <= 0 or end <= 0 or start > end:
                start = sym.start_line
                end = sym.end_line

            # 排除已被覆盖的行范围
            actual_start, actual_end = _trim_overlap(
                start, end, covered_ranges
            )
            if actual_start > actual_end:
                continue

            source_text = "\n".join(lines[actual_start - 1: actual_end])
            if not source_text.strip():
                continue

            # chunk_id 使用实际保留的行范围——同文件同起始行的不同符号不冲突
            chunk_id = _hash_id("chk", file, str(actual_start), str(actual_end))
            chunks.append(SourceChunk(
                chunk_id=chunk_id,
                file_path=file,
                start_line=actual_start,
                end_line=actual_end,
                source=source_text,
                covers=(sym.symbol_key,),
            ))
            covered_ranges.append((actual_start, actual_end))
            covered_ranges.sort()

    chunks.sort(key=lambda c: (c.file_path, c.start_line))
    return tuple(chunks)


def _trim_overlap(
    start: int,
    end: int,
    covered: list[tuple[int, int]],
) -> tuple[int, int]:
    """去掉与已覆盖范围的重复部分，返回实际应保留的行范围。"""
    actual_start, actual_end = start, end
    for cov_start, cov_end in covered:
        if cov_end < actual_start or cov_start > actual_end:
            continue
        # 被完全覆盖
        if cov_start <= actual_start and cov_end >= actual_end:
            return (0, -1)  # 空
        # 覆盖了前部
        if cov_start <= actual_start:
            actual_start = cov_end + 1
        # 覆盖了后部
        if cov_end >= actual_end:
            actual_end = cov_start - 1
    return (actual_start, actual_end)


# ── 原子引用 ────────────────────────────────────────────────────────────────


def _build_atom_refs(
    scope: SemanticScope,
    catalog: AtomCatalog,
    chunks: tuple[SourceChunk, ...],
    kept_keys: frozenset[str],
) -> list[AtomRef]:
    """为范围中每个未裁剪成员构建 AtomRef。"""
    by_key = {s.symbol_key: s for s in catalog.symbols}

    # 收集每个 atom 被哪些 chunk 覆盖
    atom_chunks: dict[str, list[str]] = defaultdict(list)
    for ch in chunks:
        for key in ch.covers:
            atom_chunks[key].append(ch.chunk_id)

    refs: list[AtomRef] = []
    for member in scope.members:
        if member.symbol_key not in kept_keys:
            continue
        sym = by_key.get(member.symbol_key)
        if sym is None:
            continue
        refs.append(AtomRef(
            atom_id=sym.atom_id,
            symbol_key=member.symbol_key,
            chunk_ids=tuple(atom_chunks.get(member.symbol_key, ())),
        ))

    refs.sort(key=lambda r: r.symbol_key)
    return refs


# ── 结构块（按入口分组）──────────────────────────────────────────────────────


def _build_blocks(
    scope: SemanticScope,
    atoms: list[AtomRef],
    chunk_by_id: dict[str, SourceChunk],
) -> list[StructureBlock]:
    """按入口分组：每个块 = 一个入口的跨文件可达闭包。共享成员单独成块。

    块 ID 由入口 symbol_key 哈希，跨 revision 稳定；超过 _MAX_ATOMS_PER_BLOCK
    时按 (distance, symbol_key) 顺序追加 _partN 后缀。
    """
    atom_by_key = {a.symbol_key: a for a in atoms}

    entry_members: dict[str, list[ScopeMember]] = defaultdict(list)
    for member in scope.members:
        if member.symbol_key not in atom_by_key:
            continue
        for entry_key in member.entry_paths or ():
            entry_members[entry_key].append(member)

    blocks: list[StructureBlock] = []
    for entry in sorted(scope.entries, key=lambda e: e.symbol_key):
        members = sorted(
            entry_members.get(entry.symbol_key, []),
            key=lambda m: (m.distance, m.symbol_key),
        )
        if not members:
            continue
        base_id = _hash_id("blk", entry.symbol_key)
        for i in range(0, len(members), _MAX_ATOMS_PER_BLOCK):
            sub = members[i: i + _MAX_ATOMS_PER_BLOCK]
            block_id = (
                base_id
                if i == 0
                else f"{base_id}_part{i // _MAX_ATOMS_PER_BLOCK + 1}"
            )
            block_atoms = tuple(a.symbol_key for a in sub)
            block_chunks: list[str] = []
            for a in sub:
                for cid in atom_by_key[a.symbol_key].chunk_ids:
                    if cid not in block_chunks:
                        block_chunks.append(cid)
            min_distance = min(m.distance for m in sub)
            desc = (
                f"入口: {entry.label}。距离 d≤{min_distance}，"
                f"包含 {len(sub)} 个原子。"
                f"源文件: {_files_in_block(block_atoms, atom_by_key, chunk_by_id)}"
            )
            blocks.append(StructureBlock(
                block_id=block_id,
                atoms=block_atoms,
                chunks=tuple(block_chunks),
                entry_distance=min_distance,
                entry_label=entry.label,
                description=desc,
            ))

    # 共享依赖（无入口路径）—— 单独成块，DS 按需读取
    shared = sorted(
        (m for m in scope.members if m.symbol_key in atom_by_key and not m.entry_paths),
        key=lambda m: m.symbol_key,
    )
    if shared:
        block_atoms = tuple(m.symbol_key for m in shared)
        shared_chunks: list[str] = []
        for a in shared:
            for cid in atom_by_key[a.symbol_key].chunk_ids:
                if cid not in shared_chunks:
                    shared_chunks.append(cid)
        blocks.append(StructureBlock(
            block_id=_hash_id("blk", "shared"),
            atoms=block_atoms,
            chunks=tuple(shared_chunks),
            entry_distance=2,
            entry_label="",
            description=(
                f"共享依赖（无单一入口），包含 {len(shared)} 个原子。"
                f"源文件: {_files_in_block(block_atoms, atom_by_key, chunk_by_id)}"
            ),
        ))
    return blocks


def _files_in_block(
    atom_keys: tuple[str, ...],
    atom_by_key: dict[str, AtomRef],
    chunk_by_id: dict[str, SourceChunk],
) -> str:
    files: set[str] = set()
    for key in atom_keys:
        atom = atom_by_key.get(key)
        if atom is None:
            continue
        for cid in atom.chunk_ids:
            ch = chunk_by_id.get(cid)
            if ch:
                files.add(ch.file_path)
    return ", ".join(sorted(files))


# ── 入口路径 ────────────────────────────────────────────────────────────────


def _build_entry_paths(scope: SemanticScope, kept_keys: frozenset[str]) -> list[str]:
    """为每个入口构建到范围成员的结构路径描述（只含未裁剪成员）。"""
    paths: list[str] = []
    for entry in scope.entries:
        reachable: list[str] = []
        for member in scope.members:
            if member.symbol_key not in kept_keys:
                continue
            if member.entry_paths and entry.symbol_key in member.entry_paths:
                reachable.append(
                    f"d={member.distance}:{member.symbol_key.split(':')[-1]}"
                )
        paths.append(
            f"{entry.label} -> {' -> '.join(reachable[:10])}"
            + (f" ... (+{len(reachable) - 10})" if len(reachable) > 10 else "")
        )
    return paths


# ── 关系边 ──────────────────────────────────────────────────────────────────


def _scope_relations(
    graph: RepositoryCodeGraph | None,
    scope_atom_ids: set[str],
) -> tuple[Relation, ...]:
    """只保留范围内原子发起的边（外部/未解析目标原样保留，供 get_atom_detail 出边展示）。"""
    if graph is None:
        return ()
    return tuple(r for r in graph.relations if r.source_atom_id in scope_atom_ids)


# ── 辅助 ────────────────────────────────────────────────────────────────────


def _hash_id(prefix: str, *parts: str) -> str:
    return prefix + "_" + hashlib.sha256(
        "\0".join(parts).encode()
    ).hexdigest()[:24]


def _file_from_key(symbol_key: str) -> str:
    parts = symbol_key.split(":", 2)
    return parts[1] if len(parts) > 1 else ""
