"""Agent 管线编排器——按序调各层模块，不包含分析逻辑。

编排顺序:
  1. platform_mcp 读取源码
  2. source_selection 粗筛
  3. source_analysis AST 解析 + 直接范围构造（跳过关系图）
  4. model_context_mcp 上下文整形 + 冻结快照
  5. semantic DeepSeek 语义补充 (Bounded Repair)
  6. Gates 校验
  7. platform_mcp 读取文档侧
  8. document_planner 文档规划 + 目标解析 + 绑定
  9. platform_mcp.plan_writer 提交
"""

from __future__ import annotations

import logging
from uuid import UUID

from app.document_planner.binding_resolver import resolve_bindings
from app.document_planner.document_composer import compose_document
from app.document_planner.evidence_catalog_builder import build
from app.document_planner.plan_validator import PlanValidationError, assemble_and_validate
from app.document_planner.target_resolver import resolve_targets
from app.model_context_mcp.context_freeze_snapshot import freeze_context
from app.model_context_mcp.snapshot_store_registry import SnapshotStoreRegistry
from app.model_context_mcp.service_mcp_tool import McpContextTools
from app.platform_mcp.binding_reader import BindingReader
from app.platform_mcp.document_reader import DocumentReader
from app.platform_mcp.plan_writer import PlanWriter
from app.platform_mcp.source_reader import SourceReader
from app.platform_mcp.workspace_reader import WorkspaceReader
from app.schemas.platform_mcp.source_file import SelectedSourceFileBatch
from app.semantic.analysis_orchestrator import AnalysisOrchestrator
from app.source_analysis.code_ast_atom import parse_batch
from app.source_analysis.graph_entry_scope import build_file_scopes
from app.source_analysis.scope_shape_context import shape_context
from app.source_selection.file_filter import SourceFileFilter

LOGGER = logging.getLogger("devcollab.agent.executor")


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
    ) -> None:
        self._ws = workspace_reader
        self._src = source_reader
        self._doc = document_reader
        self._bind = binding_reader
        self._write = plan_writer
        self._filter = file_filter
        self._registry = registry
        self._provider = provider

    async def execute(
        self,
        *,
        workspace_id: UUID,
        repository_id: UUID,
        revision: str,
        selected_paths: list[str],
        run_id: str,
    ) -> dict:
        """执行完整的源码分析 → 语义补充 → 文档规划 → 提交管线。

        run_id 由调用方提供且每次执行唯一，用于生成幂等 clientRequestId，
        避免对同一仓库+revision 重复提交被 knowledge-core 幂等校验拒绝。
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
        scopes = build_file_scopes(catalog)

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
            shaped = shape_context(scope, catalog, read_source)

            snap = freeze_context(shaped)
            self._registry.register(snap)
            self._registry.acquire(snap.context_id, snap.context_id)

            try:
                tools = McpContextTools(self._registry)
                orch = AnalysisOrchestrator(snap, tools)

                result = await self._run_semantic_session(orch)

                # 模型只返回可读的 symbol_key；在此一次性绑定回 atom_id，
                # 使下游校验/evidence/binding 全链路使用一致主键。
                _bind_result_atoms(result, snap)

                errors = orch.validate_result(result)
                if errors:
                    LOGGER.warning("Semantic result validation failed: %s", errors)
                    failed_count += 1
                    scope_results.append({
                        "error": "VALIDATION_FAILED",
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
                sections = compose_document(
                    result,
                    title=result.overall_responsibility[:80] or "代码职责说明",
                )
                targets = resolve_targets(
                    sections, candidates, structures, bindings_list,
                )
                binding_sets = resolve_bindings(sections, evidence, targets)

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

    # ── Bounded Repair Loop ──────────────────────────────────────

    async def _run_semantic_session(
        self, orch: AnalysisOrchestrator
    ) -> SemanticAnalysisResult:
        """运行一次语义分析会话, 含 Bounded Repair Loop。

        接入真实 DeepSeek provider，处理工具调用循环。
        """
        from app.schemas.semantic.analysis_result import SemanticAnalysisResult

        if self._provider is None:
            raise RuntimeError("Provider not configured")

        request = orch.build_request()

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

        orch.mark_failed("exhausted all repair attempts")
        return last_error_result or SemanticAnalysisResult(
            analysis_id=orch.session.analysis_id,
            context_id=orch.session.context_id,
            revision=orch._snap.revision,
            snapshot_hash=orch._snap.snapshot_hash,
            overall_responsibility="SEMANTIC_ANALYSIS_EXHAUSTED",
        )


def _bind_result_atoms(
    result: "SemanticAnalysisResult",
    snap: "ContextSnapshot",
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
