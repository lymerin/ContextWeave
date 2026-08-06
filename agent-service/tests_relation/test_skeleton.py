from __future__ import annotations

from uuid import UUID

from app.document_planner.document_composer import compose_slot_sections
from app.document_planner.skeleton_planner import (
    build_skeleton_plan,
    build_slot_instruction,
    plan_skeleton,
)
from app.model_context_mcp.context_freeze_snapshot import freeze_context
from app.schemas.document_planner.skeleton import SlotType
from app.schemas.semantic.analysis_result import (
    MemberInterpretation,
    SemanticAnalysisResult,
)
from app.schemas.source_file import SourceFileBatch, SourceFileRef
from app.semantic.result_validator import ResultValidator
from app.source_analysis.atom_relation_graph import build_graph
from app.source_analysis.code_ast_atom import parse_batch
from app.source_analysis.graph_entry_scope import discover_scopes
from app.source_analysis.scope_shape_context import shape_context

REPO = UUID("11111111-1111-1111-1111-111111111111")
REV = "rev-1"


def module_files() -> dict[str, str]:
    return {
        "app/main.py": (
            "from fastapi import FastAPI\n"
            "from app.service import create_order\n"
            "from app.schemas import OrderRequest\n"
            "\n"
            "app = FastAPI()\n"
            "\n"
            "@app.post('/orders')\n"
            "def create_order_route(request: OrderRequest):\n"
            "    return create_order(request)\n"
        ),
        "app/service.py": (
            "from app.schemas import OrderRequest\n"
            "from app.util import validate\n"
            "\n"
            "def create_order(request: OrderRequest):\n"
            "    validate(request)\n"
            "    return {'ok': True}\n"
        ),
        "app/schemas.py": "class OrderRequest:\n    pass\n",
        "app/util.py": "def validate(request):\n    pass\n",
    }


def pipeline():
    files = module_files()
    batch = SourceFileBatch(
        repository_id=REPO,
        revision=REV,
        files=tuple(
            SourceFileRef(file_path=path, language="Python", size_bytes=len(source))
            for path, source in sorted(files.items())
        ),
        total_count=len(files),
    )
    sources = dict(files)

    def reader(path: str) -> str | None:
        return sources.get(path)

    catalog = parse_batch(batch, reader)
    graph = build_graph(catalog, reader)
    scope = discover_scopes(graph)[0]
    shaped = shape_context(scope, catalog, reader, graph)
    snap = freeze_context(shaped)
    return catalog, snap, scope


# ── plan_skeleton ─────────────────────────────────────────────────────────


def test_skeleton_slots_cover_module_and_public_symbols() -> None:
    catalog, _, scope = pipeline()
    skeleton = plan_skeleton(scope, catalog, document_title="订单模块")
    by_id = skeleton.slots_by_id()

    assert skeleton.document_title == "订单模块"
    # 总览 + 每入口一章
    assert by_id["module:overview"].slot_type == SlotType.OVERVIEW
    flows = [s for s in skeleton.slots if s.slot_type == SlotType.FLOW]
    assert len(flows) == 1
    assert flows[0].title == "主要流程：POST /orders"
    # 速查：公开类 + 公开函数（方法并入类、私有不单列）
    symbols = [s for s in skeleton.slots if s.slot_type == SlotType.SYMBOL]
    titles = {s.title for s in symbols}
    assert "OrderRequest" in titles
    assert "create_order()" in titles
    assert "validate()" in titles
    # 无方法槽位（fixture 无公开类方法；有方法也会并入类）
    assert all("/" not in t for t in titles)


def test_skeleton_batch_plan() -> None:
    catalog, _, scope = pipeline()
    skeleton = plan_skeleton(scope, catalog)
    assert skeleton.batches[0].batch_index == 1
    assert skeleton.batches[0].slot_ids[0] == "module:overview"
    # 批 1 之后每文件一批速查
    symbol_batches = skeleton.batches[1:]
    assert len(symbol_batches) == 4
    for b in symbol_batches:
        assert len(b.file_paths) == 1
        assert b.slot_ids, "速查批次不能为空"
    # 同一文件的所有槽位在同一个批次
    files_in_batch = {b.file_paths[0] for b in symbol_batches}
    assert "app/schemas.py" in files_in_batch


def test_skeleton_is_deterministic() -> None:
    catalog, _, scope = pipeline()
    first = plan_skeleton(scope, catalog)
    second = plan_skeleton(scope, catalog)
    assert first.slots == second.slots
    assert first.batches == second.batches


