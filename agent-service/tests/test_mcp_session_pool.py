import pytest

from app.runtime.mcp_session_pool import McpSessionPool


class FakeAsyncContext:
    def __init__(self, value):
        self.value = value
        self.closed = False

    async def __aenter__(self):
        return self.value

    async def __aexit__(self, exc_type, exc, tb):
        self.closed = True


class FakeSession:
    def __init__(self):
        self.initialize_count = 0
        self.fail_next = False
        self.calls = []

    async def initialize(self):
        self.initialize_count += 1

    async def call_tool(self, name, arguments):
        self.calls.append((name, arguments))
        if self.fail_next:
            self.fail_next = False
            raise TimeoutError("timed out")
        return {"name": name, "arguments": arguments}


@pytest.mark.asyncio
async def test_reuses_job_session_without_reinitializing():
    sessions = []

    def session_factory(_read, _write):
        session = FakeSession()
        sessions.append(session)
        return FakeAsyncContext(session)

    pool = McpSessionPool(
        "http://mcp.test",
        30,
        http_client_factory=lambda _headers, _timeout: FakeAsyncContext(object()),
        transport_factory=lambda _base_url, _client: FakeAsyncContext(("read", "write", None)),
        session_factory=session_factory,
    )

    await pool.call_tool("job-1", "tool.a", {"a": 1}, "token")
    await pool.call_tool("job-1", "tool.b", {"b": 2}, "token")

    assert len(sessions) == 1
    assert sessions[0].initialize_count == 1
    assert sessions[0].calls == [("tool.a", {"a": 1}), ("tool.b", {"b": 2})]


@pytest.mark.asyncio
async def test_rebuilds_job_session_after_call_exception():
    sessions = []

    def session_factory(_read, _write):
        session = FakeSession()
        sessions.append(session)
        return FakeAsyncContext(session)

    pool = McpSessionPool(
        "http://mcp.test",
        30,
        http_client_factory=lambda _headers, _timeout: FakeAsyncContext(object()),
        transport_factory=lambda _base_url, _client: FakeAsyncContext(("read", "write", None)),
        session_factory=session_factory,
    )

    first = await pool.get_session("job-1", "token")
    first.fail_next = True
    with pytest.raises(TimeoutError):
        await pool.call_tool("job-1", "tool.a", {}, "token")

    await pool.call_tool("job-1", "tool.a", {}, "token")

    assert len(sessions) == 2
    assert [session.initialize_count for session in sessions] == [1, 1]
