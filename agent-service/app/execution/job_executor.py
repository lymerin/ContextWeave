"""Agent 管线编排器——按序调各层模块，不包含分析逻辑。

编排顺序:
  1. platform_mcp 读取源码
  2. source_selection 粗筛
  3. source_analysis AST 解析 + 关系图 + 范围构建（多文件单元合并为模块 scope）
  4. model_context_mcp 上下文整形 + 冻结快照
  5. semantic DeepSeek 语义补充 (Bounded Repair)
  6. Gates 校验
  7. platform_mcp 读取文档侧
  8. document_planner 文档规划 + 目标解析 + 绑定
  9. platform_mcp.plan_writer 提交
"""

from __future__ import annotations

import hashlib
import logging
from collections.abc import Callable
from typing import Any
from uuid import UUID

from app.document_planner.binding_resolver import resolve_bindings
from app.document_planner.document_composer import compose_document, compose_slot_sections
from app.document_planner.evidence_catalog_builder import build
from app.document_planner.plan_validator import PlanValidationError, assemble_and_validate
from app.document_planner.skeleton_planner import (
    build_skeleton_plan,
    build_slot_instruction,
    plan_skeleton,
)
from app.document_planner.target_resolver import resolve_targets
from app.model_context_mcp.context_freeze_snapshot import freeze_context
from app.model_context_mcp.service_mcp_tool import McpContextTools
from app.model_context_mcp.snapshot_store_registry import SnapshotStoreRegistry
from app.platform_mcp.binding_reader import BindingReader
from app.platform_mcp.document_reader import DocumentReader
from app.platform_mcp.plan_writer import PlanWriter
from app.platform_mcp.source_reader import SourceReader
from app.platform_mcp.workspace_reader import WorkspaceReader
from app.schemas.ast_atom import AtomCatalog, symbol_key_file_path
from app.schemas.document_planner.skeleton import SkeletonSlot
from app.schemas.model_context.snapshot import ContextSnapshot
from app.schemas.platform_mcp.source_file import SelectedSourceFileBatch
from app.schemas.repository_graph import RepositoryCodeGraph
from app.schemas.scope import ScopeMember, SemanticScope
from app.schemas.semantic.analysis_request import SemanticAnalysisRequest
from app.schemas.semantic.analysis_result import SemanticAnalysisResult
from app.semantic.analysis_orchestrator import AnalysisOrchestrator
from app.source_analysis.atom_relation_graph import build_graph
from app.source_analysis.code_ast_atom import parse_batch
from app.source_analysis.graph_entry_scope import build_file_scopes, discover_scopes
from app.source_analysis.scope_shape_context import shape_context
from app.source_selection.file_filter import SourceFileFilter

LOGGER = logging.getLogger("devcollab.agent.executor")


class SlotCoverageError(ValueError):
    """批次槽位覆盖不足——会话内 repair 耗尽后仍未补齐，交由 worker 自动重试。"""

    code = "SLOT_COVERAGE_INSUFFICIENT"


