from __future__ import annotations

import logging
from pathlib import Path
from typing import Any
from uuid import UUID

import pytest
from conftest import FakeMcpClient

from app.config import Settings
from app.graph.document_sync_workflow import DocumentSyncWorkflow
from app.schemas.binding_plans import BindingPlan, BindingRole, BindingSelection
from app.schemas.plans import AgentPlan

REPOSITORY_ID = UUID("22222222-2222-2222-2222-222222222222")
WORKSPACE_ID = "11111111-1111-1111-1111-111111111111"
REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
SOURCE_PATHS = (
    "agent-review-service/app/main.py",
    "agent-review-service/app/schemas.py",
    "agent-review-service/app/domain.py",
    "agent-review-service/app/rules.py",
)


class RepairingProvider:
    def __init__(self) -> None:
        self.document_calls: list[dict[str, Any]] = []
        self.binding_calls: list[dict[str, Any]] = []

    async def plan_document_sync(
        self,
        context_bundle: dict[str, Any],
        *,
        previous_plan: dict[str, Any] | None = None,
        validation_errors: list[dict[str, str]] | None = None,
    ) -> AgentPlan:
        self.document_calls.append(
            {
                "context": context_bundle,
                "previousPlan": previous_plan,
                "validationErrors": validation_errors,
            }
        )
        block_plans = context_bundle["documentBlockPlans"]
        if previous_plan is None:
            return _agent_plan(block_plans[:-1])
        return _agent_plan(block_plans)

    async def plan_block_bindings(
        self,
        candidates: dict[str, Any],
        *,
        previous_plan: dict[str, Any] | None = None,
        validation_errors: list[dict[str, str]] | None = None,
    ) -> BindingPlan:
        self.binding_calls.append(candidates)
        selections: list[BindingSelection] = []
        for block in candidates["documentBlockPlans"]:
            primary = block["primaryCandidateIds"][0]
            selections.append(
                BindingSelection(
                    blockKey=block["blockKey"],
                    codeCandidateId=primary,
                    role=BindingRole.PRIMARY,
                    ordinal=1,
                    reason="该候选承担当前文档块描述的主要代码职责。",
                    confidence=0.99,
                )
            )
            supporting_ids = [
                candidate_id
                for candidate_id in block["requiredCandidateIds"]
                if candidate_id != primary
            ]
            selections.extend(
                BindingSelection(
                    blockKey=block["blockKey"],
                    codeCandidateId=candidate_id,
                    role=BindingRole.SUPPORTING,
                    ordinal=ordinal,
                    reason="该候选为当前文档块提供必要的协作代码证据。",
                    confidence=0.95,
                )
                for ordinal, candidate_id in enumerate(supporting_ids, start=2)
            )
        return BindingPlan(selections=selections)

    async def plan_project_units(self, *_args: Any, **_kwargs: Any) -> Any:
        raise AssertionError("project planner must not run")


