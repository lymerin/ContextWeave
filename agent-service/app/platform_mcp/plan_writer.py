"""平台 MCP 计划提交——内部 AgentPlan → MCP 兼容格式。

内部 AgentPlan 以 SectionBindingSet 表达完整绑定状态。
MCP 后端仍接受 BindingProposal[]（增量操作）。

转换在此完成，不在核心领域层。
"""

import hashlib
import json
import logging

from app.clients.mcp_client import ReviewMcpClient
from app.schemas.document_planner.plan import AgentPlan

LOGGER = logging.getLogger(__name__)

# 平台限制：单条变更请求最多 50 条绑定提案（mcp-server max-bindings）。
# 超出后 MCP 服务端直接拒绝整条请求，因此提交前按优先级截断。
_PLATFORM_MAX_BINDINGS = 50


class PlanWriter:
    """提交 AgentPlan 到平台 MCP。"""

    def __init__(self, client: ReviewMcpClient) -> None:
        self._client = client

    async def submit(
        self,
        plan: AgentPlan,
        *,
        workspace_id: str,
        run_id: str,
        repository_id: str,
        authorization: str = "delegated",
    ) -> dict:
        """将内部 AgentPlan 转为 MCP 兼容格式并提交。

        转换规则:
          - document_operations → AgentPlan.operations
          - section_binding_sets 中的每条 SectionBinding → 一条 BindingProposal
          - evidence 从 planned_sections 派生
        """
        # clientRequestId 带计划内容指纹：同内容重试 → knowledge-core 幂等重放；
        # 内容变化（如骨架 Apply 后 CREATE→UPDATE）→ 新 ID → 新变更请求。
        # 固定 run_id 会导致内容变化时 IDEMPOTENCY_CONFLICT（409）。
        fingerprint = _plan_fingerprint(plan)
        mcp_payload = {
            "clientRequestId": f"agent-{run_id}-{fingerprint}",
            "workspaceId": workspace_id,
            "summary": plan.summary,
            "rationale": plan.rationale,
        }

        # operations
        mcp_payload["operations"] = []
        for op in plan.document_operations:
            operation = {
                "clientOperationId": op.client_operation_id,
                "sequenceNumber": op.sequence_number,
                "operationType": op.operation_type,
                "documentId": str(op.document_id) if op.document_id else None,
                "blockId": str(op.block_id) if op.block_id else None,
                "createdDocumentClientOperationId": op.created_document_op_id,
                "proposedDocumentTitle": op.proposed_document_title,
                "proposedBlockType": "PARAGRAPH",
                "proposedPlainText": op.proposed_plain_text,
                "proposedContentFormat": "MARKDOWN",
            }
            if op.base_block_version is not None:
                operation["baseBlockVersion"] = op.base_block_version
            mcp_payload["operations"].append(operation)

        # bindingProposals
        proposals: list[dict] = []
        seq = len(plan.document_operations)
        for bs in plan.section_binding_sets:
            for binding in bs.bindings:
                seq += 1
                proposal: dict = {
                    "clientBindingProposalId": f"binding-{seq}-{binding.atom_id[-8:]}",
                    "sequenceNumber": seq,
                    "action": "UPSERT_BINDING",
                    "repositoryId": repository_id,
                    "revision": plan.revision,
                    "filePath": binding.file_path,
                    "anchorKind": "SYMBOL",
                    "symbolKey": binding.symbol_key,
                    "startLine": binding.start_line,
                    "endLine": binding.end_line,
                    "bindingRole": binding.role,
                    "bindingOrdinal": binding.ordinal,
                    "reason": f"语义分析绑定: {plan.summary}",
                    "confidence": 1.0,
                }
                if binding.created_block_operation_id:
                    proposal["createdBlockClientOperationId"] = (
                        binding.created_block_operation_id
                    )
                # 文档目标——knowledge-core 要求 binding 指定 documentId 或
                # createdDocumentClientOperationId 其中之一。
                if binding.document_id:
                    proposal["documentId"] = str(binding.document_id)
                elif binding.created_document_op_id:
                    proposal["createdDocumentClientOperationId"] = (
                        binding.created_document_op_id
                    )
                proposals.append(proposal)

        # 平台限制截断：大模块总览 batch 的 SUPPORTING 绑定可能超过 50 条。
        # 按优先级保留，其余丢弃并记日志（traceability 降级，不阻断文档内容）。
        proposals = _truncate_proposals(proposals)

        # reconcile：匹配块上已过期的绑定 → REMOVE_BINDING
        for bs in plan.section_binding_sets:
            for stale in bs.stale_bindings:
                seq += 1
                proposals.append({
                    "clientBindingProposalId": f"binding-remove-{seq}",
                    "sequenceNumber": seq,
                    "action": "REMOVE_BINDING",
                    "repositoryId": repository_id,
                    "revision": plan.revision,
                    "filePath": stale.file_path,
                    "bindingId": stale.binding_id,
                    "documentId": stale.document_id,
                    "blockId": stale.block_id,
                    "reason": "语义分析收敛：该绑定不再属于此文档区块",
                    "confidence": 1.0,
                })

        mcp_payload["bindingProposals"] = proposals

        # evidence —— 每个 binding 是它所属 block 的一条证据，不排列组合。
        # 直接从保留的 proposals 派生（与 binding 一对一），截断后的
        # evidence 数量天然 ≤ 保留绑定数，不会单独超限。
        # evidence 只针对本次创建的块（UPDATE_BLOCK 的绑定是既有块，
        # clientOperationId 为空会触发 knowledge-core 引用校验失败）
        mcp_payload["evidence"] = [
            {
                "clientOperationId": (
                    p.get("createdBlockClientOperationId")
                    or p.get("createdDocumentClientOperationId") or ""
                ),
                "repositoryId": repository_id,
                "filePath": p["filePath"],
                "startLine": p["startLine"],
                "endLine": p["startLine"],  # 只指起始行，避免截断解析行号越界
                "description": f"语义分析证据: {plan.summary}",
            }
            for p in proposals
            if p.get("createdBlockClientOperationId")
            or p.get("createdDocumentClientOperationId")
        ]

        # 复用现有 MCP 客户端提交逻辑
        # 通过 submit_document_change 内部协议调用
        return await self._client.submit_document_change(
            mcp_payload,
            workspace_id=workspace_id,
            run_id=run_id,
            authorization=authorization,
        )


