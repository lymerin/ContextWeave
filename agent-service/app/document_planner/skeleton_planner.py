"""骨架生成器——模块快照 → 槽位清单 + 批次计划 + 骨架 Review 载荷。

纯程序、零模型调用。输入 SemanticScope + AtomCatalog（模块边界与符号清单），
输出 DocumentSkeleton（排版契约）与骨架 Review 的 AgentPlan（占位块 + 绑定）。

粒度规则（程序决定，模型无权改）:
  - 主要流程：每个入口一章（FLOW 槽位），非主要流程不单列。
  - 代码速查：每个公开类 + 公开函数一条（SYMBOL 槽位），方法并入所在类说明。
  - 私有符号（_ 前缀）不单列。
"""

from __future__ import annotations

from app.document_planner.binding_resolver import resolve_bindings
from app.document_planner.evidence_catalog_builder import build as build_evidence
from app.document_planner.plan_validator import assemble_and_validate
from app.document_planner.target_resolver import resolve_targets
from app.schemas.ast_atom import AtomCatalog, SymbolAtom, SymbolKind
from app.schemas.document_planner.plan import AgentPlan, PlannedSection
from app.schemas.document_planner.skeleton import (
    BatchPlan,
    DocumentSkeleton,
    SkeletonSlot,
    SlotType,
)
from app.schemas.model_context.snapshot import ContextSnapshot
from app.schemas.scope import SemanticScope

# 每批速查条目的数量上限（上下文预算保护）
_MAX_SYMBOLS_PER_BATCH = 10
# 占位文本——骨架 Review 创建占位块，填充成功后被真实内容替换
_PLACEHOLDER_PREFIX = "⏳ 本条目由系统自动生成中（批次填充后自动替换）"


def plan_skeleton(
    scope: SemanticScope,
    catalog: AtomCatalog,
    *,
    document_title: str = "",
) -> DocumentSkeleton:
    """从模块 scope + 符号目录生成文档骨架。"""
    slots: list[SkeletonSlot] = []
    sort_order = 0

    # 1. 模块总览（绑定第一入口；无入口则无主绑定）
    first_entry = sorted(scope.entries, key=lambda e: e.symbol_key)[0] if scope.entries else None
    slots.append(SkeletonSlot(
        slot_id="module:overview",
        slot_type=SlotType.OVERVIEW,
        title="模块总览",
        primary_symbol_key=first_entry.symbol_key if first_entry else None,
        placeholder=f"{_PLACEHOLDER_PREFIX}：模块总览（业务职责、入口一览、依赖）",
        sort_order=sort_order,
    ))
    sort_order += 1

    # 2. 主要流程：每入口一章
    for entry in sorted(scope.entries, key=lambda e: e.symbol_key):
        slots.append(SkeletonSlot(
            slot_id=f"flow:{entry.symbol_key}",
            slot_type=SlotType.FLOW,
            title=f"主要流程：{entry.label}",
            primary_symbol_key=entry.symbol_key,
            placeholder=f"{_PLACEHOLDER_PREFIX}：{entry.label}",
            file_path=_file_from_key(entry.symbol_key),
            sort_order=sort_order,
        ))
        sort_order += 1

    # 3. 代码速查：公开类 + 公开函数（方法并入类，私有不单列）
    module_files = frozenset(scope.related_files)
    for sym in sorted(
        (s for s in catalog.symbols
         if _file_from_key(s.symbol_key) in module_files
         and s.kind in (SymbolKind.CLASS, SymbolKind.FUNCTION, SymbolKind.ASYNC_FUNCTION)
         and not _is_private(s.name)),
        key=lambda s: (_file_from_key(s.symbol_key), s.start_line, s.symbol_key),
    ):
        slots.append(SkeletonSlot(
            slot_id=f"symbol:{sym.symbol_key}",
            slot_type=SlotType.SYMBOL,
            title=_symbol_title(sym),
            primary_symbol_key=sym.symbol_key,
            placeholder=f"{_PLACEHOLDER_PREFIX}：{_symbol_title(sym)}",
            file_path=_file_from_key(sym.symbol_key),
            sort_order=sort_order,
        ))
        sort_order += 1

    # 4. 批次计划：批 1 = 总览 + 全部流程；批 2..N = 每文件速查分片
    batches: list[BatchPlan] = []
    flow_slots = [s for s in slots if s.slot_type in (SlotType.OVERVIEW, SlotType.FLOW)]
    batches.append(BatchPlan(
        batch_index=1,
        scope_label="模块总览与主要流程",
        slot_ids=tuple(s.slot_id for s in flow_slots),
        file_paths=tuple(sorted(scope.related_files)),
    ))
    symbol_slots = [s for s in slots if s.slot_type == SlotType.SYMBOL]
    batch_index = 2
    for file_path in sorted({s.file_path for s in symbol_slots if s.file_path}):
        file_slots = [s for s in symbol_slots if s.file_path == file_path]
        for i in range(0, len(file_slots), _MAX_SYMBOLS_PER_BATCH):
            chunk = file_slots[i: i + _MAX_SYMBOLS_PER_BATCH]
            batches.append(BatchPlan(
                batch_index=batch_index,
                scope_label=f"代码速查：{file_path}",
                slot_ids=tuple(s.slot_id for s in chunk),
                file_paths=(file_path,),
            ))
            batch_index += 1

    return DocumentSkeleton(
        document_title=document_title or (scope.entries[0].label if scope.entries else "代码模块"),
        slots=tuple(slots),
        batches=tuple(batches),
    )


