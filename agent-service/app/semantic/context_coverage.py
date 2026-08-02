"""上下文覆盖率校验——比对 Manifest 与实际交付。"""

from app.schemas.model_context.snapshot import ContextSnapshot
from app.schemas.semantic.coverage import ContextCoverageReport


class CoverageTracker:
    """追踪 DeepSeek 的读取进度。"""

    def __init__(self, snapshot: ContextSnapshot) -> None:
        self._snap = snapshot
        self._report = ContextCoverageReport(
            context_id=snapshot.context_id,
            overview_read=False,
        )

    def mark_overview_read(self) -> None:
        self._report.overview_read = True

    def record(self, coverage: set[str]) -> None:
        """记录一次 MCP 返回中交付的 ID。"""
        m = self._snap.manifest
        for item_id in coverage:
            if item_id in m.required_block_ids:
                self._report.delivered_block_ids.add(item_id)
            if item_id in m.required_atom_ids:
                self._report.delivered_atom_ids.add(item_id)
            if item_id in m.required_source_chunk_ids:
                self._report.delivered_chunk_ids.add(item_id)
            if item_id in m.required_relation_ids:
                self._report.delivered_relation_ids.add(item_id)

    def compute(self) -> ContextCoverageReport:
        """计算缺失项，返回完整报告。"""
        m = self._snap.manifest
        self._report.missing_block_ids = m.required_block_ids - self._report.delivered_block_ids
        self._report.missing_atom_ids = m.required_atom_ids - self._report.delivered_atom_ids
        self._report.missing_chunk_ids = m.required_source_chunk_ids - self._report.delivered_chunk_ids
        self._report.missing_relation_ids = m.required_relation_ids - self._report.delivered_relation_ids
        return self._report

    @property
    def is_complete(self) -> bool:
        return self.compute().complete
