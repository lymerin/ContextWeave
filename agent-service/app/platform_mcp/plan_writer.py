"""平台 MCP 计划提交——内部 AgentPlan → MCP 兼容格式。

内部 AgentPlan 以 SectionBindingSet 表达完整绑定状态。
MCP 后端仍接受 BindingProposal[]（增量操作）。

转换在此完成，不在核心领域层。
"""

from uuid import UUID

from app.clients.mcp_client import ReviewMcpClient
from app.schemas.document_planner.plan import AgentPlan


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
        mcp_payload = {
            "clientRequestId": f"agent-{run_id}",
            "workspaceId": workspace_id,
            "repositoryId": repository_id,
            "revision": plan.revision,
            "summary": plan.summary,
            "rationale": plan.rationale,
            "sourceType": "MCP",
        }

        # operations
        mcp_payload["operations"] = [
            {
                "clientOperationId": op.client_operation_id,
                "sequenceNumber": op.sequence_number,
                "operationType": op.operation_type,
                "documentId": str(op.document_id) if op.document_id else None,
                "blockId": str(op.block_id) if op.block_id else None,
                "createdDocumentClientOperationId": op.created_document_op_id,
                "proposedBlockType": "PARAGRAPH",
                "proposedPlainText": op.proposed_plain_text,
                "proposedContentFormat": "MARKDOWN",
            }
            for op in plan.document_operations
        ]

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
                proposals.append(proposal)

        mcp_payload["bindingProposals"] = proposals

        # evidence
        mcp_payload["evidence"] = [
            {
                "clientOperationId": op.client_operation_id,
                "repositoryId": repository_id,
                "filePath": binding.file_path,
                "startLine": binding.start_line,
                "endLine": binding.end_line,
                "description": f"语义分析证据: {plan.summary}",
            }
            for op in plan.document_operations
            for bs in plan.section_binding_sets
            for binding in bs.bindings
        ]

        # 复用现有 MCP 客户端提交逻辑
        # 通过 submit_document_change 内部协议调用
        return await self._client.submit_document_change(
            mcp_payload,
            workspace_id=workspace_id,
            run_id=run_id,
            authorization=authorization,
        )
