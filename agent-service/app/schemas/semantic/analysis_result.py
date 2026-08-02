"""语义分析结果——DeepSeek 返回，Pydantic strict 校验。"""

from pydantic import BaseModel, ConfigDict, Field


class EvidenceRef(BaseModel):
    """证据引用——只含 ID，程序根据 ID 从快照解析 file_path/line。
    DeepSeek 不得自行填写路径或行号。
    """
    model_config = ConfigDict(extra="forbid", strict=True)

    atom_id: str
    relation_id: str | None = None
    source_chunk_id: str | None = None


class MemberInterpretation(BaseModel):
    """单个符号的语义解读。"""
    model_config = ConfigDict(extra="forbid", strict=True)

    atom_id: str
    responsibility: str = ""
    role: str = ""
    evidence_refs: list[EvidenceRef] = Field(default_factory=list)


class SemanticGroup(BaseModel):
    """一组紧密相关的符号，共同支撑同一段文档正文。

    DeepSeek 在语义补充时自行决定分组——
    例如将"路由 + 请求模型 + 业务方法"合并为一个 Group。
    """
    model_config = ConfigDict(extra="forbid", strict=True)

    group_id: str = ""
    order: int = 0
    title: str = ""                              # Section 标题（如 "接口职责：POST /orders"）
    target_kind: str = ""                         # HTTP_ENDPOINT / DATA_MODEL / BUSINESS_RULE / ...
    summary: str = ""                             # 这段正文的一句摘要
    primary_atom_ids: list[str] = Field(default_factory=list)      # PRIMARY 绑定
    informed_by_atom_ids: list[str] = Field(default_factory=list)  # 全部涉及的 atom_id
    evidence_refs: list[EvidenceRef] = Field(default_factory=list)


class ExecutionStep(BaseModel):
    """执行流程中的一步。"""
    model_config = ConfigDict(extra="forbid", strict=True)

    step_order: int = 0
    atom_id: str = ""
    description: str = ""
    evidence_refs: list[EvidenceRef] = Field(default_factory=list)


class SemanticAnalysisResult(BaseModel):
    """DeepSeek 语义分析最终输出。"""
    model_config = ConfigDict(extra="forbid", strict=True)

    analysis_id: str
    context_id: str
    revision: str
    snapshot_hash: str
    overall_responsibility: str = ""
    execution_flow: list[ExecutionStep] = Field(default_factory=list)
    semantic_groups: list[SemanticGroup] = Field(default_factory=list)
    member_interpretations: list[MemberInterpretation] = Field(default_factory=list)
    inputs: list[str] = Field(default_factory=list)
    outputs: list[str] = Field(default_factory=list)
    effects: list[dict] = Field(default_factory=list)
    unresolved_findings: list[str] = Field(default_factory=list)
