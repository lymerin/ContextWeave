from __future__ import annotations

import logging
from pathlib import Path
from typing import Any
from uuid import UUID

import pytest
from conftest import FakeMcpClient
from pydantic import ValidationError

from app.config import Settings
from app.graph.document_sync_workflow import DocumentSyncWorkflow
from app.planning.binding_candidates import (
    BindingCandidateBuilder,
    BindingPlanValidationError,
)
from app.planning.context_serializer import build_model_context
from app.planning.document_block_plans import DocumentBlockPlanBuilder
from app.planning.program_document_plan import ProgramDocumentPlanAssembler
from app.schemas.document_block_content import (
    DocumentBlockContent,
    DocumentBlockContentPlan,
    SupportingCandidateSelection,
)

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
        self.repair_calls: list[dict[str, Any]] = []

    async def generate_document_blocks(
        self,
        context: dict[str, Any],
    ) -> DocumentBlockContentPlan:
        self.document_calls.append(context)
        return DocumentBlockContentPlan(
            blocks=[
                DocumentBlockContent(
                    blockKey=block["blockKey"],
                    status="CONTENT",
                    content=(
                        "这里说明当前代码可以直接确认的职责和处理步骤。"
                        if index != 2
                        else "这里虚构了数据库行为，需要按当前代码证据重写。"
                    ),
                )
                for index, block in enumerate(context["blocks"])
            ]
        )

    async def repair_document_block(
        self,
        context: dict[str, Any],
        *,
        previous_block: dict[str, Any],
        validation_errors: list[dict[str, str]],
    ) -> DocumentBlockContent:
        self.repair_calls.append(
            {
                "context": context,
                "previousBlock": previous_block,
                "validationErrors": validation_errors,
            }
        )
        return DocumentBlockContent(
            blockKey=previous_block["blockKey"],
            status="CONTENT",
            content="这里只说明当前代码证据可以直接确认的职责和处理步骤。",
        )

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

    assert len(provider.document_calls) == 1
    assert len(provider.repair_calls) == 1
    initial_errors = provider.repair_calls[0]["validationErrors"]
    assert [item["code"] for item in initial_errors] == [
        "UNSUPPORTED_EXTERNAL_RELATION"
    ]
    diagnostic_log = "\n".join(caplog.messages)
    assert "runId=binding-plan-repair-fixture" in diagnostic_log
    assert "UNSUPPORTED_EXTERNAL_RELATION" in diagnostic_log
    repaired_context = provider.document_calls[0]
    expected_keys = [
        item["blockKey"] for item in repaired_context["blocks"]
    ]
    assert provider.repair_calls[0]["context"]["blocks"][0]["blockKey"] == (
        expected_keys[2]
    )

    assert result["change_request_id"] == "99999999-9999-9999-9999-999999999999"
    assert len(mcp.submissions) == 1
    submitted = mcp.submissions[0][0]
    content_operations = [
        operation
        for operation in submitted.operations
        if operation.operationType.value in {"ADD_BLOCK", "UPDATE_BLOCK"}
    ]
    assert [item.clientOperationId for item in content_operations] == expected_keys
    assert len(content_operations) == 5

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

    primary_symbols = {
        operation.clientOperationId: next(
            proposal.symbolKey
            for proposal in submitted.bindingProposals
            if proposal.createdBlockClientOperationId == operation.clientOperationId
            and proposal.bindingRole == "PRIMARY"
        )
        for operation in content_operations
    }
    assert primary_symbols[expected_keys[0]].endswith(":review:HTTP_ROUTE")
    assert ":review_document:" in primary_symbols[expected_keys[3]]
    repaired_key = expected_keys[2]
    assert all(
        "只保留能够" not in (operation.proposedPlainText or "")
        for operation in content_operations
        if operation.clientOperationId != repaired_key
    )


def test_model_content_schema_rejects_program_owned_fields() -> None:
    with pytest.raises(ValidationError):
        DocumentBlockContentPlan.model_validate(
            {
                "blocks": [
                    {
                        "blockKey": "block-contract",
                        "status": "CONTENT",
                        "content": "只包含正文。",
                        "title": "模型不得修改标题",
                        "sortOrder": 99,
                        "operation": {"operationType": "ADD_BLOCK"},
                    }
                ]
            }
        )


def test_program_assembler_rejects_changed_blocks_and_unknown_supporting() -> None:
    model_context, candidates, block_plans = _planning_fixture()
    assembler = ProgramDocumentPlanAssembler()
    valid = DocumentBlockContentPlan(
        blocks=[
            DocumentBlockContent(
                blockKey=item.blockKey,
                status="CONTENT",
                content="仅说明代码证据可以确认的职责。",
            )
            for item in block_plans
        ]
    )
    missing = DocumentBlockContentPlan(blocks=valid.blocks[:-1])
    with pytest.raises(BindingPlanValidationError) as mismatch:
        assembler.assemble(model_context, candidates, block_plans, missing)
    assert mismatch.value.issues[0]["code"] == "DOCUMENT_BLOCK_CONTENT_PLAN_MISMATCH"

    first = valid.blocks[0].model_copy(
        update={
            "supportingSelections": [
                SupportingCandidateSelection(
                    candidateId="candidate_unknown",
                    reason="不在程序允许集合内。",
                    confidence=0.5,
                )
            ]
        }
    )
    unknown = DocumentBlockContentPlan(blocks=[first, *valid.blocks[1:]])
    with pytest.raises(BindingPlanValidationError) as unsupported:
        assembler.assemble(model_context, candidates, block_plans, unknown)
    assert unsupported.value.issues[0]["code"] == "UNKNOWN_CANDIDATE_ID"


def test_program_assembler_owns_stable_operations_and_primary_bindings() -> None:
    model_context, candidates, block_plans = _planning_fixture()
    assembled = ProgramDocumentPlanAssembler().assemble(
        model_context,
        candidates,
        block_plans,
        DocumentBlockContentPlan(
            blocks=[
                DocumentBlockContent(
                    blockKey=item.blockKey,
                    status="CONTENT",
                    content="仅说明代码证据可以确认的职责。",
                )
                for item in block_plans
            ]
        ),
    )
    assert len(assembled.agent_plan.operations) == 6
    assert [item.clientOperationId for item in assembled.agent_plan.operations[1:]] == [
        item.blockKey for item in block_plans
    ]
    primary_by_block = {
        item.blockKey: item
        for item in assembled.binding_plan.selections
        if item.role.value == "PRIMARY"
    }
    assert set(primary_by_block) == {item.blockKey for item in block_plans}
    assert all(item.ordinal == 1 for item in primary_by_block.values())
    candidate_by_id = {item.candidateId: item for item in candidates}
    assert candidate_by_id[
        primary_by_block[block_plans[0].blockKey].codeCandidateId
    ].symbolKey.endswith(":review:HTTP_ROUTE")
    assert candidate_by_id[
        primary_by_block[block_plans[3].blockKey].codeCandidateId
    ].symbolKey.find(":review_document:") >= 0


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


def _planning_fixture() -> tuple[Any, Any, Any]:
    model_context = build_model_context(_context_bundle())
    candidates = BindingCandidateBuilder().build_code(model_context)
    block_plans = DocumentBlockPlanBuilder().build(candidates)
    assert len(block_plans) == 5
    return model_context, candidates, block_plans
