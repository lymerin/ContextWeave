import pytest

from app.clients.mcp_client import McpClientError, OfficialMcpClient


def test_official_client_allows_code_read_batch():
    assert "devcollab.code.read_batch" in OfficialMcpClient.ALLOWED_TOOLS


class FakePool:
    def __init__(self):
        self.calls = []
        self.fail = False

    async def call_tool(self, job_id, name, arguments, authorization):
        self.calls.append((job_id, name, arguments, authorization))
        if self.fail:
            raise TimeoutError("timed out")
        return FakeToolResult({"ok": True})


class FakeToolResult:
    def __init__(self, structured):
        self.structuredContent = structured
        self.content = []
        self.isError = False


@pytest.mark.asyncio
async def test_official_client_uses_session_pool_when_configured():
    pool = FakePool()
    client = OfficialMcpClient("http://mcp.test", 30, session_pool=pool, job_id="job-1")

    result = await client.call_tool(
        "devcollab.workspace.get_context", {"workspaceId": "w"}, "Bearer token"
    )

    assert result == {"ok": True}
    assert pool.calls == [
        (
            "job-1",
            "devcollab.workspace.get_context",
            {"workspaceId": "w"},
            "Bearer token",
        )
    ]


@pytest.mark.asyncio
async def test_official_client_preserves_timeout_mapping_for_session_pool():
    pool = FakePool()
    pool.fail = True
    client = OfficialMcpClient("http://mcp.test", 30, session_pool=pool, job_id="job-1")

    with pytest.raises(McpClientError) as error:
        await client.call_tool("devcollab.workspace.get_context", {}, "Bearer token")

    assert error.value.code == "MCP_UNAVAILABLE"
