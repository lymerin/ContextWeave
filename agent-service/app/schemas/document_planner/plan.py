"""文档规划内部模型——不是 MCP 协议。表达完整绑定状态，不做增量追加。"""

from dataclasses import dataclass, field
from uuid import UUID


@dataclass
class PlannedSection:
    """文档中的一个段落——由 semantic_group 转换而来。

    primary_atom_ids        PRIMARY 绑定的 atom_id。
    informed_by_atom_ids    全部涉及的 atom_id（PRIMARY + SUPPORTING）。
    """

    section_ref: str                         # group_id
    order: int = 0
    title: str = ""
    target_kind: str = ""
    content_markdown: str = ""
    primary_atom_ids: tuple[str, ...] = ()
    informed_by_atom_ids: tuple[str, ...] = ()


@dataclass
class SectionBinding:
    """单个绑定条目——section 期望的完整绑定状态中的一条。"""

    atom_id: str
    file_path: str
    symbol_key: str
    start_line: int
    end_line: int
    role: str = "PRIMARY"                    # PRIMARY | SUPPORTING
    ordinal: int = 1
    created_block_operation_id: str | None = None  # 新建 Block 时引用 ADD_BLOCK 的 client_operation_id
    # 绑定关联的文档目标——knowledge-core 要求二选一：
    #   document_id（现有文档）或 created_document_op_id（本请求新建文档）
    document_id: UUID | None = None
    created_document_op_id: str | None = None


@dataclass
class SectionBindingSet:
    """一个 Section 当前期望拥有的完整绑定状态。

    不是"本次要追加的几条 Binding"——是"这个 Section 应该有的全部绑定"。
    """

    section_ref: str
    bindings: tuple[SectionBinding, ...] = ()


@dataclass
class PlanOperation:
    """文档操作——内部模型。"""

    client_operation_id: str
    sequence_number: int
    operation_type: str
    document_id: UUID | None = None
    block_id: UUID | None = None
    created_document_op_id: str | None = None
    proposed_document_title: str | None = None  # CREATE_DOCUMENT 必填，提交给 knowledge-core
    proposed_plain_text: str = ""


@dataclass
class AgentPlan:
    """内部 AgentPlan——表达完整状态。

    planned_sections       本次规划的 Section 列表。
    section_binding_sets   每个 Section 的完整绑定状态。
    """

    plan_id: str = ""
    context_id: str = ""
    revision: str = ""
    snapshot_hash: str = ""
    summary: str = ""
    rationale: str = ""
    document_operations: tuple[PlanOperation, ...] = ()
    planned_sections: tuple[PlannedSection, ...] = ()
    section_binding_sets: tuple[SectionBindingSet, ...] = ()
