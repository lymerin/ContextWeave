"""上下文快照模型——不可变的代码上下文只读副本。

ContextSnapshot   ShapedCodeContext 的冻结快照。
SnapshotManifest  快照中必须交付的内容清单。
"""

from __future__ import annotations

import hashlib
from collections.abc import Mapping
from dataclasses import dataclass, field
from datetime import UTC, datetime
from types import MappingProxyType
from uuid import UUID

from app.schemas.repository_graph import Relation
from app.schemas.shaped_context import (
    AtomRef,
    ShapedCodeContext,
    SourceChunk,
    StructureBlock,
)


@dataclass(frozen=True)
class SnapshotManifest:
    """快照内容清单——coverage 校验的基准。"""

    required_atom_ids: frozenset[str] = frozenset()
    required_relation_ids: frozenset[str] = frozenset()
    required_source_chunk_ids: frozenset[str] = frozenset()
    required_block_ids: frozenset[str] = frozenset()
    atom_count: int = 0
    relation_count: int = 0
    source_chunk_count: int = 0
    block_count: int = 0
    trimmed_atom_count: int = 0
    snapshot_hash: str = ""
    structural_fingerprint: str = ""


@dataclass(frozen=True)
class ContextSnapshot:
    """ShapedCodeContext 的不可变只读快照。

    创建后所有字段不可修改。内部集合全部使用不可变类型。
    """

    context_id: str
    repository_id: UUID
    revision: str
    snapshot_hash: str
    manifest: SnapshotManifest
    frozen_at: str

    # 不可变数据
    atoms: tuple[AtomRef, ...] = ()
    chunks: tuple[SourceChunk, ...] = ()
    structure_blocks: tuple[StructureBlock, ...] = ()
    relations: tuple[Relation, ...] = ()
    entry_paths: tuple[str, ...] = ()

    # 不可变索引
    atom_by_id: Mapping[str, AtomRef] = field(default_factory=dict)
    chunk_by_id: Mapping[str, SourceChunk] = field(default_factory=dict)
    block_by_id: Mapping[str, StructureBlock] = field(default_factory=dict)
    relation_by_source: Mapping[str, tuple[Relation, ...]] = field(default_factory=dict)
    # 权威 symbol_key → atom_id 映射。模型输出引用 symbol_key，代码层经此绑定回
    # atom_id。全链路唯一权威源，替代各处独立构建的反向映射。
    atom_by_symbol: Mapping[str, str] = field(default_factory=dict)

    @property
    def scope_id(self) -> str:
        return self.context_id

    @property
    def atom_count(self) -> int:
        return len(self.atoms)

    @property
    def block_count(self) -> int:
        return len(self.structure_blocks)


def freeze(shaped: ShapedCodeContext, context_id: str) -> ContextSnapshot:
    """冻结 ShapedCodeContext 为不可变 ContextSnapshot。"""
    now = datetime.now(UTC).isoformat()

    # 构建索引
    atom_by_id = MappingProxyType({a.atom_id: a for a in shaped.atoms})
    atom_by_symbol = MappingProxyType({a.symbol_key: a.atom_id for a in shaped.atoms})
    chunk_by_id = MappingProxyType({c.chunk_id: c for c in shaped.chunks})
    block_by_id = MappingProxyType({b.block_id: b for b in shaped.structure_blocks})

    # 关系索引
    rel_source: dict[str, list[Relation]] = {}
    for r in shaped.relations:
        rel_source.setdefault(r.source_atom_id, []).append(r)
    relation_by_source = MappingProxyType({
        k: tuple(v) for k, v in rel_source.items()
    })

    # structural_fingerprint
    fp_raw = "\0".join(
        sorted(a.atom_id for a in shaped.atoms)
        + sorted(c.chunk_id for c in shaped.chunks)
        + [shaped.revision]
    )
    fingerprint = hashlib.sha256(fp_raw.encode()).hexdigest()[:32]
    snapshot_hash = hashlib.sha256(
        f"{shaped.repository_id}\0{shaped.revision}\0{shaped.scope_id}\0{now}".encode()
    ).hexdigest()[:24]

    # Manifest
    manifest = SnapshotManifest(
        required_atom_ids=frozenset(a.atom_id for a in shaped.atoms),
        required_relation_ids=frozenset(
            f"{r.source_atom_id}:{r.kind}:{r.target_atom_id or r.target_external or ''}"
            for r in shaped.relations
        ),
        required_source_chunk_ids=frozenset(c.chunk_id for c in shaped.chunks),
        required_block_ids=frozenset(b.block_id for b in shaped.structure_blocks),
        atom_count=len(shaped.atoms),
        relation_count=len(shaped.relations),
        source_chunk_count=len(shaped.chunks),
        block_count=len(shaped.structure_blocks),
        trimmed_atom_count=len(shaped.trimmed_atom_ids),
        structural_fingerprint=fingerprint,
    )

    return ContextSnapshot(
        context_id=context_id,
        repository_id=shaped.repository_id,
        revision=shaped.revision,
        snapshot_hash=snapshot_hash,
        manifest=manifest,
        frozen_at=now,
        atoms=shaped.atoms,
        chunks=shaped.chunks,
        structure_blocks=shaped.structure_blocks,
        relations=shaped.relations,
        entry_paths=shaped.entry_paths,
        atom_by_id=atom_by_id,
        atom_by_symbol=atom_by_symbol,
        chunk_by_id=chunk_by_id,
        block_by_id=block_by_id,
        relation_by_source=relation_by_source,
    )