class JobExecutor:
    """管线编排——粘合层，不包含分析逻辑。"""

    def __init__(
        self,
        workspace_reader: WorkspaceReader,
        source_reader: SourceReader,
        document_reader: DocumentReader,
        binding_reader: BindingReader,
        plan_writer: PlanWriter,
        file_filter: SourceFileFilter,
        registry: SnapshotStoreRegistry,
        provider: Any = None,
        budget_chars: int = 40_000,
    ) -> None:
        self._ws = workspace_reader
        self._src = source_reader
        self._doc = document_reader
        self._bind = binding_reader
        self._write = plan_writer
        self._filter = file_filter
        self._registry = registry
        self._provider = provider
        self._budget_chars = budget_chars

    async def execute(
        self,
        *,
        workspace_id: UUID,
        repository_id: UUID,
        revision: str,
        selected_paths: list[str],
        run_id: str,
        slot_scope: tuple[SkeletonSlot, ...] = (),
        batch_label: str = "",
    ) -> dict:
        """执行完整的源码分析 → 语义补充 → 文档规划 → 提交管线。

        run_id 由调用方提供且每次执行唯一，用于生成幂等 clientRequestId，
        避免对同一仓库+revision 重复提交被 knowledge-core 幂等校验拒绝。

        slot_scope 提供时本执行是"槽位批次"：会话指令注入批次作用域与槽位清单，
        校验器核对清单覆盖（缺失槽位逐个点名 → 会话内 repair → 仍缺则抛
        SlotCoverageError 由 worker 自动重试）；正文组装走 compose_slot_sections。
        slot_scope 为空时维持现有语义会话（批 1 / 单文件分析）。
        """


        # ── 1. 读取 + 粗筛 ──────────────────────────────────────
        ctx = await self._ws.read_context(workspace_id, repository_id)
        repo = ctx.repository(repository_id)
        if repo is None:
            return {"error": "REPOSITORY_NOT_FOUND"}

        batch = self._filter.filter(
            repository_id, revision,
            [{"filePath": p, "readable": True, "sizeBytes": 0} for p in selected_paths],
        )

        selection = SelectedSourceFileBatch(
            repository_id=str(repository_id),
            revision=revision,
            paths=tuple(f.file_path for f in batch.files),
            total_count=batch.total_count,
        )
        sources = await self._src.read_batch(workspace_id, repository_id, selection)

        # ── 2. AST 解析 + 直接范围构造 ───────────────────────────
        def read_source(path: str) -> str | None:
            for f in sources.files:
                if f.file_path == path:
                    return f.content
            return None

        catalog = parse_batch(sources, read_source)

        # ── 2b. 范围构建（按单元类型分支）─────────────────────────
        # 多文件单元（SEMANTIC_ANALYSIS）→ 整模块一个 scope，一次 DS 会话；
        # 单文件单元（CURRENT_FILE_ANALYSIS）→ 维持每文件 scope。
        scopes, graph = build_execution_scopes(catalog, read_source, selected_paths)

        if not scopes:
            return {
                "status": "EMPTY_SCOPE",
                "change_request_id": None,
                "summary": "无法从文件中检测到可分析的语义入口（HTTP 路由、公开类或函数）",
            }

        scope_results: list[dict] = []
        submitted_count = 0
        failed_count = 0
        first_review_id: str | None = None

        for scope in scopes:
            shaped = shape_context(
                scope, catalog, read_source, graph,
                budget_chars=self._budget_chars,
            )

            snap = freeze_context(shaped)
            self._registry.register(snap)
            self._registry.acquire(snap.context_id, snap.context_id)

            try:
                tools = McpContextTools(self._registry)
                orch = AnalysisOrchestrator(
                    snap, tools, required_slots=slot_scope,
                )

                request = orch.build_request()
                if slot_scope:
                    # 批次作用域：指令注入槽位清单 + 源码内联（静态生成，
                    # 无工具循环——快速模型在工具循环下会死循环）
                    batch_type = (
                        "FLOW"
                        if any(
                            s.slot_type in ("OVERVIEW", "FLOW") for s in slot_scope
                        )
                        else "SYMBOL"
                    )
                    request = request.model_copy(update={
                        "instruction": build_slot_instruction(
                            slot_scope, batch_label, batch_type=batch_type,
                        ),
                    })
                    inline_sources = _build_inline_sources(snap, slot_scope)
                    result = await self._run_batch_session(
                        orch, request, inline_sources,
                    )
                else:
                    result = await self._run_semantic_session(orch, request)

                # 模型只返回可读的 symbol_key；在此一次性绑定回 atom_id，
                # 使下游校验/evidence/binding 全链路使用一致主键。
                _bind_result_atoms(result, snap)

                errors = orch.validate_result(result)
                if errors:
                    coverage_missing = any(
                        "slot 未覆盖" in e for e in errors
                    )
                    LOGGER.warning(
                        "Semantic result validation failed: %s", errors
                    )
                    failed_count += 1
                    scope_results.append({
                        "error": (
                            "SLOT_COVERAGE_INSUFFICIENT"
                            if coverage_missing else "VALIDATION_FAILED"
                        ),
                        "details": errors,
                    })
                    continue

                if result.overall_responsibility in (
                    "SEMANTIC_ANALYSIS_FAILED",
                    "SEMANTIC_ANALYSIS_EXHAUSTED",
                ):
                    # Bounded Repair 穷尽/Provider 失败返回的空结果。
                    # 不能伪装成 NO_CHANGE——这是执行失败，必须计入 failed。
                    LOGGER.warning(
                        "Semantic analysis failed: %s",
                        result.overall_responsibility,
                    )
                    failed_count += 1
                    scope_results.append({
                        "error": "SEMANTIC_ANALYSIS_FAILED",
                        "details": result.overall_responsibility,
                    })
                    continue

                if not result.semantic_groups and not result.member_interpretations:
                    # DeepSeek 正常返回但判定无文档价值 → 真正 NO_CHANGE。
                    LOGGER.warning("Semantic result has no groups or interpretations")
                    scope_results.append({
                        "status": "NO_CHANGE",
                        "summary": "DeepSeek 未产出语义分组",
                    })
                    continue

                files = list(scope.related_files)
                candidates = await self._doc.locate_documents(
                    workspace_id, repository_id, files,
                )
                structures: list = []
                doc_ids: list[UUID] = []
                for c in candidates:
                    if c.document_id not in doc_ids:
                        doc_ids.append(c.document_id)
                if doc_ids:
                    structures = await self._doc.read_structures(workspace_id, doc_ids)
                bindings_list = await self._bind.read_batch(
                    workspace_id, repository_id, files,
                )

                evidence = build(snap, catalog)
                atom_symbol_keys = {
                    a.atom_id: a.symbol_key for a in snap.atoms
                }
                if slot_scope:
                    sections = compose_slot_sections(
                        slot_scope, result, snap.atom_by_symbol,
                    )
                else:
                    sections = compose_document(
                        result,
                        title=result.overall_responsibility[:80] or "代码职责说明",
                    )
                targets = resolve_targets(
                    sections, candidates, structures, bindings_list,
                    atom_symbol_keys=atom_symbol_keys,
                )
                binding_sets = resolve_bindings(
                    sections, evidence, targets,
                    existing_bindings=bindings_list,
                )

                plan = assemble_and_validate(
                    sections, binding_sets, evidence,
                    context_id=snap.context_id,
                    revision=snap.revision,
                    snapshot_hash=snap.snapshot_hash,
                    section_targets=targets,
                )
                submit_result = await self._write.submit(
                    plan,
                    workspace_id=str(workspace_id),
                    repository_id=str(repository_id),
                    run_id=run_id,
                )

                # 提取 changeRequestId（MCP 返回驼峰，转为 snake_case 给 Worker）
                review_id = (
                    submit_result.get("changeRequestId")
                    or submit_result.get("change_request_id")
                )
                scope_results.append({
                    "status": "SUBMITTED",
                    "review_id": review_id,
                })
                submitted_count += 1
                if review_id and first_review_id is None:
                    first_review_id = str(review_id)

            except PlanValidationError as exc:
                LOGGER.error("Plan validation failed: %s", exc.issues)
                failed_count += 1
                scope_results.append({
                    "error": "PLAN_VALIDATION_FAILED",
                    "details": exc.issues,
                })
            except Exception:
                LOGGER.exception("Execution failed for scope %s", scope.scope_id)
                failed_count += 1
                scope_results.append({
                    "error": "EXECUTION_FAILED",
                })
            finally:
                self._registry.release(snap.context_id, snap.context_id)

        # 槽位覆盖不足：会话内 repair 已穷尽 → 抛给 worker 自动重试（退避）
        if any(
            r.get("error") == "SLOT_COVERAGE_INSUFFICIENT"
            for r in scope_results
        ):
            details = [
                d
                for r in scope_results
                if r.get("error") == "SLOT_COVERAGE_INSUFFICIENT"
                for d in r.get("details", [])
            ]
            raise SlotCoverageError("; ".join(details))

        # 状态决策: 有提交 → SUBMITTED, 全失败 → FAILED, 否则 NO_CHANGE
        if submitted_count > 0:
            outcome = "SUBMITTED"
        elif failed_count > 0:
            outcome = "FAILED"
        else:
            outcome = "NO_CHANGE"

        return {
            "status": outcome,
            "change_request_id": first_review_id,
            "scope_count": len(scopes),
            "submitted": submitted_count,
            "failed": failed_count,
            "results": scope_results,
        }

    # ── 骨架施工（纯程序，零模型调用）────────────────────────────────

    async def execute_skeleton_flow(
        self,
        *,
        workspace_id: UUID,
        repository_id: UUID,
        revision: str,
        selected_paths: list[str],
        run_id: str,
        document_title: str = "",
    ) -> dict:
        """骨架施工全流程：模块快照 → plan_skeleton → 骨架 Review 提交。

        返回结果携带 skeleton（供 worker 创建批次单元）；
        占位块带着目标符号的绑定创建，后续批次按绑定重叠匹配 → UPDATE_BLOCK 替换。
        """
        prepared = await self._prepare(
            workspace_id=workspace_id,
            repository_id=repository_id,
            revision=revision,
            selected_paths=selected_paths,
        )
        if prepared is None:
            return {
                "status": "EMPTY_SCOPE",
                "change_request_id": None,
                "summary": "无法从文件中检测到可分析的语义入口",
            }
        catalog, snap, scope = prepared
        skeleton = plan_skeleton(scope, catalog, document_title=document_title)

        files = list(scope.related_files)
        candidates = await self._doc.locate_documents(
            workspace_id, repository_id, files,
        )
        doc_ids: list[UUID] = []
        for c in candidates:
            if c.document_id not in doc_ids:
                doc_ids.append(c.document_id)
        structures: list = []
        if doc_ids:
            structures = await self._doc.read_structures(workspace_id, doc_ids)
        bindings_list = await self._bind.read_batch(
            workspace_id, repository_id, files,
        )

        plan = build_skeleton_plan(
            skeleton, snap, catalog, candidates, structures, bindings_list,
        )
        submit_result = await self._write.submit(
            plan,
            workspace_id=str(workspace_id),
            repository_id=str(repository_id),
            run_id=run_id,
        )
        review_id = (
            submit_result.get("changeRequestId")
            or submit_result.get("change_request_id")
        )
        return {
            "status": "SUBMITTED",
            "change_request_id": review_id,
            "slot_count": len(skeleton.slots),
            "batch_count": len(skeleton.batches),
            "skeleton": skeleton,
        }

    async def _prepare(
        self,
        *,
        workspace_id: UUID,
        repository_id: UUID,
        revision: str,
        selected_paths: list[str],
    ) -> tuple[AtomCatalog, ContextSnapshot, SemanticScope] | None:
        """读取源码 → 解析 → 范围 → 快照。返回 (catalog, snap, scope)。"""
        ctx = await self._ws.read_context(workspace_id, repository_id)
        repo = ctx.repository(repository_id)
        if repo is None:
            return None
        batch = self._filter.filter(
            repository_id, revision,
            [{"filePath": p, "readable": True, "sizeBytes": 0} for p in selected_paths],
        )
        selection = SelectedSourceFileBatch(
            repository_id=str(repository_id),
            revision=revision,
            paths=tuple(f.file_path for f in batch.files),
            total_count=batch.total_count,
        )
        sources = await self._src.read_batch(workspace_id, repository_id, selection)

        def read_source(path: str) -> str | None:
            for f in sources.files:
                if f.file_path == path:
                    return f.content
            return None

        catalog = parse_batch(sources, read_source)
        scopes, graph = build_execution_scopes(catalog, read_source, selected_paths)
        if not scopes:
            return None
        scope = scopes[0]
        shaped = shape_context(
            scope, catalog, read_source, graph,
            budget_chars=self._budget_chars,
        )
        snap = freeze_context(shaped)
        return catalog, snap, scope

    # ── 批次静态生成（Bounded Repair Loop，无工具循环）─────────────

    async def _run_batch_session(
        self,
        orch: AnalysisOrchestrator,
        request: SemanticAnalysisRequest,
        inline_sources: list[dict[str, Any]],
    ) -> SemanticAnalysisResult:
        """批次会话：源码内联一次调用输出 JSON；校验失败带名单 repair 反馈。"""
        if self._provider is None:
            raise RuntimeError("Provider not configured")

        last_error_result: SemanticAnalysisResult | None = None
        for attempt in range(orch.session.max_repairs + 1):
            try:
                result = await self._provider.analyze_batch(
                    request, inline_sources,
                )
            except Exception as exc:
                LOGGER.error(
                    "Batch analysis failed (attempt %s/%s): %s",
                    attempt + 1, orch.session.max_repairs + 1, exc,
                )
                if attempt == orch.session.max_repairs:
                    orch.mark_failed(str(exc))
                    return SemanticAnalysisResult(
                        analysis_id=orch.session.analysis_id,
                        context_id=orch.session.context_id,
                        revision=orch._snap.revision,
                        snapshot_hash=orch._snap.snapshot_hash,
                        overall_responsibility="SEMANTIC_ANALYSIS_FAILED",
                    )
                continue

            errors = orch.validate_result(result)
            if not errors:
                orch.mark_succeeded()
                return result

            LOGGER.warning(
                "Batch validation failed (attempt %s/%s): %s",
                attempt + 1, orch.session.max_repairs + 1, errors,
            )
            last_error_result = result
            if attempt >= orch.session.max_repairs:
                orch.mark_failed("; ".join(errors))
                return result
            orch.repair_policy(errors)
            request = request.model_copy(update={
                "instruction": (
                    request.instruction
                    + "\n\n【修复要求】\n"
                    + "\n".join(f"- {e}" for e in errors)
                    + "\n请按修复要求重新输出完整 JSON，不要解释。"
                ),
            })

        orch.mark_failed("exhausted all repair attempts")
        return last_error_result or SemanticAnalysisResult(
            analysis_id=orch.session.analysis_id,
            context_id=orch.session.context_id,
            revision=orch._snap.revision,
            snapshot_hash=orch._snap.snapshot_hash,
            overall_responsibility="SEMANTIC_ANALYSIS_EXHAUSTED",
        )

    # ── Bounded Repair Loop ──────────────────────────────────────

    async def _run_semantic_session(
        self,
        orch: AnalysisOrchestrator,
        request: SemanticAnalysisRequest,
    ) -> SemanticAnalysisResult:
        """运行一次语义分析会话, 含 Bounded Repair Loop。

        接入真实 DeepSeek provider，处理工具调用循环。
        每次 repair 会把校验错误（含缺失槽位名单）追加到 instruction 反馈给模型，
        而不是重跑同一请求。
        """
        if self._provider is None:
            raise RuntimeError("Provider not configured")

        async def tool_handler(name: str, args: dict) -> dict:
            return orch.handle_tool_call(name, args)

        # 主调用 + Bounded Repair
        last_error_result: SemanticAnalysisResult | None = None
        for attempt in range(orch.session.max_repairs + 1):
            try:
                result = await self._provider.analyze_semantics(
                    request, tool_handler
                )
            except Exception as exc:
                LOGGER.error(
                    "Semantic analysis failed (attempt %s/%s): %s",
                    attempt + 1, orch.session.max_repairs + 1, exc,
                )
                if attempt == orch.session.max_repairs:
                    orch.mark_failed(str(exc))
                    # 返回一个明确失败的空结果，让上层跳过后续处理
                    failed = SemanticAnalysisResult(
                        analysis_id=orch.session.analysis_id,
                        context_id=orch.session.context_id,
                        revision=orch._snap.revision,
                        snapshot_hash=orch._snap.snapshot_hash,
                        overall_responsibility="SEMANTIC_ANALYSIS_FAILED",
                    )
                    return failed
                continue

            errors = orch.validate_result(result)
            if not errors:
                orch.mark_succeeded()
                return result

            LOGGER.warning(
                "Semantic validation failed (attempt %s/%s): %s",
                attempt + 1, orch.session.max_repairs + 1, errors,
            )
            last_error_result = result
            if attempt >= orch.session.max_repairs:
                orch.mark_failed("; ".join(errors))
                return result
            orch.repair_policy(errors)
            # 把缺失/错误名单反馈给模型（此前 repair 只是重跑同一请求，等于没修）
            request = request.model_copy(update={
                "instruction": (
                    request.instruction
                    + "\n\n【修复要求】\n"
                    + "\n".join(f"- {e}" for e in errors)
                    + "\n请按修复要求重新输出完整 JSON，不要解释。"
                ),
            })

        orch.mark_failed("exhausted all repair attempts")
        return last_error_result or SemanticAnalysisResult(
            analysis_id=orch.session.analysis_id,
            context_id=orch.session.context_id,
            revision=orch._snap.revision,
            snapshot_hash=orch._snap.snapshot_hash,
            overall_responsibility="SEMANTIC_ANALYSIS_EXHAUSTED",
        )


