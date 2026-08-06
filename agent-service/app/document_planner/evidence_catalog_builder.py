"""证据目录构建——从快照提取 Binding 所需的原子行号映射。"""

from app.schemas.ast_atom import AtomCatalog
from app.schemas.document_planner.evidence import (
    PlanningEvidenceCatalog,
    build_evidence_catalog,
)
from app.schemas.model_context.snapshot import ContextSnapshot

__all__ = ["build", "PlanningEvidenceCatalog"]


def build(
    snapshot: ContextSnapshot,
    catalog: AtomCatalog,
    extra_symbols: tuple = (),
) -> PlanningEvidenceCatalog:
    """ContextSnapshot + AtomCatalog → PlanningEvidenceCatalog。"""
    return build_evidence_catalog(snapshot, catalog, extra_symbols=extra_symbols)
