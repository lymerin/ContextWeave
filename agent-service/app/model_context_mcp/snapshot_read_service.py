"""快照读取服务——五种只读查询，无状态、幂等。"""

from dataclasses import dataclass, field

from app.schemas.model_context.snapshot import ContextSnapshot
from app.schemas.shaped_context import SourceChunk


@dataclass
class OverviewResult:
    context_id: str
    revision: str
    atom_count: int
    chunk_count: int
    block_count: int
    entry_paths: tuple[str, ...] = ()
    block_summaries: tuple[str, ...] = ()
    coverage: set[str] = field(default_factory=set)


@dataclass
class BlockResult:
    block_id: str
    atom_ids: tuple[str, ...] = ()
    sources: tuple[SourceChunk, ...] = ()
    description: str = ""
    coverage: set[str] = field(default_factory=set)


@dataclass
class AtomResult:
    symbol_key: str
    sources: tuple[SourceChunk, ...] = ()
    coverage: set[str] = field(default_factory=set)


@dataclass
class PathResult:
    entry_label: str
    steps: tuple[str, ...] = ()
    coverage: set[str] = field(default_factory=set)


@dataclass
class SearchResult:
    query: str
    matches: tuple[str, ...] = ()
    match_count: int = 0
    coverage: set[str] = field(default_factory=set)


class SnapshotReadService:
    """快照只读查询——五种能力。"""

    def __init__(self, snapshot: ContextSnapshot) -> None:
        self._snap = snapshot

    def overview(self) -> OverviewResult:
        """范围总览。"""
        return OverviewResult(
            context_id=self._snap.context_id,
            revision=self._snap.revision,
            atom_count=self._snap.atom_count,
            chunk_count=len(self._snap.chunks),
            block_count=self._snap.block_count,
            entry_paths=self._snap.entry_paths,
            block_summaries=tuple(
                f"{b.block_id}: d={b.entry_distance} atoms={len(b.atoms)}"
                for b in self._snap.structure_blocks
            ),
        )

    def get_block(self, block_id: str) -> BlockResult | None:
        """读取结构块。"""
        block = self._snap.block_by_id.get(block_id)
        if block is None:
            return None

        sources: list[SourceChunk] = []
        for cid in block.chunks:
            ch = self._snap.chunk_by_id.get(cid)
            if ch:
                sources.append(ch)

        coverage = set(block.atoms)
        coverage.update(c.chunk_id for c in sources)
        coverage.add(block_id)

        return BlockResult(
            block_id=block_id,
            atom_ids=block.atoms,
            sources=tuple(sources),
            description=block.description,
            coverage=coverage,
        )

    def get_atom(self, symbol_key: str) -> AtomResult | None:
        """读取原子详情。"""
        atom = self._snap.atom_by_id.get(symbol_key)
        if atom is None:
            return None

        sources: list[SourceChunk] = []
        for cid in atom.chunk_ids:
            ch = self._snap.chunk_by_id.get(cid)
            if ch:
                sources.append(ch)

        coverage = {symbol_key}
        coverage.update(c.chunk_id for c in sources)

        return AtomResult(
            symbol_key=symbol_key,
            sources=tuple(sources),
            coverage=coverage,
        )

    def trace_path(self, entry_label: str) -> list[PathResult]:
        """追踪入口路径。"""
        results: list[PathResult] = []
        for path in self._snap.entry_paths:
            if entry_label in path:
                steps = tuple(s.strip() for s in path.split("->"))
                results.append(PathResult(
                    entry_label=entry_label,
                    steps=steps,
                    coverage=set(steps),
                ))
        return results

    def search(self, query: str) -> SearchResult:
        """按名称搜索符号。"""
        q = query.lower()
        matches = tuple(
            key for key in self._snap.atom_by_id
            if q in key.lower()
        )[:50]
        return SearchResult(
            query=query,
            matches=matches,
            match_count=len(matches),
            coverage=set(matches[:20]),
        )