def _bind_result_atoms(
    result: SemanticAnalysisResult,
    snap: ContextSnapshot,
) -> None:
    """把模型结果中的 symbol_key 引用绑定回 atom_id（唯一的 ID 处理点）。

    模型只引用可读的 symbol_key（如 PYTHON:path:Name:CLASS），不生成/记忆
    任何 hash 型 atom_id。此函数在进入 document 规划前，把语义结果里所有
    atom 引用经快照权威索引 atom_by_symbol 绑定为 atom_id，保证下游校验、
    evidence 构建、binding 解析使用一致主键。

    未命中索引的 symbol_key 保留原值（交由下游证据缺失兜底），不视为格式错误。
    引用自身的 ID（group_id / source_chunk_id / relation_id）不做改动。
    """
    if result.overall_responsibility in (
        "SEMANTIC_ANALYSIS_FAILED",
        "SEMANTIC_ANALYSIS_EXHAUSTED",
    ):
        return  # 失败哨兵，无有效引用可绑定

    resolve = snap.atom_by_symbol.get

    for group in result.semantic_groups:
        group.primary_atom_ids = [resolve(v, v) for v in group.primary_atom_ids]
        group.informed_by_atom_ids = [resolve(v, v) for v in group.informed_by_atom_ids]
        for ref in group.evidence_refs:
            ref.atom_id = resolve(ref.atom_id, ref.atom_id)

    for interp in result.member_interpretations:
        interp.atom_id = resolve(interp.atom_id, interp.atom_id)
        for ref in interp.evidence_refs:
            ref.atom_id = resolve(ref.atom_id, ref.atom_id)

    for step in result.execution_flow:
        step.atom_id = resolve(step.atom_id, step.atom_id)
        for ref in step.evidence_refs:
            ref.atom_id = resolve(ref.atom_id, ref.atom_id)


