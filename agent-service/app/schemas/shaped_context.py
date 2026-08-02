"""整形后的代码上下文模型——第 2c 层产出。

SourceChunk         一个去重后的源码片段。
AtomRef             整形上下文中的原子引用。
StructureBlock      一个按结构连通性划分的代码块。
ShapedCodeContext   整形完成后的完整代码上下文。
"""

from __future__ import annotations

from dataclasses import dataclass
from uuid import UUID


# ── 源码片段 ────────────────────────────────────────────────────────────────


@dataclass(frozen=True)
class SourceChunk:
    """一个去重后的源码片段。

    chunk_id     片段 ID。
    file_path    所在文件。
    start_line   起始行（1-based）。
    end_line     结束行（1-based，包含）。
    source       实际源码文本。
    covers       覆盖的原子 symbol_key 列表。
    """

    chunk_id: str
    file_path: str
    start_line: int
    end_line: int
    source: str
    covers: tuple[str, ...] = ()  # symbol_key[]


# ── 原子引用 ────────────────────────────────────────────────────────────────


@dataclass(frozen=True)
class AtomRef:
    """整形上下文中的原子引用——只存索引信息，不存完整 SymbolAtom。

    atom_id         对应 SymbolAtom.atom_id，全链路唯一主键。
    symbol_key      对应 SymbolAtom.symbol_key，兼容旧格式。
    chunk_ids       该原子源码覆盖的 SourceChunk ID 列表。
    out_edges       出边（atom_id → kind → target）。
    in_edges        入边（atom_id ← kind ← source）。
    """

    atom_id: str
    symbol_key: str
    chunk_ids: tuple[str, ...] = ()
    out_edges: tuple[str, ...] = ()
    in_edges: tuple[str, ...] = ()


# ── 结构块 ──────────────────────────────────────────────────────────────────


@dataclass(frozen=True)
class StructureBlock:
    """一个按结构连通性划分的代码块。

    block_id       块 ID。
    atoms          块内原子的 symbol_key 列表。
    chunks         块内源码片段的 chunk_id 列表。
    entry_distance 到最近入口的结构距离。
    description    纯事实的结构前言（不含业务语义）。
    """

    block_id: str
    atoms: tuple[str, ...] = ()
    chunks: tuple[str, ...] = ()
    entry_distance: int = 0
    description: str = ""


# ── 整形上下文 ──────────────────────────────────────────────────────────────


@dataclass(frozen=True)
class ShapedCodeContext:
    """整形后的完整代码上下文——第 2c 层最终产出。

    repository_id     仓库 UUID。
    revision          Git commit hash。
    scope_id          来源 SemanticScope ID。
    atoms             AtomRef 索引（symbol_key → AtomRef）。
    chunks            SourceChunk 索引（chunk_id → SourceChunk）。
    structure_blocks  按结构连通性划分的代码块。
    cross_relations   跨块关系（源块 → 目标块）。
    entry_paths       从入口到各原子的可达路径记录。
    """

    repository_id: UUID
    revision: str
    scope_id: str = ""
    atoms: tuple[AtomRef, ...] = ()
    chunks: tuple[SourceChunk, ...] = ()
    structure_blocks: tuple[StructureBlock, ...] = ()
    cross_relations: tuple[str, ...] = ()
    entry_paths: tuple[str, ...] = ()

    @property
    def atom_count(self) -> int:
        return len(self.atoms)

    @property
    def chunk_count(self) -> int:
        return len(self.chunks)

    @property
    def block_count(self) -> int:
        return len(self.structure_blocks)