def test_slot_ids_are_stable_identities() -> None:
    catalog, _, scope = pipeline()
    skeleton = plan_skeleton(scope, catalog)
    for slot in skeleton.slots:
        if slot.slot_type == SlotType.FLOW:
            assert slot.slot_id.startswith("flow:")
            assert slot.primary_symbol_key in slot.slot_id
        elif slot.slot_type == SlotType.SYMBOL:
            assert slot.slot_id.startswith("symbol:")
            assert slot.primary_symbol_key in slot.slot_id
            assert slot.file_path is not None


# ── build_slot_instruction ────────────────────────────────────────────────


def test_slot_instruction_lists_scope_and_slots() -> None:
    catalog, _, scope = pipeline()
    skeleton = plan_skeleton(scope, catalog)
    slots = tuple(s for s in skeleton.slots if s.slot_type == SlotType.SYMBOL)[:2]
    text = build_slot_instruction(slots, "代码速查：app/util.py")
    assert "代码速查：app/util.py" in text
    assert slots[0].slot_id in text
    assert "第一句是功能声明" in text
    assert "只填写清单中的槽位" in text


# ── 覆盖校验器 ────────────────────────────────────────────────────────────


def test_coverage_accepts_complete_result() -> None:
    catalog, snap, scope = pipeline()
    slot = next(
        s for s in plan_skeleton(scope, catalog).slots
        if s.slot_type == SlotType.SYMBOL
    )
    result = SemanticAnalysisResult(
        overall_responsibility="订单模块",
        member_interpretations=[
            MemberInterpretation(atom_id=slot.primary_symbol_key, responsibility="创建订单")
        ],
    )
    errors = ResultValidator(snap, required_slots=(slot,)).validate(result)
    assert not [e for e in errors if "slot 未覆盖" in e]


def test_coverage_names_missing_slots() -> None:
    catalog, snap, scope = pipeline()
    skeleton = plan_skeleton(scope, catalog)
    required = tuple(s for s in skeleton.slots if s.slot_type == SlotType.SYMBOL)
    result = SemanticAnalysisResult(
        overall_responsibility="订单模块",
        member_interpretations=[],  # 一个都没解释
    )
    errors = ResultValidator(snap, required_slots=required).validate(result)
    missing = [e for e in errors if "slot 未覆盖" in e]
    assert len(missing) == len(required)
    assert all(slot.slot_id in " ".join(missing) for slot in required)


def test_flow_slot_requires_group_hitting_entry() -> None:
    catalog, snap, scope = pipeline()
    skeleton = plan_skeleton(scope, catalog)
    flow = next(s for s in skeleton.slots if s.slot_type == SlotType.FLOW)
    result = SemanticAnalysisResult(
        overall_responsibility="订单模块",
        semantic_groups=[],
    )
    errors = ResultValidator(snap, required_slots=(flow,)).validate(result)
    assert any("slot 未覆盖" in e and flow.slot_id in e for e in errors)


# ── compose_slot_sections ────────────────────────────────────────────────


def test_compose_slot_sections_maps_to_slot_ids() -> None:
    catalog, snap, scope = pipeline()
    skeleton = plan_skeleton(scope, catalog)
    slots = tuple(s for s in skeleton.slots if s.slot_type == SlotType.SYMBOL)
    # 模拟 executor 的 _bind_result_atoms：解释的 atom_id 绑定为快照 atom_id
    result = SemanticAnalysisResult(
        overall_responsibility="订单模块",
        member_interpretations=[
            MemberInterpretation(
                atom_id=snap.atom_by_symbol[slot.primary_symbol_key],
                responsibility=f"解释：{slot.title}",
            )
            for slot in slots
        ],
    )
    sections = compose_slot_sections(slots, result, dict(snap.atom_by_symbol))
    assert len(sections) == len(slots)
    assert {s.section_ref for s in sections} == {s.slot_id for s in slots}
    first = sections[0]
    assert first.content_markdown.startswith(f"## {slots[0].title}")


# ── build_skeleton_plan（骨架 Review 载荷）───────────────────────────────


