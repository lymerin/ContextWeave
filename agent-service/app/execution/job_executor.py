"""Agent 管线编排器——按序调各层模块，不包含分析逻辑。

编排顺序:
  1. platform_mcp 读取源码
  2. source_selection 粗筛
  3. source_analysis 四步分析
  4. model_context_mcp 冻结快照
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
from app.source_analysis.atom_relation_graph import build_graph
from app.source_analysis.code_ast_atom import parse_batch
from app.source_analysis.graph_entry_scope import discover_scopes
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
    ) -> dict:
        """执行完整的源码分析 → 语义补充 → 文档规划 → 提交管线。"""
        run_id = f"exec-{repository_id}-{revision[:8]}"

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

        # ── 2. 源码分析 ─────────────────────────────────────────
        def read_source(path: str) -> str | None:
            for f in sources.files:
                if f.file_path == path:
                    return f.content
            return None

        catalog = parse_batch(sources, read_source)
        graph = build_graph(catalog, read_source)
        scopes = discover_scopes(graph)

        if not scopes:
            return {"status": "NO_CHANGE", "summary": "无可分析的语义范围"}

        results: list[dict] = []

        for scope in scopes:
            shaped = shape_context(scope, catalog, read_source)

            # ── 3. 快照 ──────────────────────────────────────
            snap = freeze_context(shaped)
            self._registry.register(snap)
            analysis_id = snap.context_id
            self._registry.acquire(snap.context_id, analysis_id)

            try:
                tools = McpContextTools(self._registry)
                orch = AnalysisOrchestrator(snap, tools)

                # ── 4. 语义补充 (Bounded Repair) ─────────────
                result = await self._run_semantic_session(orch)

                # ── 5. Gates ─────────────────────────────────
                errors = orch.validate_result(result)
                if errors:
                    LOGGER.warning("Semantic result validation failed: %s", errors)
                    results.append({"error": "VALIDATION_FAILED", "details": errors})
                    continue

                # ── 6. 读取文档侧 ────────────────────────────
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

                # ── 7. 文档规划 ─────────────────────────────
                evidence = build(snap, catalog)
                sections = compose_document(result, title=result.overall_responsibility[:80] or "代码职责说明")
                binding_sets = resolve_bindings(sections, evidence)
                targets = resolve_targets(
                    sections, candidates, structures, bindings_list,
                )

                # ── 8. 组装 + 提交 ──────────────────────────
                plan = assemble_and_validate(
                    sections, binding_sets, evidence,
                    context_id=snap.context_id,
                    revision=snap.revision,
                    snapshot_hash=snap.snapshot_hash,
                    section_targets=targets,
                )
                # plan_writer 内部检查 document_id 完整性
                submit_result = await self._write.submit(
                    plan,
                    workspace_id=str(workspace_id),
                    repository_id=str(repository_id),
                    run_id=run_id,
                )
                results.append(submit_result)

            except PlanValidationError as exc:
                LOGGER.error("Plan validation failed: %s", exc.issues)
                results.append({"error": "PLAN_VALIDATION_FAILED", "details": exc.issues})
            except Exception:
                LOGGER.exception("Execution failed for scope %s", scope.scope_id)
                results.append({"error": "EXECUTION_FAILED"})
            finally:
                self._registry.release(snap.context_id, analysis_id)

        return {
            "status": "COMPLETED",
            "scope_count": len(scopes),
            "submitted": sum(1 for r in results if "error" not in r),
            "results": results,
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
        errors: list[str] = []
        for attempt in range(orch.session.max_repairs + 1):
            try:
                result = await self._provider.analyze_semantics(
                    request, tool_handler
                )
            except Exception as exc:
                LOGGER.error("Semantic analysis failed: %s", exc)
                if attempt == orch.session.max_repairs:
                    orch.mark_failed(str(exc))
                    return SemanticAnalysisResult(
                        analysis_id=orch.session.analysis_id,
                        context_id=orch.session.context_id,
                        revision=orch._snap.revision,       # noqa: SLF001
                        snapshot_hash=orch._snap.snapshot_hash,  # noqa: SLF001
                    )
                continue

            errors = orch.validate_result(result)
            if not errors:
                orch.mark_succeeded()
                return result

            LOGGER.warning(
                "Semantic validation failed (attempt %s/%s): %s",
                attempt + 1, orch.session.max_repairs + 1, errors,
            )
            if attempt >= orch.session.max_repairs:
                orch.mark_failed("; ".join(errors))
                return result
            orch.repair_policy(errors)

        return SemanticAnalysisResult(
            analysis_id=orch.session.analysis_id,
            context_id=orch.session.context_id,
            revision=orch._snap.revision,
            snapshot_hash=orch._snap.snapshot_hash,
        )
