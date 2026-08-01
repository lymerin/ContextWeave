import asyncio
import time
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest
from conftest import (
    FakeMcpClient,
    FakeModelProvider,
    MemoryRunStore,
    request_payload,
)
from fastapi.testclient import TestClient

from app.clients.mcp_client import (
    McpClientError,
    OfficialMcpClient,
    _structured_tool_content,
)
from app.config import Settings
from app.graph.document_sync_workflow import DocumentSyncWorkflow
from app.main import create_app
from app.providers.base import ModelProviderError
from app.runtime.executor import AgentRunExecutor
from app.schemas.document_block_content import (
    DocumentBlockContent,
    DocumentBlockContentPlan,
)
from app.schemas.plans import AgentPlan

REPOSITORY = "22222222-2222-2222-2222-222222222222"
REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
REVIEW_SOURCE_PATHS = (
    "agent-review-service/app/main.py",
    "agent-review-service/app/schemas.py",
    "agent-review-service/app/domain.py",
    "agent-review-service/app/rules.py",
)


class ReviewServiceMcp(FakeMcpClient):
    async def call_tool(
        self, name: str, arguments: dict[str, Any], authorization: str
    ) -> dict[str, Any]:
        if name == "devcollab.code.read" and arguments["path"] in REVIEW_SOURCE_PATHS:
            path = arguments["path"]
            content = (REPOSITORY_ROOT / path).read_text(encoding="utf-8")
            self.calls.append((name, arguments, authorization))
            return {
                "workspaceId": arguments["workspaceId"],
                "repositoryId": arguments["repositoryId"],
                "path": path,
                "commitHash": "abc",
                "language": "Python",
                "sizeBytes": len(content.encode("utf-8")),
                "startLine": 1,
                "endLine": len(content.splitlines()),
                "totalLines": len(content.splitlines()),
                "content": content,
                "truncated": False,
                "omittedLineCount": 0,
                "omittedCharacterCount": 0,
                "existingBindings": [],
                "existingBindingsAvailable": True,
                "existingBindingsRequested": False,
            }
        return await super().call_tool(name, arguments, authorization)


def no_change() -> AgentPlan:
    return AgentPlan.model_validate(
        {
            "decision": "NO_CHANGE",
            "summary": "No synchronization is needed",
            "rationale": "The implementation and documentation agree.",
            "operations": [],
            "bindingProposals": [],
            "evidence": [],
        }
    )


def create_document() -> AgentPlan:
    return AgentPlan.model_validate(
        {
            "decision": "SUBMIT_REVIEW",
            "summary": "Create implementation documentation",
            "rationale": "The selected implementation lacks a dedicated document.",
            "operations": [
                {
                    "clientOperationId": "create-1",
                    "sequenceNumber": 1,
                    "operationType": "CREATE_DOCUMENT",
                    "proposedDocumentTitle": "示例实现说明",
                    "proposedDocumentType": "BACKEND",
                },
                {
                    "clientOperationId": "block-1",
                    "sequenceNumber": 2,
                    "operationType": "ADD_BLOCK",
                    "createdDocumentClientOperationId": "create-1",
                    "proposedBlockType": "PARAGRAPH",
                    "proposedPlainText": "示例能力由当前选中的类实现，并作为正式模块行为对外提供。",
                    "proposedContentFormat": "MARKDOWN",
                },
            ],
            "bindingProposals": [
                {
                    "clientBindingProposalId": "bind-1",
                    "sequenceNumber": 3,
                    "action": "UPSERT_BINDING",
                    "repositoryId": REPOSITORY,
                    "filePath": "src/Example.java",
                    "createdDocumentClientOperationId": "create-1",
                    "reason": "Link the new document to its implementation.",
                }
            ],
            "evidence": [
                {
                    "clientOperationId": "create-1",
                    "repositoryId": REPOSITORY,
                    "filePath": "src/Example.java",
                    "startLine": 1,
                    "endLine": 1,
                    "description": "Selected implementation",
                },
                {
                    "clientOperationId": "block-1",
                    "repositoryId": REPOSITORY,
                    "filePath": "src/Example.java",
                    "startLine": 1,
                    "endLine": 1,
                    "description": "Block content evidence",
                },
            ],
        }
    )


