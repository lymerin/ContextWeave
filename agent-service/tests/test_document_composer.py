"""compose_slot_sections 槽位归属测试——回归：重复块缺陷。

缺陷背景：group_by_atom 用合并映射 + setdefault，模块总览 group 的
informed_by_atom_ids 覆盖全部模块符号时，所有 FLOW/OVERVIEW 槽位
误命中总览 group → 同一批次提交 N 份相同正文（重复块）。
修复：primary 精确匹配优先，informed 仅兜底。
"""

import sys

sys.path.insert(0, "agent-service")

from app.document_planner.document_composer import compose_slot_sections
from app.schemas.document_planner.skeleton import SkeletonSlot, SlotType
from app.schemas.semantic.analysis_result import (
    SemanticAnalysisResult,
    SemanticGroup,
)


def _slot(slot_id: str, slot_type: SlotType, symbol_key: str, title: str) -> SkeletonSlot:
    return SkeletonSlot(
        slot_id=slot_id,
        slot_type=slot_type,
        title=title,
        primary_symbol_key=symbol_key,
        placeholder="⏳ 占位",
        sort_order=0,
    )


def test_flow_slots_hit_their_own_primary_group() -> None:
    """模块总览 group informed 覆盖全部符号时，FLOW 槽位仍命中各自 primary group。"""
    atom_by_symbol = {
        "JAVA:a/Login.java:Login:CLASS": "sym_login",
        "JAVA:a/Role.java:Role:CLASS": "sym_role",
        "JAVA:a/User.java:User:CLASS": "sym_user",
    }
    slots = (
        _slot(
            "module:overview",
            SlotType.OVERVIEW,
            "JAVA:a/Login.java:Login:CLASS",
            "模块总览",
        ),
        _slot(
            "flow:JAVA:a/Login.java:Login:CLASS",
            SlotType.FLOW,
            "JAVA:a/Login.java:Login:CLASS",
            "主要流程：Login",
        ),
        _slot(
            "flow:JAVA:a/Role.java:Role:CLASS",
            SlotType.FLOW,
            "JAVA:a/Role.java:Role:CLASS",
            "主要流程：Role",
        ),
    )
    semantic = SemanticAnalysisResult(
        semantic_groups=[
            # 模块总览：informed 覆盖全部符号（这是缺陷的触发条件）
            SemanticGroup(
                group_id="g_overview",
                order=0,
                title="模块总览",
                content_markdown="## 模块总览\n\n整个模块的业务职责",
                primary_atom_ids=["sym_login"],
                informed_by_atom_ids=["sym_login", "sym_role", "sym_user"],
            ),
            SemanticGroup(
                group_id="g_login",
                order=1,
                title="主要流程：Login",
                content_markdown="## 主要流程：Login\n\n登录链路",
                primary_atom_ids=["sym_login"],
                informed_by_atom_ids=["sym_login"],
            ),
            SemanticGroup(
                group_id="g_role",
                order=2,
                title="主要流程：Role",
                content_markdown="## 主要流程：Role\n\n角色链路",
                primary_atom_ids=["sym_role"],
                informed_by_atom_ids=["sym_role"],
            ),
        ],
    )

    sections = compose_slot_sections(slots, semantic, atom_by_symbol)

    # 3 个槽位全部命中，无跳过
    assert len(sections) == 3
    by_ref = {s.section_ref: s for s in sections}
    # overview 槽位命中总览 group
    assert "模块总览" in by_ref["module:overview"].content_markdown
    # FLOW 槽位命中各自的 primary group，而不是被总览 group 吞掉
    assert by_ref["flow:JAVA:a/Login.java:Login:CLASS"].content_markdown == (
        "## 主要流程：Login\n\n登录链路"
    )
    assert by_ref["flow:JAVA:a/Role.java:Role:CLASS"].content_markdown == (
        "## 主要流程：Role\n\n角色链路"
    )
    # 无重复内容
    contents = {s.content_markdown for s in sections}
    assert len(contents) == 3


def test_informed_fallback_when_no_primary_group() -> None:
    """符号不在任何 group 的 primary 时，informed 兜底仍可命中。"""
    atom_by_symbol = {"JAVA:a/X.java:X:CLASS": "sym_x"}
    slots = (
        _slot(
            "flow:JAVA:a/X.java:X:CLASS",
            SlotType.FLOW,
            "JAVA:a/X.java:X:CLASS",
            "主要流程：X",
        ),
    )
    semantic = SemanticAnalysisResult(
        semantic_groups=[
            SemanticGroup(
                group_id="g_x",
                order=0,
                title="主要流程：X",
                content_markdown="## 主要流程：X\n\nX 的链路",
                primary_atom_ids=[],
                informed_by_atom_ids=["sym_x"],
            ),
        ],
    )

    sections = compose_slot_sections(slots, semantic, atom_by_symbol)

    assert len(sections) == 1
    assert "X 的链路" in sections[0].content_markdown