def test_skeleton_plan_creates_placeholder_blocks_with_bindings() -> None:
    catalog, snap, scope = pipeline()
    skeleton = plan_skeleton(scope, catalog)
    plan = build_skeleton_plan(
        skeleton, snap, catalog,
        candidates=[], structures=[], existing_bindings=[],
    )
    # 新建文档 + 每个槽位一个占位块
    creates = [o for o in plan.document_operations if o.operation_type == "CREATE_DOCUMENT"]
    adds = [o for o in plan.document_operations if o.operation_type == "ADD_BLOCK"]
    assert len(creates) == 1
    assert len(adds) == len(skeleton.slots)
    # 每个槽位一条绑定（占位块带绑定 → 后续批次按绑定重叠匹配 UPDATE_BLOCK）
    assert len(plan.section_binding_sets) == len(skeleton.slots)
    for bs in plan.section_binding_sets:
        assert len(bs.bindings) == 1, "每个占位块恰好一个 PRIMARY 绑定"
    # 占位文本进入块内容
    assert "系统自动生成" in adds[0].proposed_plain_text




# ── 批次指令模板（batch_type）────────────────────────────────────────────


def test_flow_instruction_template() -> None:
    catalog, _, scope = pipeline()
    skeleton = plan_skeleton(scope, catalog)
    flow_slots = tuple(s for s in skeleton.slots if s.slot_type == SlotType.FLOW)
    text = build_slot_instruction(
        (skeleton.slots[0], *flow_slots), "模块总览与主要流程", batch_type="FLOW"
    )
    assert "semantic_groups" in text
    assert "content_markdown" in text
    assert "inline_sources" in text
    assert "不要调用任何工具" in text
    assert "module:overview" in text


def test_symbol_instruction_template() -> None:
    catalog, _, scope = pipeline()
    skeleton = plan_skeleton(scope, catalog)
    slots = tuple(s for s in skeleton.slots if s.slot_type == SlotType.SYMBOL)
    text = build_slot_instruction(slots, "代码速查：app/schemas.py", batch_type="SYMBOL")
    assert "member_interpretations" in text
    assert "content_markdown" in text
    assert "inline_sources" in text
    assert "不要调用任何工具" in text
    assert "方法并入所在类的说明" in text


# ── content_markdown ─────────────────────────────────────────────────────


def test_semantic_result_accepts_content_markdown() -> None:
    from app.schemas.semantic.analysis_result import SemanticGroup

    group = SemanticGroup(
        group_id="g1", title="主要流程", summary="摘要",
        content_markdown="## 主要流程\n\n功能声明。\n\n流程：\n1. 步骤一\n2. 步骤二",
        primary_atom_ids=["PYTHON:a.py:run:FUNCTION"],
    )
    assert group.content_markdown.startswith("## 主要流程")


def test_compose_flow_slot_uses_group_content() -> None:
    catalog, snap, scope = pipeline()
    skeleton = plan_skeleton(scope, catalog)
    flow = next(s for s in skeleton.slots if s.slot_type == SlotType.FLOW)
    from app.schemas.semantic.analysis_result import SemanticGroup

    entry_atom = snap.atom_by_symbol[flow.primary_symbol_key]
    result = SemanticAnalysisResult(
        overall_responsibility="订单模块",
        semantic_groups=[
            SemanticGroup(
                group_id="g1", title="主要流程", summary="摘要",
                content_markdown="功能声明：创建订单。\n\n流程：\n1. 校验\n2. 落库",
                primary_atom_ids=[entry_atom],
                informed_by_atom_ids=[entry_atom],
            )
        ],
    )
    sections = compose_slot_sections(
        (flow,), result, dict(snap.atom_by_symbol)
    )
    assert len(sections) == 1
    assert sections[0].section_ref == flow.slot_id
    assert "功能声明：创建订单" in sections[0].content_markdown
    assert sections[0].primary_atom_ids == (entry_atom,)


# ── worker 批次载荷往返 ───────────────────────────────────────────────────


def test_batch_payloads_round_trip() -> None:
    from app.schemas.document_planner.skeleton import SkeletonSlot
    from app.worker import _batch_payloads

    catalog, _, scope = pipeline()
    skeleton = plan_skeleton(scope, catalog)
    payloads = _batch_payloads(skeleton)

    # 批 1 + 每文件一批
    assert payloads[0]["batch_index"] == 1
    assert payloads[0]["slot_plan"]["batchType"] == "FLOW"
    assert payloads[1]["slot_plan"]["batchType"] == "SYMBOL"
    # 往返：序列化槽位可重建为 SkeletonSlot（worker 会话消费）
    for payload in payloads:
        for slot_dict in payload["slot_plan"]["slots"]:
            slot = SkeletonSlot(**slot_dict)
            assert slot.slot_id.startswith(("module:", "flow:", "symbol:"))
    # 每批槽位数量与骨架一致
    assert sum(len(p["slot_plan"]["slots"]) for p in payloads) == len(skeleton.slots)
    # 文件集往返
    assert payloads[0]["file_paths"] == sorted(scope.related_files)
