"""漂移结果提交 —— 将漂移检测结果映射为 BindingProposal。

将 BindingReResolution 结果转换为 MCP submit_document_change 格式，
使漂移修复走现有的 PENDING → APPLIED / REJECTED 审查流水线。

转换规则:
  - COSMETIC / SIGNATURE_CHANGED / SYMBOL_MOVED → UPSERT_BINDING（更新绑定）
  - SYMBOL_REMOVED / FILE_REMOVED → REMOVE_BINDING（移除绑定）
  - NONE → 跳过，不生成 proposal
"""

from __future__ import annotations

from typing import Any
from uuid import uuid4

from app.clients.mcp_client import ReviewMcpClient
from app.schemas.ast_atom import symbol_key_file_path
from app.schemas.drift import BindingReResolution, DriftLevel


class DriftWriter:
    """将漂移检测结果提交为文档变更请求。"""

    def __init__(self, client: ReviewMcpClient) -> None:
        self._client = client

    async def submit(
        self,
        resolutions: list[BindingReResolution],
        *,
        workspace_id: str,
        repository_id: str,
        revision: str,
        run_id: str = "",
    ) -> dict[str, Any]:
        """根据漂移结果构建 BindingProposal 并提交。

        仅对非 NONE 的结果生成操作，按严重程度排序。
        全部 NONE 时直接返回 NO_DRIFT 状态，不提交。
        """
        actionable = [
            r for r in resolutions
            if r.drift_level != DriftLevel.NONE
        ]
        if not actionable:
            return {"status": "NO_DRIFT", "message": "未检测到任何文档漂移。"}

        # 按严重程度排序（最严重的在前）
        actionable.sort(key=lambda r: _severity(r.drift_level), reverse=True)

        run = run_id or uuid4().hex[:12]

        payload: dict[str, Any] = {
            "clientRequestId": f"drift-{run}",
            "workspaceId": workspace_id,
            "summary": f"漂移检测: {len(actionable)} 条绑定受影响",
            "rationale": _build_rationale(actionable),
            "operations": [],
            "bindingProposals": [
                _build_proposal(r, repository_id, revision, i)
                for i, r in enumerate(actionable, start=1)
            ],
            "evidence": [
                _build_evidence(r, repository_id)
                for r in actionable
            ],
        }

        return await self._client.submit_document_change(
            payload,
            workspace_id=workspace_id,
            run_id=run,
            authorization="delegated",
        )


# ── Proposal 构建 ────────────────────────────────────────────────────────────


def _build_proposal(
    resolution: BindingReResolution,
    repository_id: str,
    revision: str,
    seq: int,
) -> dict[str, Any]:
    """从单条漂移结果构建 BindingProposal。"""
    r = resolution

    if r.drift_level in (DriftLevel.SYMBOL_REMOVED, DriftLevel.FILE_REMOVED):
        action = "REMOVE_BINDING"
    else:
        action = "UPSERT_BINDING"

    proposal: dict[str, Any] = {
        "clientBindingProposalId": f"drift-{seq}-{r.binding_id.hex[:8]}",
        "sequenceNumber": seq,
        "action": action,
        "repositoryId": repository_id,
        "revision": revision,
        "documentId": str(r.document_id),
        "reason": r.drift_detail,
        "confidence": 1.0,
    }

    if r.block_id:
        proposal["blockId"] = str(r.block_id)

    if action == "UPSERT_BINDING" and r.new_atom:
        proposal["filePath"] = symbol_key_file_path(r.new_atom.symbol_key)
        proposal["anchorKind"] = "SYMBOL"
        proposal["symbolKey"] = r.new_atom.symbol_key
        proposal["startLine"] = r.new_atom.start_line
        proposal["endLine"] = r.new_atom.end_line

    return proposal


def _build_evidence(
    resolution: BindingReResolution,
    repository_id: str,
) -> dict[str, Any]:
    """为漂移结果构建一条 Evidence。"""
    r = resolution
    atom = r.new_atom or r.old_atom
    file_path = symbol_key_file_path(atom.symbol_key) if atom else ""

    return {
        "repositoryId": repository_id,
        "filePath": file_path,
        "startLine": atom.start_line if atom else None,
        "endLine": atom.start_line if atom else None,
        "description": f"漂移 [{r.drift_level.value}]: {r.drift_detail}",
    }


def _build_rationale(resolutions: list[BindingReResolution]) -> str:
    """生成漂移检测的总结理由。"""
    by_level: dict[DriftLevel, int] = {}
    for r in resolutions:
        by_level[r.drift_level] = by_level.get(r.drift_level, 0) + 1

    lines = ["自动漂移检测发现以下问题:"]
    for level, count in sorted(
        by_level.items(),
        key=lambda item: _severity(item[0]),
        reverse=True,
    ):
        lines.append(f"- {count} 条绑定: {level.value}（{_level_label(level)}）")

    lines.append("\n批准以更新过时的代码-文档绑定。")
    return "\n".join(lines)


# ── 辅助 ──────────────────────────────────────────────────────────────────────


def _severity(level: DriftLevel) -> int:
    """返回漂移级别的严重程度数值（越大越严重）。"""
    _order = {
        DriftLevel.NONE: 0,
        DriftLevel.COSMETIC: 1,
        DriftLevel.SIGNATURE_CHANGED: 2,
        DriftLevel.SYMBOL_MOVED: 3,
        DriftLevel.SYMBOL_REMOVED: 4,
        DriftLevel.FILE_REMOVED: 5,
    }
    return _order.get(level, 0)


def _level_label(level: DriftLevel) -> str:
    """返回漂移级别的人类可读标签。"""
    _labels = {
        DriftLevel.NONE: "无需变更",
        DriftLevel.COSMETIC: "行号偏移，更新绑定位置即可",
        DriftLevel.SIGNATURE_CHANGED: "签名变更，需审阅文档内容",
        DriftLevel.SYMBOL_MOVED: "符号移动到其他文件",
        DriftLevel.SYMBOL_REMOVED: "符号被删除，绑定失效",
        DriftLevel.FILE_REMOVED: "文件被删除，绑定失效",
    }
    return _labels.get(level, "")
