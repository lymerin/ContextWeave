"""文档规划输入模型。"""

from pydantic import BaseModel, ConfigDict

from app.schemas.platform_mcp.binding import ExistingBinding
from app.schemas.platform_mcp.document import DocumentCandidate, DocumentStructure
from app.schemas.document_planner.evidence import PlanningEvidenceCatalog
from app.schemas.semantic.analysis_result import SemanticAnalysisResult


class DocumentPlanningInput(BaseModel):
    """文档规划的完整输入。"""
    model_config = ConfigDict(extra="forbid")

    context_id: str
    snapshot_hash: str
    evidence_catalog: PlanningEvidenceCatalog
    semantic_result: SemanticAnalysisResult
    document_candidates: list[DocumentCandidate] = []
    existing_bindings: list[ExistingBinding] = []
    document_structures: list[DocumentStructure] = []