# ── 批次内联源码 ─────────────────────────────────────────────────────────


def _build_inline_sources(
    snap: ContextSnapshot,
    slots: tuple[SkeletonSlot, ...],
) -> list[dict[str, Any]]:
    """槽位目标符号的源码片段（来自快照 chunks）→ 内联进批次请求。

    静态生成的输入：模型不需要工具探索，源码直接给出；
    覆盖不到目标（快照缺陷）的槽位由覆盖校验器在会话后拦截。
    """
    sources: list[dict[str, Any]] = []
    seen: set[str] = set()
    for slot in slots:
        atom_id = snap.atom_by_symbol.get(slot.primary_symbol_key or "")
        atom = snap.atom_by_id.get(atom_id) if atom_id else None
        if atom is None:
            continue
        for cid in atom.chunk_ids:
            chunk = snap.chunk_by_id.get(cid)
            if chunk is None or chunk.chunk_id in seen:
                continue
            seen.add(chunk.chunk_id)
            sources.append({
                "symbol_key": slot.primary_symbol_key,
                "file_path": chunk.file_path,
                "start_line": chunk.start_line,
                "end_line": chunk.end_line,
                "source": chunk.source,
            })
    return sources


# ── 范围构建（按单元类型分支）─────────────────────────────────────────────


