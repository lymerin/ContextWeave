"""上下文整形——第 2c 层处理器。

输入  SemanticScope + AtomCatalog + 源码读取函数。
输出  ShapedCodeContext（去重、排序、切块后的可导航代码上下文）。

核心规则:
  1. 源码去重——重叠行范围不重复展示
  2. 按结构距离切块——同一条执行路径尽量在同一块
  3. 每个块生成纯事实结构前言
"""

from __future__ import annotations

import hashlib
from collections import defaultdict
from collections.abc import Callable
from typing import Any

from app.schemas.ast_atom import AtomCatalog, SymbolAtom, SymbolKind
from app.schemas.scope import MemberRole, SemanticScope
from app.schemas.shaped_context import (
    AtomRef,
    ShapedCodeContext,
    SourceChunk,
    StructureBlock,
)

# 每个结构块最多包含的原子数
_MAX_ATOMS_PER_BLOCK = 20


# ── 入口 ────────────────────────────────────────────────────────────────────


def shape_context(
    scope: SemanticScope,
    catalog: AtomCatalog,
    source_reader: Callable[[str], str | None],
) -> ShapedCodeContext:
    """将 SemanticScope 整形为 ShapedCodeContext。"""
    by_key: dict[str, SymbolAtom] = {s.symbol_key: s for s in catalog.symbols}
    scope_symbols = [
        by_key[k] for k in scope.member_keys() if k in by_key
    ]
    scope_symbols.sort(key=lambda s: (_file_from_key(s.symbol_key), s.start_line))

    # 1. 源码去重
    chunks = _build_chunks(scope_symbols, source_reader)
    chunk_by_id = {c.chunk_id: c for c in chunks}

    # 2. 原子引用
    atoms = _build_atom_refs(scope, catalog, chunks)

    # 3. 按结构距离切块
    blocks = _build_blocks(scope, atoms, chunk_by_id)

    # 4. 入口路径
    entry_paths = _build_entry_paths(scope)

    return ShapedCodeContext(
        repository_id=catalog.repository_id,
        revision=catalog.revision,
        scope_id=scope.scope_id,
        atoms=tuple(atoms),
        chunks=chunks,
        structure_blocks=tuple(blocks),
        entry_paths=tuple(entry_paths),
    )


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

        # 按起始行排序
        file_symbols.sort(key=lambda s: (s.start_line, -s.end_line))

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

            chunk_id = _hash_id("chk", file, str(start), str(end))
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
) -> list[AtomRef]:
    """为范围中每个成员构建 AtomRef。"""
    by_key = {s.symbol_key: s for s in catalog.symbols}

    # 收集每个 atom 被哪些 chunk 覆盖
    atom_chunks: dict[str, list[str]] = defaultdict(list)
    for ch in chunks:
        for key in ch.covers:
            atom_chunks[key].append(ch.chunk_id)

    refs: list[AtomRef] = []
    for member in scope.members:
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


# ── 结构切块 ────────────────────────────────────────────────────────────────


def _build_blocks(
    scope: SemanticScope,
    atoms: list[AtomRef],
    chunk_by_id: dict[str, SourceChunk],
) -> list[StructureBlock]:
    """按结构距离将原子切分为结构块。"""
    # 按距离分组
    by_distance: dict[int, list[AtomRef]] = defaultdict(list)
    for member in scope.members:
        by_distance[member.distance].append(
            next((a for a in atoms if a.symbol_key == member.symbol_key), None)
        )

    blocks: list[StructureBlock] = []
    for dist in sorted(by_distance):
        group = [a for a in by_distance[dist] if a is not None]
        if not group:
            continue

        # 如果该距离原子过多，按 _MAX_ATOMS_PER_BLOCK 切分
        for i in range(0, len(group), _MAX_ATOMS_PER_BLOCK):
            sub = group[i: i + _MAX_ATOMS_PER_BLOCK]
            block_atoms = tuple(a.symbol_key for a in sub)
            block_chunks: list[str] = []
            for a in sub:
                for cid in a.chunk_ids:
                    if cid not in block_chunks:
                        block_chunks.append(cid)

            block_id = _hash_id("blk", str(dist), str(i))
            # 纯事实结构前言
            entry_str = ", ".join(
                f"{e.label}" for e in scope.entries
            )
            desc = (
                f"结构距离 d={dist}，包含 {len(sub)} 个原子。"
                f"入口: {entry_str}。"
                f"源文件: {_files_in_block(sub, chunk_by_id)}"
            )
            blocks.append(StructureBlock(
                block_id=block_id,
                atoms=block_atoms,
                chunks=tuple(block_chunks),
                entry_distance=dist,
                description=desc,
            ))

    return blocks


def _files_in_block(
    atoms: list[AtomRef],
    chunk_by_id: dict[str, SourceChunk],
) -> str:
    files: set[str] = set()
    for a in atoms:
        for cid in a.chunk_ids:
            ch = chunk_by_id.get(cid)
            if ch:
                files.add(ch.file_path)
    return ", ".join(sorted(files))


# ── 入口路径 ────────────────────────────────────────────────────────────────


def _build_entry_paths(scope: SemanticScope) -> list[str]:
    """为每个入口构建到范围成员的结构路径描述。"""
    paths: list[str] = []
    for entry in scope.entries:
        reachable: list[str] = []
        for member in scope.members:
            if member.entry_paths and entry.symbol_key in member.entry_paths:
                reachable.append(
                    f"d={member.distance}:{member.symbol_key.split(':')[-1]}"
                )
        paths.append(
            f"{entry.label} -> {' -> '.join(reachable[:10])}"
            + (f" ... (+{len(reachable) - 10})" if len(reachable) > 10 else "")
        )
    return paths


# ── 辅助 ────────────────────────────────────────────────────────────────────


def _hash_id(prefix: str, *parts: str) -> str:
    return prefix + "_" + hashlib.sha256(
        "\0".join(parts).encode()
    ).hexdigest()[:24]


def _file_from_key(symbol_key: str) -> str:
    parts = symbol_key.split(":", 2)
    return parts[1] if len(parts) > 1 else ""
