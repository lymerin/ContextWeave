from typing import Any
from uuid import UUID

import pytest
from pydantic import ValidationError

from app.graph.document_sync_workflow import DocumentSyncWorkflow
from app.planning.binding_candidates import (
    BindingCandidateBuilder,
    BindingPlanExpander,
    BindingPlanValidationError,
)
from app.runtime.binding_only import BindingOnlyWorkflow
from app.schemas.binding_plans import BindingPlan, BindingRole, BindingSelection
from app.schemas.plans import AgentPlan, Decision

REPOSITORY_ID = UUID("22222222-2222-2222-2222-222222222222")
DOCUMENT_ID = UUID("44444444-4444-4444-4444-444444444444")
BLOCK_A = UUID("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
BLOCK_B = UUID("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")


def context(
    *,
    code_files: list[dict[str, Any]] | None = None,
    blocks: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    return {
        "workspace": {
            "workspaceId": "11111111-1111-1111-1111-111111111111",
            "repositoryId": str(REPOSITORY_ID),
            "revision": "abc123",
        },
        "task": {"selectedPaths": [item["filePath"] for item in code_files or []]},
        "codeFiles": code_files or [],
        "existingBindings": [],
        "documents": [
            {
                "documentId": str(DOCUMENT_ID),
                "title": "上下文构建与预算模块",
                "blocks": blocks or [],
            }
        ],
    }


def no_change_plan() -> AgentPlan:
    return AgentPlan(
        decision=Decision.NO_CHANGE,
        summary="无需修改正文",
        rationale="现有正文与代码一致。",
    )


def test_binding_plan_rejects_raw_anchor_fields_and_invalid_confidence() -> None:
    with pytest.raises(ValidationError):
        BindingSelection.model_validate(
            {
                "codeCandidateId": "code_candidate",
                "documentAnchorCandidateId": "doc_candidate",
                "reason": "职责一致",
                "confidence": 0.9,
                "filePath": "app/budget.py",
            }
        )
    with pytest.raises(ValidationError):
        BindingSelection(
            blockKey="block_key",
            codeCandidateId="code_candidate",
            role=BindingRole.PRIMARY,
            ordinal=1,
            reason="越界",
            confidence=1.1,
        )


def test_python_ast_builds_top_level_class_function_async_and_methods() -> None:
    source = """\
async def load_context():
    return {}

class ContextBuilder:
    def build(self):
        return {}

    async def rebuild(self):
        return {}
"""
    candidates = (
        BindingCandidateBuilder()
        .build(
            context(
                code_files=[
                    {
                        "filePath": "agent-service/app/context/builder.py",
                        "language": "Python",
                        "content": source,
                    }
                ]
            ),
            no_change_plan(),
        )
        .code
    )
    by_name = {item.displayName: item for item in candidates}
    by_qualified_name = {item.qualifiedName: item for item in candidates}
    assert {
        "agent-service/app/context/builder.py",
        "load_context",
        "ContextBuilder",
    } <= set(by_name)
    assert {"ContextBuilder.build", "ContextBuilder.rebuild"} <= set(by_qualified_name)
    assert by_name["load_context"].startLine == 1
    assert by_qualified_name["ContextBuilder.build"].startLine == 5
    assert by_qualified_name["ContextBuilder.rebuild"].endLine == 9
    assert by_qualified_name["ContextBuilder.build"].symbolKey.startswith("PYTHON:")


def test_python_syntax_error_and_unsupported_language_fall_back_to_file() -> None:
    candidates = (
        BindingCandidateBuilder()
        .build(
            context(
                code_files=[
                    {
                        "filePath": "broken.py",
                        "language": "Python",
                        "content": "def broken(:",
                    },
                    {
                        "filePath": "component.ts",
                        "language": "TypeScript",
                        "content": "export const value = 1;",
                    },
                ]
            ),
            no_change_plan(),
        )
        .code
    )
    assert len(candidates) == 2
    assert all(item.anchorKind.value == "FILE" for item in candidates)
    assert all(item.symbolKey is None for item in candidates)


def test_java_candidates_reuse_real_projected_symbols() -> None:
    candidates = (
        BindingCandidateBuilder()
        .build(
            context(
                code_files=[
                    {
                        "filePath": "src/AuthController.java",
                        "language": "Java",
                        "content": "class AuthController { void login() {} }",
                        "symbols": [
                            {
                                "symbolKey": "src/AuthController.java#login()",
                                "qualifiedName": "AuthController#login",
                                "startLine": 1,
                                "endLine": 1,
                            }
                        ],
                    }
                ]
            ),
            no_change_plan(),
        )
        .code
    )
    symbol = next(item for item in candidates if item.anchorKind.value == "SYMBOL")
    assert symbol.symbolKey == "src/AuthController.java#login()"
    assert (symbol.startLine, symbol.endLine) == (1, 1)


def test_code_candidates_are_ordered_by_path_range_and_put_file_overview_last() -> None:
    candidates = (
        BindingCandidateBuilder()
        .build(
            context(
                code_files=[
                    {
                        "filePath": "zeta.py",
                        "language": "Python",
                        "content": "def later():\n    return 2\n",
                    },
                    {
                        "filePath": "alpha.py",
                        "language": "Python",
                        "content": ("def first():\n    return 1\n\ndef second():\n    return 2\n"),
                    },
                ]
            ),
            no_change_plan(),
        )
        .code
    )
    order = [(item.filePath, item.startLine, item.anchorKind.value) for item in candidates]
    assert order == [
        ("alpha.py", 1, "SYMBOL"),
        ("alpha.py", 4, "SYMBOL"),
        ("alpha.py", None, "FILE"),
        ("zeta.py", 1, "SYMBOL"),
        ("zeta.py", None, "FILE"),
    ]


def test_candidate_limits_previews_and_real_document_block_ids() -> None:
    candidates = BindingCandidateBuilder(
        max_code_candidates=2,
        max_document_candidates=2,
        max_preview_characters=20,
    ).build(
        context(
            code_files=[
                {
                    "filePath": "budget.py",
                    "language": "Python",
                    "content": "def budget():\n    " + ("x" * 1000),
                }
            ],
            blocks=[
                {
                    "blockId": str(BLOCK_A),
                    "type": "PARAGRAPH",
                    "sortOrder": 0,
                    "version": 1,
                    "plainText": "预算职责" * 100,
                }
            ],
        ),
        no_change_plan(),
    )
    assert len(candidates.code) == 2
    assert len(candidates.documents) == 2
    assert max(len(item.contentPreview) for item in candidates.code) <= 20
    block = next(item for item in candidates.documents if item.blockId)
    assert block.blockId == BLOCK_A


def test_add_block_candidate_uses_real_client_operation_reference() -> None:
    plan = AgentPlan.model_validate(
        {
            "decision": "SUBMIT_REVIEW",
            "summary": "新增文档",
            "rationale": "需要形成正式说明。",
            "operations": [
                {
                    "clientOperationId": "create-doc",
                    "sequenceNumber": 1,
                    "operationType": "CREATE_DOCUMENT",
                    "proposedDocumentTitle": "模块说明",
                },
                {
                    "clientOperationId": "add-block",
                    "sequenceNumber": 2,
                    "operationType": "ADD_BLOCK",
                    "createdDocumentClientOperationId": "create-doc",
                    "proposedBlockType": "PARAGRAPH",
                    "proposedPlainText": "正式正文",
                    "proposedContentFormat": "MARKDOWN",
                },
            ],
            "evidence": [],
        }
    )
    candidates = (
        BindingCandidateBuilder()
        .build(
            context(
                code_files=[{"filePath": "service.py", "language": "Python", "content": "x = 1"}]
            ),
            plan,
        )
        .documents
    )
    created = next(item for item in candidates if item.createdBlockClientOperationId)
    assert created.createdDocumentClientOperationId == "create-doc"
    assert created.createdBlockClientOperationId == "add-block"


def test_expansion_uses_exact_server_candidates_and_rejects_unknown_ids() -> None:
    candidates = BindingCandidateBuilder().build(
        context(
            code_files=[
                {
                    "filePath": "budget.py",
                    "language": "Python",
                    "content": "def budget():\n    return 1",
                }
            ],
            blocks=[
                {
                    "blockId": str(BLOCK_A),
                    "type": "PARAGRAPH",
                    "sortOrder": 0,
                    "version": 1,
                    "plainText": "预算职责",
                }
            ],
        ),
        no_change_plan(),
    )
    code = next(item for item in candidates.code if item.symbolKey)
    document = next(item for item in candidates.documents if item.blockId)
    expanded = BindingPlanExpander().expand(
        no_change_plan(),
        BindingPlan(
            selections=[
                BindingSelection(
                    blockKey=document.candidateId,
                    codeCandidateId=code.candidateId,
                    role=BindingRole.PRIMARY,
                    ordinal=1,
                    reason="预算函数实现该 Block 描述的预算职责。",
                    confidence=0.94,
                )
            ]
        ),
        candidates,
    )
    proposal = expanded.bindingProposals[0]
    assert proposal.filePath == code.filePath
    assert proposal.symbolKey == code.symbolKey
    assert proposal.blockId == BLOCK_A
    assert proposal.reason.startswith("预算函数")
    assert proposal.confidence == 0.94
    with pytest.raises(BindingPlanValidationError):
        BindingPlanExpander().expand(
            no_change_plan(),
            BindingPlan(
                selections=[
                    BindingSelection(
                        blockKey=document.candidateId,
                        codeCandidateId="unknown_code",
                        role=BindingRole.PRIMARY,
                        ordinal=1,
                        reason="未知",
                        confidence=0.1,
                    )
                ]
            ),
            candidates,
        )


@pytest.mark.asyncio
async def test_binding_pass_repairs_unknown_candidate_at_most_once(settings: Any) -> None:
    class Provider:
        def __init__(self) -> None:
            self.calls = 0

        async def plan_block_bindings(
            self,
            candidates: dict[str, Any],
            **_kwargs: Any,
        ) -> BindingPlan:
            self.calls += 1
            if self.calls == 1:
                return BindingPlan(
                    selections=[
                        BindingSelection(
                            blockKey=candidates["documentBlockPlans"][0]["blockKey"],
                            codeCandidateId="unknown_code",
                            role=BindingRole.PRIMARY,
                            ordinal=1,
                            reason="第一次引用未知候选。",
                            confidence=0.5,
                        )
                    ]
                )
            return BindingPlan(selections=[])

        async def plan_document_sync(self, *_args: Any, **_kwargs: Any) -> Any:
            raise AssertionError("document planner must not run")

        async def plan_project_units(self, *_args: Any, **_kwargs: Any) -> Any:
            raise AssertionError("project planner must not run")

    provider = Provider()

    async def status(*_args: Any) -> None:
        return None

    workflow = DocumentSyncWorkflow(object(), provider, settings, status)  # type: ignore[arg-type]
    model_context = context(
        code_files=[{"filePath": "budget.py", "language": "Python", "content": "x = 1"}],
        blocks=[
            {
                "blockId": str(BLOCK_B),
                "type": "PARAGRAPH",
                "sortOrder": 0,
                "version": 1,
                "plainText": "预算说明",
            }
        ],
    )
    state: dict[str, Any] = {
        "run_id": "binding-test",
        "plan": no_change_plan(),
        "model_context": model_context,
        "trace_events": [],
    }
    result = await workflow.plan_bindings(state)  # type: ignore[arg-type]
    assert provider.calls == 2
    assert result["plan_outcome"] == "SUBMIT_REVIEW"
    assert result["plan"].bindingProposals[0].bindingRole == "PRIMARY"


@pytest.mark.asyncio
async def test_focused_binding_only_entry_never_runs_document_or_project_planner(
    settings: Any,
) -> None:
    submitted: list[AgentPlan] = []

    class Client:
        async def call_tool(
            self,
            name: str,
            arguments: dict[str, Any],
            _authorization: str,
        ) -> dict[str, Any]:
            if name == "devcollab.workspace.get_context":
                return {
                    "repositories": [
                        {
                            "repositoryId": str(REPOSITORY_ID),
                            "lastSyncedCommit": "abc123",
                        }
                    ]
                }
            if name == "devcollab.code.read":
                return {
                    "path": arguments["path"],
                    "language": "Python",
                    "content": "def budget():\n    return 1\n",
                    "commitHash": "abc123",
                    "truncated": False,
                }
            if name == "devcollab.binding.list_batch":
                return {"files": []}
            if name == "devcollab.document.get_structure":
                return {
                    "documentId": str(DOCUMENT_ID),
                    "title": "上下文构建与预算模块",
                    "blocks": [
                        {
                            "blockId": str(BLOCK_A),
                            "type": "PARAGRAPH",
                            "sortOrder": 0,
                            "version": 1,
                            "plainText": "预算模块负责限制上下文大小。",
                        }
                    ],
                }
            raise AssertionError(f"unexpected tool: {name}")

        async def read_code_details(self, **_kwargs: Any) -> dict[str, Any]:
            return {"symbols": []}

        async def submit_document_change(
            self,
            plan: AgentPlan,
            **_kwargs: Any,
        ) -> dict[str, Any]:
            submitted.append(plan)
            return {"status": "PENDING", "changeRequestId": "review-1"}

    class Provider:
        async def plan_block_bindings(
            self,
            candidates: dict[str, Any],
            **_kwargs: Any,
        ) -> BindingPlan:
            code = next(
                item for item in candidates["codeCandidates"] if item["anchorKind"] == "SYMBOL"
            )
            block_plan = candidates["documentBlockPlans"][0]
            return BindingPlan(
                selections=[
                    BindingSelection(
                        blockKey=block_plan["blockKey"],
                        codeCandidateId=code["candidateId"],
                        role=BindingRole.PRIMARY,
                        ordinal=1,
                        reason="预算函数实现该文档块描述的职责。",
                        confidence=0.95,
                    )
                ]
            )

        async def plan_document_sync(self, *_args: Any, **_kwargs: Any) -> Any:
            raise AssertionError("document planner must not run")

        async def plan_project_units(self, *_args: Any, **_kwargs: Any) -> Any:
            raise AssertionError("project planner must not run")

    async def status(*_args: Any) -> None:
        return None

    result = await BindingOnlyWorkflow(
        Client(),  # type: ignore[arg-type]
        Provider(),  # type: ignore[arg-type]
        settings,
        status,
    ).run(
        run_id="focused-binding",
        workspace_id="11111111-1111-1111-1111-111111111111",
        repository_id=str(REPOSITORY_ID),
        revision="abc123",
        file_paths=["agent-service/app/context/budget.py"],
        document_id=str(DOCUMENT_ID),
    )

    assert result["changeRequestId"] == "review-1"
    assert len(submitted) == 1
    proposal = submitted[0].bindingProposals[0]
    assert proposal.filePath == "agent-service/app/context/budget.py"
    assert proposal.blockId == BLOCK_A