def build_execution_scopes(
    catalog: AtomCatalog,
    read_source: Callable[[str], str | None],
    selected_paths: list[str],
) -> tuple[tuple[SemanticScope, ...], RepositoryCodeGraph | None]:
    """按单元类型决定分析范围。

    多文件单元（SEMANTIC_ANALYSIS）：
      build_graph → discover_scopes（跨文件 BFS）→ 合并为恰好一个模块 scope。
      discover_scopes 无入口时回退 build_file_scopes 再合并，保证模块永远单 scope。
    单文件单元（CURRENT_FILE_ANALYSIS）：维持每文件一个 scope。

    返回 (scopes, graph)；graph 为 None 表示关系未构建（不会发生——
    两条路径都会构建图，供 shape_context 输出关系边）。
    """
    graph = build_graph(catalog, read_source)
    if len(selected_paths) > 1:
        scopes = discover_scopes(graph)
        if not scopes:
            scopes = build_file_scopes(catalog)
        merged = _merge_module_scopes(scopes)
        if not merged.members:
            # 模块文件全部无法解析（无任何符号）→ 空 scope，返回 EMPTY_SCOPE
            return (), graph
        # 补全模块文件内的全部符号：discover_scopes 只从 HTTP 路由入口
        # BFS（无路由文件如 blueprints/cli 会被整体排除），骨架/批次需要
        # 覆盖模块全部公开符号，快照必须包含完整上下文。
        # BFS 闭包继续提供角色/距离，补充符号按共享依赖处理。
        module_files = frozenset(m.file_path for m in catalog.modules)
        existing = merged.member_keys()
        extras = [
            s for s in catalog.symbols
            if symbol_key_file_path(s.symbol_key) in module_files
            and s.symbol_key not in existing
        ]
        if extras:
            extra_members = tuple(
                ScopeMember(s.symbol_key, "SHARED_DEPENDENCY", 2, ())
                for s in extras
            )
            merged = SemanticScope(
                scope_id=merged.scope_id,
                entries=merged.entries,
                members=tuple(sorted(
                    (*merged.members, *extra_members),
                    key=lambda m: (m.distance, m.symbol_key),
                )),
                boundary=merged.boundary,
                unresolved=merged.unresolved,
                related_files=tuple(sorted(
                    set(merged.related_files)
                    | {symbol_key_file_path(s.symbol_key) for s in extras}
                )),
            )
        return (merged,), graph
    return build_file_scopes(catalog), graph


