"""文档骨架模型——纯程序产出，零模型参与。

骨架 = 模块文档的完整结构（排版契约）：章节树 + 每个章节的符号槽位。
槽位有稳定 ID（跨 revision 稳定），是批次会话的填充目标和 reconcile 的匹配键。
"""

from __future__ import annotations

from dataclasses import dataclass


class SlotType:
    OVERVIEW = "OVERVIEW"    # 模块总览（绑定第一入口）
    FLOW = "FLOW"            # 主要流程（每入口一章，绑定入口符号）
    SYMBOL = "SYMBOL"        # 代码速查（每公开类/公开函数一条，绑定目标符号）


@dataclass(frozen=True)
class SkeletonSlot:
    """一个待填充的文档槽位。

    slot_id            稳定身份：module:overview / flow:<entry_key> / symbol:<symbol_key>
    slot_type          OVERVIEW | FLOW | SYMBOL
    title              骨架标题（排版由它决定，模型无权改）
    primary_symbol_key 绑定目标符号（reconcile 匹配键，程序从快照取）
    placeholder        未填充时的占位文本
    file_path          符号所在文件（SYMBOL 槽位）
    sort_order         章节内顺序
    """

    slot_id: str
    slot_type: str
    title: str
    primary_symbol_key: str | None
    placeholder: str = ""
    file_path: str | None = None
    sort_order: int = 0


@dataclass(frozen=True)
class BatchPlan:
    """一个填充批次的计划。

    batch_index   批号（1 = 总览+主要流程，其余为代码速查分片）。
    scope_label   人类可读作用域（注入会话指令）。
    slot_ids      本批负责的槽位（程序清单，模型无遗漏空间）。
    file_paths    本批读取的文件（构建 scope 用）。
    """

    batch_index: int
    scope_label: str
    slot_ids: tuple[str, ...]
    file_paths: tuple[str, ...]


@dataclass(frozen=True)
class DocumentSkeleton:
    """模块文档的完整骨架。

    document_title  文档标题（由模块命名决定）。
    slots           全部槽位（排序即排版）。
    batches         批次计划（批 1 + 每文件一批速查）。
    """

    document_title: str
    slots: tuple[SkeletonSlot, ...]
    batches: tuple[BatchPlan, ...]

    def slots_by_id(self) -> dict[str, SkeletonSlot]:
        return {s.slot_id: s for s in self.slots}

    def slot_ids(self) -> tuple[str, ...]:
        return tuple(s.slot_id for s in self.slots)