def _truncate_proposals(proposals: list[dict]) -> list[dict]:
    """平台 max-bindings=50 约束下保留最高优先级绑定。

    优先级（stable，同优先级保持原 sequenceNumber 顺序）:
      0. PRIMARY 锚点绑定
      1. 带创建块操作的绑定（新建块需要绑定才能被 trace 到）
      2. 既有块上的 SUPPORTING 绑定
      3. REMOVE_BINDING（过期清理——丢弃只影响收敛，不影响新内容）

    被丢弃的绑定记 warning 日志，数量计入审计。
    """
    if len(proposals) <= _PLATFORM_MAX_BINDINGS:
        return proposals

    def priority(p: dict) -> tuple[int, int]:
        seq = p.get("sequenceNumber", 0)
        if p.get("action") == "REMOVE_BINDING":
            return (3, seq)
        if p.get("bindingRole") == "PRIMARY":
            return (0, seq)
        if p.get("createdBlockClientOperationId") or p.get(
            "createdDocumentClientOperationId"
        ):
            return (1, seq)
        return (2, seq)

    kept = sorted(proposals, key=priority)[:_PLATFORM_MAX_BINDINGS]
    dropped = len(proposals) - len(kept)
    LOGGER.warning(
        "binding proposals truncated: %d -> %d (platform max %d); "
        "dropped %d low-priority bindings (traceability degraded)",
        len(proposals), len(kept), _PLATFORM_MAX_BINDINGS, dropped,
    )
    return kept


def _plan_fingerprint(plan: AgentPlan) -> str:
    """计划内容指纹——operations + 绑定集合的确定性哈希。"""
    operations = [
        {
            "type": op.operation_type,
            "doc": str(op.document_id) if op.document_id else None,
            "block": str(op.block_id) if op.block_id else None,
            "create_doc": op.created_document_op_id,
            "version": op.base_block_version,
            "text": op.proposed_plain_text,
        }
        for op in plan.document_operations
    ]
    bindings = [
        [
            {
                "atom": b.atom_id,
                "file": b.file_path,
                "symbol": b.symbol_key,
                "lines": [b.start_line, b.end_line],
                "role": b.role,
                "ordinal": b.ordinal,
            }
            for b in bs.bindings
        ]
        for bs in plan.section_binding_sets
    ]
    stale = [
        [s.binding_id for s in bs.stale_bindings]
        for bs in plan.section_binding_sets
    ]
    raw = json.dumps(
        [operations, bindings, stale], ensure_ascii=False, sort_keys=True
    )
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:16]