def invalid_update() -> AgentPlan:
    return AgentPlan.model_validate(
        {
            "decision": "SUBMIT_REVIEW",
            "summary": "Update a block",
            "rationale": "An update might be needed.",
            "operations": [
                {
                    "clientOperationId": "update-1",
                    "sequenceNumber": 1,
                    "operationType": "UPDATE_BLOCK",
                    "documentId": "44444444-4444-4444-4444-444444444444",
                    "blockId": "77777777-7777-7777-7777-777777777777",
                    "baseBlockVersion": 99,
                    "proposedPlainText": "fabricated",
                    "proposedContentFormat": "MARKDOWN",
                }
            ],
            "bindingProposals": [],
            "evidence": [
                {
                    "clientOperationId": "update-1",
                    "repositoryId": REPOSITORY,
                    "filePath": "src/Example.java",
                    "startLine": 1,
                    "endLine": 1,
                    "description": "Selected implementation",
                }
            ],
        }
    )


def initial_state() -> dict[str, Any]:
    return {
        "run_id": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
        "workspace_id": "11111111-1111-1111-1111-111111111111",
        "repository_id": REPOSITORY,
        "selected_paths": ["src/Example.java"],
        "user_instruction": None,
        "authorization": "Bearer transient",
        "tool_call_count": 0,
        "code_chars_used": 0,
        "trace_events": [],
        "errors": [],
    }


def review_state() -> dict[str, Any]:
    return {**initial_state(), "selected_paths": list(REVIEW_SOURCE_PATHS)}


def review_settings(settings: Settings) -> Settings:
    return settings.model_copy(
        update={"agent_max_selected_files": 6, "agent_max_code_chars": 200_000}
    )


async def no_status(
    status: str,
    node: str,
    updates: dict[str, Any],
) -> None:
    return None


@pytest.mark.asyncio
async def test_no_change_does_not_submit(settings: Settings) -> None:
    mcp = FakeMcpClient()
    result = await DocumentSyncWorkflow(
        mcp, FakeModelProvider([no_change()]), settings, no_status
    ).graph.ainvoke(initial_state())
    assert result["decision"] == "NO_CHANGE"
    assert not mcp.submissions


@pytest.mark.asyncio
async def test_submit_review_calls_dedicated_mcp_once(settings: Settings) -> None:
    mcp = ReviewServiceMcp()
    result = await DocumentSyncWorkflow(
        mcp, FakeModelProvider([create_document()]), review_settings(settings), no_status
    ).graph.ainvoke(review_state())
    assert result["change_request_id"] == "99999999-9999-9999-9999-999999999999"
    assert len(mcp.submissions) == 1
    assert mcp.submissions[0][3] == "Bearer transient"


@pytest.mark.asyncio
async def test_first_invalid_plan_is_repaired_once(settings: Settings) -> None:
    provider = FakeModelProvider([invalid_update(), no_change()])
    mcp = FakeMcpClient()
    result = await DocumentSyncWorkflow(mcp, provider, settings, no_status).graph.ainvoke(
        initial_state()
    )
    assert result["decision"] == "NO_CHANGE"
    assert provider.calls == []
    assert provider.block_content_calls == []
    assert not mcp.submissions


@pytest.mark.asyncio
async def test_second_invalid_plan_fails_without_submission(settings: Settings) -> None:
    provider = FakeModelProvider([invalid_update(), invalid_update()])
    mcp = FakeMcpClient()
    result = await DocumentSyncWorkflow(mcp, provider, settings, no_status).graph.ainvoke(
        initial_state()
    )
    assert result["decision"] == "NO_CHANGE"
    assert provider.calls == []
    assert not mcp.submissions


@pytest.mark.asyncio
async def test_invalid_model_response_is_repaired_once(settings: Settings) -> None:
    provider = FakeModelProvider(
        [
            ModelProviderError(
                "MODEL_INVALID_RESPONSE",
                "invalid",
                raw_plan={"decision": "wrong"},
            ),
            no_change(),
        ]
    )
    with pytest.raises(ModelProviderError):
        await DocumentSyncWorkflow(
            ReviewServiceMcp(), provider, review_settings(settings), no_status
        ).graph.ainvoke(review_state())
    assert len(provider.block_content_calls) == 1


