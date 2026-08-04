"""文档漂移检测模型 —— Binding 重新解析与漂移分类。

绑定（Binding）是代码↔文档之间的契约。当代码在两个 revision 之间发生变化时，
绑定可能"漂移"——它描述的代码与文档 Block 的内容不再匹配。

本模块定义通过在新 revision 下重新解析绑定来检测和分类漂移的数据结构。

设计原则:
  - 以 Binding 为分析单元（而非以 diff hunk 为起点）
  - 漂移判定基于 old_atom vs new_atom 的符号级对比
  - 支持 FILE / RANGE / SYMBOL 三种 anchorKind 的保守降级处理
  - 三个 drift_level 直接映射到前端已有的 VALID / DRIFTED / BROKEN 状态
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum
from uuid import UUID

from app.schemas.ast_atom import SymbolAtom


class DriftLevel(StrEnum):
    """绑定漂移严重程度。

    映射到前端 CodeAnchor.status:
      NONE               → VALID
      COSMETIC           → DRIFTED
      SIGNATURE_CHANGED  → DRIFTED
      SYMBOL_MOVED       → DRIFTED
      SYMBOL_REMOVED     → BROKEN
      FILE_REMOVED       → BROKEN
    """

    NONE = "none"
    """符号签名和位置均未变化——绑定仍然准确。"""

    COSMETIC = "cosmetic"
    """仅行号偏移，签名未变——绑定基本准确，更新行号即可。"""

    SIGNATURE_CHANGED = "signature_changed"
    """函数/类签名发生变化——绑定描述的代码已不同，文档可能需要重写。"""

    SYMBOL_MOVED = "symbol_moved"
    """符号移动到其他文件——绑定的文件路径已过时。"""

    SYMBOL_REMOVED = "symbol_removed"
    """符号被删除——绑定失效，关联的文档 Block 应归档或重写。"""

    FILE_REMOVED = "file_removed"
    """整个绑定文件被删除——绑定失效。"""


# ── 重新解析结果 ────────────────────────────────────────────────────────────


@dataclass(frozen=True)
class BindingReResolution:
    """单条绑定在新 revision 下重新解析的结果。

    对比旧 revision 下绑定指向的符号与新 revision 下存在（或不存在）的符号。
    """

    binding_id: UUID
    document_id: UUID
    block_id: UUID | None

    # 旧 revision 状态
    old_atom: SymbolAtom | None = None
    """旧 revision 下该绑定解析到的 SymbolAtom。"""
    old_file_exists: bool = True

    # 新 revision 状态
    new_atom: SymbolAtom | None = None
    """新 revision 下该绑定解析到的 SymbolAtom（None = 符号不存在）。"""
    new_file_exists: bool = True
    resolution_method: str = ""
    """符号定位方式: 'symbol_key' | 'qualified_name' | 'line_range' | 'file_only'."""

    # 判定
    drift_level: DriftLevel = DriftLevel.NONE
    drift_detail: str = ""
    """人类可读的漂移说明（或无漂移原因）。"""


# ── 漂移报告 ────────────────────────────────────────────────────────────────


@dataclass(frozen=True)
class DriftReport:
    """单条绑定的完整漂移评估。

    包含重新解析结果和影响面评估（哪些调用方受影响、关联的其他绑定、建议操作）。
    """

    resolution: BindingReResolution

    # 影响面评估（Phase 2）
    affected_caller_keys: tuple[str, ...] = ()
    """依赖漂移符号的调用方 symbol_key 列表。"""

    related_binding_ids: tuple[UUID, ...] = ()
    """指向同一符号或其调用方的其他 binding ID。"""

    recommendation: str = ""
    """给文档审阅者的自然语言建议。"""