@pytest.mark.asyncio
async def test_real_failure_fixture_repairs_once_then_submits_pending_review(
    settings: Settings,
    caplog: pytest.LogCaptureFixture,
) -> None:
    caplog.set_level(logging.WARNING, logger="devcollab.agent.document_plan")
    provider = RepairingProvider()
    mcp = FakeMcpClient()

    async def on_status(*_args: Any) -> None:
        return None

    workflow = DocumentSyncWorkflow(mcp, provider, settings, on_status)
    result = await workflow.execute_context_bundle(
        {
            "run_id": "binding-plan-repair-fixture",
            "workspace_id": WORKSPACE_ID,
            "authorization": "Bearer transient",
            "trace_events": [],
            "context_bundle": _context_bundle(),
        }
    )

    assert len(provider.document_calls) == 2
    initial_errors = provider.document_calls[1]["validationErrors"]
    assert initial_errors == [
        {
            "path": "operations",
            "code": "DOCUMENT_BLOCK_PLAN_MISMATCH",
            "message": "Document operations must match the supplied blockKey set exactly",
        }
    ]
    diagnostic_log = "\n".join(caplog.messages)
    assert "runId=binding-plan-repair-fixture" in diagnostic_log
    assert "DOCUMENT_BLOCK_PLAN_MISMATCH" in diagnostic_log
    assert "candidate_" in diagnostic_log
    repaired_context = provider.document_calls[1]["context"]
    repaired_plan = provider.document_calls[1]["previousPlan"]
    expected_keys = [
        item["blockKey"] for item in repaired_context["documentBlockPlans"]
    ]
    assert [
        operation["clientOperationId"]
        for operation in repaired_plan["operations"]
        if operation["operationType"] in {"ADD_BLOCK", "UPDATE_BLOCK"}
    ] == expected_keys[:-1]

    assert result["change_request_id"] == "99999999-9999-9999-9999-999999999999"
    assert len(mcp.submissions) == 1
    submitted = mcp.submissions[0][0]
    content_operations = [
        operation
        for operation in submitted.operations
        if operation.operationType.value in {"ADD_BLOCK", "UPDATE_BLOCK"}
    ]
    assert [item.clientOperationId for item in content_operations] == expected_keys
    assert len(provider.binding_calls) == 1

    bindings_by_block: dict[str, list[Any]] = {}
    for proposal in submitted.bindingProposals:
        key = proposal.createdBlockClientOperationId
        assert key is not None
        bindings_by_block.setdefault(key, []).append(proposal)
    assert set(bindings_by_block) == set(expected_keys)
    for block_key, proposals in bindings_by_block.items():
        primary = [item for item in proposals if item.bindingRole == "PRIMARY"]
        supporting = sorted(
            item.bindingOrdinal
            for item in proposals
            if item.bindingRole == "SUPPORTING"
        )
        assert len(primary) == 1, block_key
        assert primary[0].bindingOrdinal == 1
        assert supporting == list(range(2, len(supporting) + 2))


def _context_bundle() -> dict[str, Any]:
    return {
        "runId": "binding-plan-repair-fixture",
        "workspace": {
            "workspaceId": WORKSPACE_ID,
            "repositoryId": str(REPOSITORY_ID),
            "repositoryName": "devcollab",
            "defaultBranch": "main",
            "revision": "fixture-revision",
        },
        "task": {
            "selectedPaths": list(SOURCE_PATHS),
            "userInstruction": "请根据真实代码生成面向初学者的中文正式说明。",
        },
        "codeFiles": [
            {
                "filePath": path,
                "language": "Python",
                "content": (REPOSITORY_ROOT / path).read_text(encoding="utf-8"),
                "truncated": False,
            }
            for path in SOURCE_PATHS
        ],
        "existingBindings": [],
        "documents": [],
    }


def _agent_plan(block_plans: list[dict[str, Any]]) -> AgentPlan:
    operations: list[dict[str, Any]] = [
        {
            "clientOperationId": "create_review_service_document",
            "sequenceNumber": 1,
            "operationType": "CREATE_DOCUMENT",
            "proposedDocumentTitle": "评审服务代码职责说明",
            "proposedDocumentType": "BACKEND",
        }
    ]
    evidence: list[dict[str, Any]] = [
        {
            "clientOperationId": "create_review_service_document",
            "repositoryId": str(REPOSITORY_ID),
            "filePath": SOURCE_PATHS[0],
            "description": "所选代码文件共同构成评审服务的入口、模型转换和规则处理。",
        }
    ]
    for sequence_number, block in enumerate(block_plans, start=2):
        operations.append(
            {
                "clientOperationId": block["blockKey"],
                "sequenceNumber": sequence_number,
                "operationType": "ADD_BLOCK",
                "createdDocumentClientOperationId": "create_review_service_document",
                "proposedBlockType": "PARAGRAPH",
                "proposedPlainText": (
                    f"## {block['title']}\n\n"
                    "这一部分依据当前选定代码说明实际职责、输入输出和协作关系。"
                    "正文只陈述能够由代码直接确认的行为，帮助初学者理解调用顺序，"
                    "并在修改对应职责时检查相关文件是否仍保持一致。"
                ),
                "proposedContentFormat": "MARKDOWN",
            }
        )
        evidence.append(
            {
                "clientOperationId": block["blockKey"],
                "repositoryId": str(REPOSITORY_ID),
                "filePath": SOURCE_PATHS[0],
                "description": f"程序规划的 {block['targetKind']} 代码职责证据。",
            }
        )
    return AgentPlan.model_validate(
        {
            "decision": "SUBMIT_REVIEW",
            "summary": "生成评审服务代码职责说明",
            "rationale": "按程序提供的固定文档块契约生成可评审内容",
            "operations": operations,
            "bindingProposals": [],
            "evidence": evidence,
        }
    )
