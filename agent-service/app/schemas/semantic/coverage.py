"""上下文覆盖率报告——Manifest vs 实际交付。"""

from dataclasses import dataclass, field


@dataclass
class ContextCoverageReport:
    """比对 SnapshotManifest 与 analysis_session 累计交付的差异。"""

    context_id: str
    overview_read: bool = False
    delivered_block_ids: set[str] = field(default_factory=set)
    delivered_atom_ids: set[str] = field(default_factory=set)
    delivered_chunk_ids: set[str] = field(default_factory=set)
    delivered_relation_ids: set[str] = field(default_factory=set)

    missing_block_ids: set[str] = field(default_factory=set)
    missing_atom_ids: set[str] = field(default_factory=set)
    missing_chunk_ids: set[str] = field(default_factory=set)
    missing_relation_ids: set[str] = field(default_factory=set)

    @property
    def complete(self) -> bool:
        return (
            self.overview_read
            and not self.missing_block_ids
            and not self.missing_atom_ids
            and not self.missing_chunk_ids
            and not self.missing_relation_ids
        )

    @property
    def summary(self) -> str:
        if self.complete:
            return "complete"
        parts: list[str] = []
        if not self.overview_read:
            parts.append("overview not read")
        if self.missing_block_ids:
            parts.append(f"{len(self.missing_block_ids)} blocks missing")
        if self.missing_atom_ids:
            parts.append(f"{len(self.missing_atom_ids)} atoms missing")
        if self.missing_chunk_ids:
            parts.append(f"{len(self.missing_chunk_ids)} chunks missing")
        if self.missing_relation_ids:
            parts.append(f"{len(self.missing_relation_ids)} relations missing")
        return "; ".join(parts)