def build_slot_instruction(
    slots: tuple[SkeletonSlot, ...],
    scope_label: str,
    batch_type: str = "SYMBOL",
) -> str:
    """构造批次会话指令（注入 SemanticAnalysisRequest.instruction）。

    batch_type:
      "FLOW"   —— 批 1：模块总览 + 主要流程（输出 semantic_groups.content_markdown）
      "SYMBOL" —— 批 2..N：代码速查（输出 member_interpretations.content_markdown）
    槽位清单由程序注入，模型无遗漏空间。
    """
    listing = "\n".join(f"- {s.slot_id}（{s.title}）" for s in slots)
    static_note = (
        "槽位目标的源码已随请求内联（inline_sources），直接依据内联源码写出解释；"
        "不要调用任何工具，最终输出必须是一个 JSON 对象。"
    )
    if batch_type == "FLOW":
        return (
            f"本次会话你负责批次：{scope_label}。\n\n"
            "请为下列槽位输出 semantic_groups，每个槽位填写 content_markdown 完整正文"
            "（遵守基座排版规则）：\n"
            f"{listing}\n\n"
            "模块总览：业务职责（功能声明一句）、入口一览（- 入口：干什么）、"
            "模块间依赖（谁调用我、我调用谁，来自内联源码中的调用证据）。\n"
            "主要流程：从入口出发讲完整链路——请求怎么进来、数据怎么流转、"
            "关键规则在哪、结果怎么返回。每个流程一个 semantic_group。\n\n"
            f"{static_note}\n"
            "只填写清单中的槽位；清单外内容不要写，其他批次负责。"
        )
    return (
        f"本次会话你负责批次：{scope_label}。\n\n"
        "请为下列槽位输出 member_interpretations，每个槽位填写 content_markdown 完整正文"
        "（遵守基座排版规则）：\n"
        f"{listing}\n\n"
        "每个槽位的写法：\n"
        "1. 第一句是功能声明（见基座规则）\n"
        "2. 展开关键细节：类说明含字段列表（- 名称：说明）、职责、与其他符号的协作；"
        "函数说明含输入、处理、输出\n"
        "3. 可选附 **需要注意** 小节（仅限代码证据支持的内容）\n"
        "4. 方法并入所在类的说明，不单列\n\n"
        f"{static_note}\n"
        "只填写清单中的槽位；清单外内容不要写，其他批次负责。"
    )


def build_skeleton_plan(
    skeleton: DocumentSkeleton,
    snap: ContextSnapshot,
    catalog: AtomCatalog,
    candidates: list,
    structures: list,
    existing_bindings: list,
    *,
    max_placeholder_slots: int = 45,
) -> AgentPlan:
    """骨架 → AgentPlan（占位块 + 绑定），复用现有 reconcile/assemble 管线。

    每个槽位一个 PlannedSection（内容 = 占位文本），绑定目标符号——
    占位块带着绑定创建，后续批次按绑定重叠匹配 → UPDATE_BLOCK 替换。

    max_placeholder_slots：knowledge-core 单请求上限 50 个操作（创建文档 1 +
    占位块 N + 绑定 N），大模块只预建前 N 个占位块，其余槽位由对应批次会话
    走 ADD_BLOCK 创建（reconcile 未匹配即新增）。
    """
    # primary atom 从 catalog 解析（骨架覆盖模块全量符号，快照只含 scope 闭包）
    catalog_atom_ids = {s.symbol_key: s.atom_id for s in catalog.symbols}
    sections: list[PlannedSection] = []
    for slot in skeleton.slots[:max_placeholder_slots]:
        primary_atom = (
            catalog_atom_ids.get(slot.primary_symbol_key)
            if slot.primary_symbol_key else None
        )
        primary_ids = (primary_atom,) if primary_atom else ()
        sections.append(PlannedSection(
            section_ref=slot.slot_id,
            order=slot.sort_order,
            title=slot.title,
            target_kind=slot.slot_type,
            content_markdown=f"## {slot.title}\n\n{slot.placeholder}",
            primary_atom_ids=primary_ids,
            informed_by_atom_ids=primary_ids,
        ))

    atom_symbol_keys = {a.atom_id: a.symbol_key for a in snap.atoms}
    targets = resolve_targets(
        tuple(sections), candidates, structures, existing_bindings,
        atom_symbol_keys=atom_symbol_keys,
    )
    # evidence 补齐模块文件内快照未覆盖的符号（骨架绑定必须全量）
    module_files = frozenset(
        p for b in skeleton.batches for p in b.file_paths
    )
    snap_keys = {a.symbol_key for a in snap.atoms}
    extra_symbols = tuple(
        s for s in catalog.symbols
        if _file_from_key(s.symbol_key) in module_files
        and s.symbol_key not in snap_keys
    )
    evidence = build_evidence(snap, catalog, extra_symbols=extra_symbols)
    binding_sets = resolve_bindings(
        tuple(sections), evidence, targets,
        existing_bindings=existing_bindings,
    )
    return assemble_and_validate(
        tuple(sections), binding_sets, evidence,
        context_id=snap.context_id,
        revision=snap.revision,
        snapshot_hash=snap.snapshot_hash,
        section_targets=targets,
    )


def _symbol_title(sym: SymbolAtom) -> str:
    if sym.kind == SymbolKind.CLASS:
        return sym.qualified_name
    return f"{sym.qualified_name}()"


def _is_private(name: str) -> bool:
    return name.startswith("_") and not name.startswith("__")


def _file_from_key(symbol_key: str) -> str:
    parts = symbol_key.split(":", 2)
    return parts[1] if len(parts) > 1 else ""