@pytest.mark.asyncio
async def test_non_validation_model_error_is_not_retried(settings: Settings) -> None:
    provider = FakeModelProvider([ModelProviderError("MODEL_RATE_LIMITED", "rate limited")])
    with pytest.raises(ModelProviderError):
        workflow = DocumentSyncWorkflow(
            ReviewServiceMcp(), provider, review_settings(settings), no_status
        )
        await workflow.graph.ainvoke(review_state())
    assert len(provider.block_content_calls) == 1


@pytest.mark.asyncio
async def test_fixed_graph_reports_expected_planning_statuses(settings: Settings) -> None:
    statuses: list[tuple[str, str]] = []

    async def record(status: str, node: str, updates: dict[str, Any]) -> None:
        statuses.append((status, node))

    await DocumentSyncWorkflow(
        ReviewServiceMcp(),
        FakeModelProvider([create_document()]),
        review_settings(settings),
        record,
    ).graph.ainvoke(review_state())
    assert statuses == [
        ("PLANNING", "plan_changes"),
        ("VALIDATING", "validate_plan"),
        ("PLANNING_BINDINGS", "plan_bindings"),
        ("PLANNING_BINDINGS", "binding_candidates"),
        ("SUBMITTING_REVIEW", "submit_review"),
        ("REVIEW_SUBMITTED", "submit_review"),
    ]


class CapturingOfficialClient(OfficialMcpClient):
    def __init__(self) -> None:
        super().__init__("http://unused", 1)
        self.invocations: list[tuple[str, dict[str, Any], str]] = []

    async def _invoke_tool(
        self,
        name: str,
        arguments: dict[str, Any],
        authorization: str,
    ) -> dict[str, Any]:
        self.invocations.append((name, arguments, authorization))
        return {
            "changeRequestId": "99999999-9999-9999-9999-999999999999",
            "status": "PENDING",
            "createdAt": "2026-07-27T00:00:00Z",
            "idempotentReplay": len(self.invocations) > 1,
        }


def test_mcp_result_prefers_structured_content() -> None:
    result = SimpleNamespace(
        structuredContent={"changeRequestId": "review-1", "status": "PENDING"},
        content=[SimpleNamespace(type="text", text='{"ignored":true}')],
        isError=False,
    )

    assert _structured_tool_content(result) == {
        "changeRequestId": "review-1",
        "status": "PENDING",
    }


def test_mcp_result_accepts_strict_json_text_fallback() -> None:
    result = SimpleNamespace(
        structuredContent=None,
        content=[
            SimpleNamespace(
                type="text",
                text='{"changeRequestId":"review-1","status":"PENDING"}',
            )
        ],
        isError=False,
    )

    assert _structured_tool_content(result)["changeRequestId"] == "review-1"


def test_mcp_non_json_tool_error_is_not_reported_as_unavailable() -> None:
    result = SimpleNamespace(
        structuredContent=None,
        content=[SimpleNamespace(type="text", text="Input schema validation failed")],
        isError=True,
    )

    with pytest.raises(McpClientError) as captured:
        _structured_tool_content(result)

    assert captured.value.code == "MCP_TOOL_ERROR"
    assert "schema validation" in str(captured.value)


class MissingReviewIdClient(OfficialMcpClient):
    async def _invoke_tool(
        self,
        name: str,
        arguments: dict[str, Any],
        authorization: str,
    ) -> dict[str, Any]:
        return {"status": "PENDING"}


@pytest.mark.asyncio
async def test_submit_rejects_success_result_without_change_request_id() -> None:
    client = MissingReviewIdClient("http://unused", 1)

    with pytest.raises(McpClientError) as captured:
        await client.submit_document_change(
            create_document(),
            workspace_id="11111111-1111-1111-1111-111111111111",
            run_id="aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            authorization="Bearer transient",
        )

    assert captured.value.code == "MCP_PROTOCOL_ERROR"


class InsufficientFirstBlockProvider(FakeModelProvider):
    async def generate_document_blocks(
        self,
        context: dict[str, Any],
    ) -> DocumentBlockContentPlan:
        generated = await super().generate_document_blocks(context)
        first, *remaining = generated.blocks
        return DocumentBlockContentPlan(
            blocks=[
                DocumentBlockContent(
                    blockKey=first.blockKey,
                    status="INSUFFICIENT_EVIDENCE",
                ),
                *remaining,
            ]
        )


