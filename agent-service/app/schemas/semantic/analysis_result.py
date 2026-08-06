"""语义分析结果——DeepSeek 返回，结构容错。

设计原则：大模型解读任意代码，输出格式必然有变化（camelCase/snake_case
混用、字段缺失、多余字段、类型轻微偏移）。这里**不逼模型精确序列化**，
而是:
  - 去掉 strict：允许 int/str 轻微类型偏移
  - extra="ignore"：模型多写的字段忽略，不拒绝
  - 关键字段加 camelCase alias：同时接受 stepOrder/atomId 与 step_order/atom_id

程序在解析层做结构归一化（见 deepseek._normalize_payload），此 schema
负责"尽量吸收"模型的格式变化，让结果进入下游前不被格式问题拒绝。
"""

from pydantic import BaseModel, ConfigDict, Field


class EvidenceRef(BaseModel):
    """证据引用——只含 ID，程序根据 ID 从快照解析 file_path/line。"""
    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    atom_id: str = ""
    relation_id: str | None = None
    source_chunk_id: str | None = None


class MemberInterpretation(BaseModel):
    """单个符号的语义解读。"""
    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    atom_id: str = ""
    responsibility: str = ""
    role: str = ""
    content_markdown: str = ""  # 完整正文（遵守基座排版规则）；responsibility 保留为摘要
    evidence_refs: list[EvidenceRef] = Field(default_factory=list)


class SemanticGroup(BaseModel):
    """一组紧密相关的符号，共同支撑同一段文档正文。"""
    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    group_id: str = ""
    order: int = 0
    title: str = ""                              # Section 标题（如 "接口职责：POST /orders"）
    target_kind: str = ""                         # HTTP_ENDPOINT / DATA_MODEL / BUSINESS_RULE / ...
    summary: str = ""                             # 这段正文的一句摘要
    content_markdown: str = ""                    # 完整正文（遵守基座排版规则）；summary 保留为摘要
    primary_atom_ids: list[str] = Field(default_factory=list)      # PRIMARY 绑定
    informed_by_atom_ids: list[str] = Field(default_factory=list)  # 全部涉及的 atom_id
    evidence_refs: list[EvidenceRef] = Field(default_factory=list)


class ExecutionStep(BaseModel):
    """执行流程中的一步。"""
    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    step_order: int = 0
    atom_id: str = ""
    description: str = ""
    evidence_refs: list[EvidenceRef] = Field(default_factory=list)


class SemanticAnalysisResult(BaseModel):
    """DeepSeek 语义分析最终输出。"""
    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    analysis_id: str = ""
    context_id: str = ""
    revision: str = ""
    snapshot_hash: str = ""
    overall_responsibility: str = ""
    execution_flow: list[ExecutionStep] = Field(default_factory=list)
    semantic_groups: list[SemanticGroup] = Field(default_factory=list)
    member_interpretations: list[MemberInterpretation] = Field(default_factory=list)
    inputs: list[str] = Field(default_factory=list)
    outputs: list[str] = Field(default_factory=list)
    effects: list[dict] = Field(default_factory=list)
    unresolved_findings: list[str] = Field(default_factory=list)