def _merge_module_scopes(scopes: tuple[SemanticScope, ...]) -> SemanticScope:
    """把同一模块的所有入口 scope 合并为一个模块 scope。

    成员按 symbol_key 去重，保留到任意入口距离更短的一项；
    入口、边界、未解析、相关文件取并集。scope_id 由入口集合哈希，跨 revision 稳定。
    """
    entries = tuple(
        sorted({e for s in scopes for e in s.entries}, key=lambda e: e.symbol_key)
    )
    members: dict[str, ScopeMember] = {}
    for s in scopes:
        for m in s.members:
            old = members.get(m.symbol_key)
            if old is None or m.distance < old.distance:
                members[m.symbol_key] = m
    scope_id = "scope_" + hashlib.sha256(
        "\0".join(sorted(e.symbol_key for e in entries)).encode()
    ).hexdigest()[:24]
    return SemanticScope(
        scope_id=scope_id,
        entries=entries,
        members=tuple(
            sorted(members.values(), key=lambda m: (m.distance, m.symbol_key))
        ),
        boundary=tuple(sorted({b for s in scopes for b in s.boundary})),
        unresolved=tuple(sorted({u for s in scopes for u in s.unresolved})),
        related_files=tuple(
            sorted({f for s in scopes for f in s.related_files})
        ),
    )