@pytest.mark.asyncio
async def test_insufficient_required_block_is_rewritten_once() -> None:
    provider = InsufficientFirstBlockProvider([create_document()])
    result = await DocumentSyncWorkflow(
        ReviewServiceMcp(),
        provider,
        review_settings(Settings()),
        no_status,
    ).graph.ainvoke(review_state())

    assert result["decision"] == "SUBMIT_REVIEW"
    assert len(provider.block_repair_calls) == 1
    assert provider.block_repair_calls[0]["validationErrors"][0]["code"] == (
        "BLOCK_INSUFFICIENT_EVIDENCE"
    )


@pytest.mark.asyncio
async def test_dedicated_client_fixes_tool_name_and_client_request_id() -> None:
    client = CapturingOfficialClient()
    run_id = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    await client.submit_document_change(
        create_document(),
        workspace_id="11111111-1111-1111-1111-111111111111",
        run_id=run_id,
        authorization="Bearer transient",
    )
    name, arguments, authorization = client.invocations[0]
    assert name == "devcollab.review.submit_document_change"
    assert arguments["clientRequestId"] == f"agent-{run_id}"
    assert "decision" not in arguments
    assert authorization == "Bearer transient"


@pytest.mark.asyncio
async def test_dedicated_client_retry_reuses_client_request_id() -> None:
    client = CapturingOfficialClient()
    kwargs = {
        "workspace_id": "11111111-1111-1111-1111-111111111111",
        "run_id": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
        "authorization": "Bearer transient",
    }
    await client.submit_document_change(create_document(), **kwargs)
    result = await client.submit_document_change(create_document(), **kwargs)
    assert (
        client.invocations[0][1]["clientRequestId"] == client.invocations[1][1]["clientRequestId"]
    )
    assert result["idempotentReplay"] is True


def wait_for_terminal(
    client: TestClient,
    run_id: str,
    timeout: float = 2,
) -> dict[str, Any]:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        response = client.get(
            f"/api/v1/agent-runs/{run_id}",
            headers={"Authorization": "Bearer transient"},
        )
        body = response.json()
        if body["status"] in {"NO_CHANGE", "REVIEW_SUBMITTED", "FAILED"}:
            return body
        time.sleep(0.01)
    raise AssertionError("Agent run did not reach a terminal state")


def app_client(
    settings: Settings,
    provider: FakeModelProvider,
    mcp: FakeMcpClient | None = None,
    store: MemoryRunStore | None = None,
) -> tuple[TestClient, FakeMcpClient, MemoryRunStore]:
    actual_mcp = mcp or FakeMcpClient()
    actual_store = store or MemoryRunStore()
    return (
        TestClient(
            create_app(
                settings=settings,
                mcp_client=actual_mcp,
                run_store=actual_store,
                model_provider=provider,
            )
        ),
        actual_mcp,
        actual_store,
    )


def test_formal_post_returns_202_and_no_change_terminal(settings: Settings) -> None:
    client, mcp, _ = app_client(settings, FakeModelProvider([no_change()]))
    with client:
        response = client.post(
            "/api/v1/agent-runs",
            json=request_payload(),
            headers={"Authorization": "Bearer transient"},
        )
        assert response.status_code == 202
        assert response.json()["status"] == "QUEUED"
        result = wait_for_terminal(client, response.json()["runId"])
    assert result["status"] == "NO_CHANGE"
    assert result["changeRequestId"] is None
    assert not mcp.submissions


def test_formal_run_submits_pending_review(settings: Settings) -> None:
    client, mcp, _ = app_client(
        review_settings(settings),
        FakeModelProvider([create_document()]),
        ReviewServiceMcp(),
    )
    with client:
        response = client.post(
            "/api/v1/agent-runs",
            json=request_payload(list(REVIEW_SOURCE_PATHS)),
            headers={"Authorization": "Bearer transient"},
        )
        result = wait_for_terminal(client, response.json()["runId"])
    assert result["status"] == "REVIEW_SUBMITTED"
    assert result["changeRequestId"] == "99999999-9999-9999-9999-999999999999"
    assert len(mcp.submissions) == 1


