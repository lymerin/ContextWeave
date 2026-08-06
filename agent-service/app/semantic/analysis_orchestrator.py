"""语义分析编排器——Session 状态机 + Bounded Repair Loop。

一次 ContextSnapshot = 一次 SemanticAnalysisSession。
会话内部允许多轮 MCP 调用 + 最多 2 次 Repair。
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass, field
from enum import Enum
from typing import Any

from app.model_context_mcp.service_mcp_tool import McpContextTools
from app.schemas.model_context.snapshot import ContextSnapshot
from app.schemas.semantic.analysis_request import (
    AnalysisManifest,
    SemanticAnalysisRequest,
)
from app.schemas.semantic.analysis_result import SemanticAnalysisResult
from app.schemas.semantic.coverage import ContextCoverageReport
from app.semantic.context_coverage import CoverageTracker
from app.semantic.result_validator import ResultValidator


class SessionState(Enum):
    READING_CONTEXT = "READING_CONTEXT"
    GENERATING = "GENERATING"
    VALIDATING = "VALIDATING"
    REPAIR_FLASH = "REPAIR_FLASH"
    REPAIR_PRO = "REPAIR_PRO"
    SUCCEEDED = "SUCCEEDED"
    FAILED_REQUIRES_HUMAN = "FAILED_REQUIRES_HUMAN"


@dataclass
class AnalysisSession:
    analysis_id: str
    context_id: str
    state: SessionState = SessionState.READING_CONTEXT
    repair_attempts: int = 0
    max_repairs: int = 2
    coverage: ContextCoverageReport = field(
        default_factory=lambda: ContextCoverageReport("")
    )


class AnalysisOrchestrator:
    """编排一次语义分析会话。"""

    def __init__(
        self,
        snapshot: ContextSnapshot,
        mcp_tools: McpContextTools,
        *,
        max_repairs: int = 2,
        required_slots: tuple = (),
    ) -> None:
        self._snap = snapshot
        self._tools = mcp_tools
        self._tracker = CoverageTracker(snapshot)
        self._validator = ResultValidator(snapshot, required_slots=required_slots)
        self._session = AnalysisSession(
            analysis_id=str(uuid.uuid4()),
            context_id=snapshot.context_id,
            max_repairs=max_repairs,
        )

    @property
    def session(self) -> AnalysisSession:
        self._session.coverage = self._tracker.compute()
        return self._session

    @property
    def state(self) -> SessionState:
        return self._session.state

    # ── 请求构建 ──────────────────────────────────────────────────

    def build_request(self) -> SemanticAnalysisRequest:
        return SemanticAnalysisRequest(
            analysis_id=self._session.analysis_id,
            context_id=self._snap.context_id,
            revision=self._snap.revision,
            snapshot_hash=self._snap.snapshot_hash,
            entry_point_ids=list(
                self._snap.atom_by_symbol.get(aid, aid)
                for aid in self._snap.manifest.required_atom_ids
            )[:10],
            structure_block_ids=list(self._snap.manifest.required_block_ids),
            manifest=AnalysisManifest(
                atom_count=self._snap.manifest.atom_count,
                block_count=self._snap.manifest.block_count,
                chunk_count=self._snap.manifest.source_chunk_count,
                relation_count=self._snap.manifest.relation_count,
            ),
        )

    # ── MCP 工具转发 ──────────────────────────────────────────────

    def handle_tool_call(
        self, tool_name: str, arguments: dict[str, Any]
    ) -> dict[str, Any]:
        ctx_id = arguments.get("context_id", self._snap.context_id)

        if tool_name == "get_context_overview":
            result = self._tools.get_context_overview(ctx_id)
            self._tracker.mark_overview_read()
            return result

        elif tool_name == "get_structure_block":
            result = self._tools.get_structure_block(
                ctx_id, arguments.get("block_id", "")
            )
            if "coverage" in result:
                self._tracker.record(set(result["coverage"]))
            return result

        elif tool_name == "get_atom_detail":
            result = self._tools.get_atom_detail(
                ctx_id, arguments.get("symbol_key", "")
            )
            if "coverage" in result:
                self._tracker.record(set(result["coverage"]))
            return result

        elif tool_name == "trace_structure_path":
            result = self._tools.trace_structure_path(
                ctx_id, arguments.get("entry_label", "")
            )
            if "coverage" in result:
                self._tracker.record(set(result["coverage"]))
            return result

        elif tool_name == "search_context_symbols":
            return self._tools.search_context_symbols(
                ctx_id, arguments.get("query", "")
            )

        return {"error": f"unknown tool: {tool_name}"}

    # ── Coverage Gate ─────────────────────────────────────────────

    def can_submit_final(self) -> tuple[bool, str]:
        report = self._tracker.compute()
        if not report.complete:
            return False, report.summary
        return True, "complete"

    # ── Bounded Repair Loop ───────────────────────────────────────

    def validate_result(self, result: SemanticAnalysisResult) -> list[str]:
        return self._validator.validate(result)

    def repair_policy(self, errors: list[str]) -> SessionState:
        """根据当前状态和错误决定下一步。"""
        self._session.repair_attempts += 1

        if self._session.repair_attempts > self._session.max_repairs:
            self._session.state = SessionState.FAILED_REQUIRES_HUMAN
            return self._session.state

        if self._session.repair_attempts == 1:
            # 第一次: Flash Repair — 同模型, 只修正校验字段
            self._session.state = SessionState.REPAIR_FLASH
        else:
            # 第二次: Pro Repair — 完整重输出
            self._session.state = SessionState.REPAIR_PRO

        return self._session.state

    def mark_succeeded(self) -> None:
        self._session.state = SessionState.SUCCEEDED

    def mark_failed(self, reason: str) -> None:
        self._session.state = SessionState.FAILED_REQUIRES_HUMAN
        self._session.repair_attempts = self._session.max_repairs + 1
