from uuid import uuid4

import pytest

from app.runtime.delegated_mcp_client import DelegatedMcpClient
from app.runtime.delegation_token_cache import DelegationTokenCache


class FakeReviewClient:
    def __init__(self):
        self.calls = []
        self.submissions = []

    async def call_tool(self, name, arguments, authorization):
        self.calls.append((name, arguments, authorization))
        return {"commitHash": arguments.get("revision", "abc")}

    async def submit_document_change(self, plan, *, workspace_id, run_id, authorization):
        self.submissions.append((plan, workspace_id, run_id, authorization))
        return {"changeRequestId": "11111111-1111-1111-1111-111111111111", "status": "PENDING"}


class FakeDelegationClient:
    def __init__(self):
        self.exchanges = 0

    async def exchange(self, *, delegation_id, job_id):
        self.exchanges += 1
        return "Bearer delegated-token"


@pytest.mark.asyncio
async def test_delegated_client_reuses_cached_token_for_tool_and_submit_calls():
    review = FakeReviewClient()
    delegation = FakeDelegationClient()
    client = DelegatedMcpClient(
        review,
        delegation,
        delegation_id=uuid4(),
        job_id=uuid4(),
        revision="abc",
        token_cache=DelegationTokenCache(300),
    )

    await client.call_tool("devcollab.workspace.get_context", {}, "ignored")
    await client.call_tool("devcollab.repository.list_files", {}, "ignored")
    await client.submit_document_change(
        {"operations": []},
        workspace_id=str(uuid4()),
        run_id="run-1",
        authorization="ignored",
    )

    assert delegation.exchanges == 1
    assert [call[2] for call in review.calls] == [
        "Bearer delegated-token",
        "Bearer delegated-token",
    ]
    assert review.submissions[0][3] == "Bearer delegated-token"


@pytest.mark.asyncio
async def test_delegated_client_without_cache_keeps_exchanging_each_call():
    review = FakeReviewClient()
    delegation = FakeDelegationClient()
    client = DelegatedMcpClient(
        review,
        delegation,
        delegation_id=uuid4(),
        job_id=uuid4(),
        revision="abc",
    )

    await client.call_tool("devcollab.workspace.get_context", {}, "ignored")
    await client.call_tool("devcollab.repository.list_files", {}, "ignored")

    assert delegation.exchanges == 2