@pytest.mark.parametrize(
    ("error", "expected"),
    [
        (ModelProviderError("MODEL_TIMEOUT", "timeout"), "MODEL_TIMEOUT"),
        (ModelProviderError("MODEL_RATE_LIMITED", "limited"), "MODEL_RATE_LIMITED"),
        (ModelProviderError("MODEL_UNAVAILABLE", "down"), "MODEL_UNAVAILABLE"),
        (
            ModelProviderError("MODEL_CONFIGURATION_ERROR", "missing"),
            "MODEL_CONFIGURATION_ERROR",
        ),
    ],
)
def test_model_failures_are_visible_in_run(
    settings: Settings,
    error: Exception,
    expected: str,
) -> None:
    client, _, _ = app_client(
        review_settings(settings),
        FakeModelProvider([error]),
        ReviewServiceMcp(),
    )
    with client:
        response = client.post(
            "/api/v1/agent-runs",
            json=request_payload(list(REVIEW_SOURCE_PATHS)),
            headers={"Authorization": "Bearer transient"},
        )
        result = wait_for_terminal(client, response.json()["runId"])
    assert result["status"] == "FAILED"
    assert result["errorCode"] == expected


def test_validation_failure_is_visible_and_never_submits(settings: Settings) -> None:
    invalid_response = ModelProviderError(
        "MODEL_INVALID_RESPONSE",
        "invalid block content",
        raw_plan={"blocks": [{"operation": {}}]},
    )
    client, mcp, _ = app_client(
        review_settings(settings),
        FakeModelProvider([invalid_response]),
        ReviewServiceMcp(),
    )
    with client:
        response = client.post(
            "/api/v1/agent-runs",
            json=request_payload(list(REVIEW_SOURCE_PATHS)),
            headers={"Authorization": "Bearer transient"},
        )
        result = wait_for_terminal(client, response.json()["runId"])
    assert result["errorCode"] == "MODEL_INVALID_RESPONSE"
    assert "Bearer transient" not in result["errorMessage"]
    assert not mcp.submissions


def test_submission_permission_failure_is_visible(settings: Settings) -> None:
    class DeniedMcp(ReviewServiceMcp):
        async def submit_document_change(
            self,
            plan: AgentPlan,
            *,
            workspace_id: str,
            run_id: str,
            authorization: str,
        ) -> dict[str, Any]:
            raise McpClientError("MCP_PERMISSION_DENIED", "denied")

    client, _, _ = app_client(
        review_settings(settings),
        FakeModelProvider([create_document()]),
        DeniedMcp(),
    )
    with client:
        response = client.post(
            "/api/v1/agent-runs",
            json=request_payload(list(REVIEW_SOURCE_PATHS)),
            headers={"Authorization": "Bearer transient"},
        )
        result = wait_for_terminal(client, response.json()["runId"])
    assert result["status"] == "FAILED"
    assert result["errorCode"] == "MCP_PERMISSION_DENIED"


def test_redis_record_contains_summary_only(settings: Settings) -> None:
    store = MemoryRunStore()
    client, _, actual_store = app_client(
        settings,
        FakeModelProvider([create_document()]),
        store=store,
    )
    with client:
        response = client.post(
            "/api/v1/agent-runs",
            json=request_payload(),
            headers={"Authorization": "Bearer transient"},
        )
        wait_for_terminal(client, response.json()["runId"])
    serialized = str(actual_store.values)
    assert "Bearer transient" not in serialized
    assert "class Example" not in serialized
    assert "proposedPlainText" not in serialized
    assert "document_sync_v1" not in serialized
    assert set(actual_store.ttls.values()) == {86400}


@pytest.mark.asyncio
async def test_executor_does_not_start_same_run_twice(settings: Settings) -> None:
    class SlowProvider(FakeModelProvider):
        async def generate_document_blocks(self, context: dict[str, Any]) -> Any:
            await asyncio.sleep(0.05)
            return await super().generate_document_blocks(context)

    provider = SlowProvider()
    store = MemoryRunStore()
    executor = AgentRunExecutor(
        ReviewServiceMcp(), provider, store, review_settings(settings)
    )
    kwargs = {
        "run_id": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
        "workspace_id": "11111111-1111-1111-1111-111111111111",
        "repository_id": REPOSITORY,
        "selected_paths": list(REVIEW_SOURCE_PATHS),
        "user_instruction": None,
        "authorization": "Bearer transient",
        "created_at": "2026-07-27T00:00:00+00:00",
    }
    executor.start(**kwargs)
    executor.start(**kwargs)
    for _ in range(100):
        if provider.block_content_calls:
            break
        await asyncio.sleep(0.01)
    assert len(provider.block_content_calls) == 1
    await executor.close()
