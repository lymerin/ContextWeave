from uuid import UUID

import pytest

from app import perf_metrics
from app.platform_mcp.source_reader import SourceReader
from app.schemas.platform_mcp.source_file import SelectedSourceFileBatch

WORKSPACE_ID = UUID("11111111-1111-1111-1111-111111111111")
REPOSITORY_ID = UUID("22222222-2222-2222-2222-222222222222")


class FakeMcpClient:
    def __init__(self) -> None:
        self.calls: list[tuple[str, dict, str]] = []

    async def call_tool(self, name, arguments, authorization):
        self.calls.append((name, arguments, authorization))
        return {
            "content": f"class App {{ /* {len(self.calls)} */ }}",
            "language": "Java",
            "totalLines": 1,
            "endLine": 1,
            "truncated": False,
        }


class FakeCodeReadBatchClient:
    def __init__(self, contents: dict[str, str]) -> None:
        self.contents = contents
        self.calls: list[tuple[str, dict, str]] = []

    async def call_tool(self, name, arguments, authorization):
        self.calls.append((name, arguments, authorization))
        if name == "devcollab.code.read_batch":
            return {
                "workspaceId": str(WORKSPACE_ID),
                "repositoryId": str(REPOSITORY_ID),
                "revision": arguments["revision"],
                "truncated": False,
                "files": {path: self._page(path, None, None) for path in arguments["paths"]},
            }
        if name == "devcollab.code.read":
            return self._page(
                arguments["path"],
                arguments.get("startLine"),
                arguments.get("endLine"),
            )
        raise AssertionError(f"unexpected tool {name}")

    def _page(self, path: str, start_line: int | None, end_line: int | None) -> dict:
        content = self.contents[path]
        lines = content.splitlines()
        if content == "":
            lines = [""]
        total_lines = len(lines)
        start = 1 if start_line is None else start_line
        end = total_lines if end_line is None else min(end_line, total_lines)
        end = min(end, start + 399)
        selected = "".join(f"{line}\n" for line in lines[start - 1 : end])
        return {
            "path": path,
            "commitHash": "abc123",
            "language": "Python" if path.endswith(".py") else "Java",
            "sizeBytes": len(content.encode("utf-8")),
            "startLine": start,
            "endLine": end,
            "totalLines": total_lines,
            "content": selected,
            "truncated": end < total_lines,
            "omittedLineCount": max(0, total_lines - end),
            "omittedCharacterCount": 0 if end == total_lines else 1,
        }


@pytest.mark.asyncio
async def test_source_reader_reuses_content_for_same_path_and_blob_sha():
    client = FakeMcpClient()
    reader = SourceReader(client)
    selection = SelectedSourceFileBatch(
        repository_id=str(REPOSITORY_ID),
        revision="abc123",
        paths=("src/App.java",),
        total_count=1,
    )

    first = await reader.read_batch(
        WORKSPACE_ID, REPOSITORY_ID, selection, blob_shas={"src/App.java": "blob-a"}
    )
    second = await reader.read_batch(
        WORKSPACE_ID, REPOSITORY_ID, selection, blob_shas={"src/App.java": "blob-a"}
    )

    assert first.files[0].content == second.files[0].content
    assert [call[0] for call in client.calls] == ["devcollab.code.read"]


@pytest.mark.asyncio
async def test_source_reader_misses_when_blob_sha_changes():
    client = FakeMcpClient()
    reader = SourceReader(client)
    selection = SelectedSourceFileBatch(
        repository_id=str(REPOSITORY_ID),
        revision="abc123",
        paths=("src/App.java",),
        total_count=1,
    )

    await reader.read_batch(
        WORKSPACE_ID, REPOSITORY_ID, selection, blob_shas={"src/App.java": "blob-a"}
    )
    await reader.read_batch(
        WORKSPACE_ID, REPOSITORY_ID, selection, blob_shas={"src/App.java": "blob-b"}
    )

    assert [call[0] for call in client.calls] == [
        "devcollab.code.read",
        "devcollab.code.read",
    ]


@pytest.mark.asyncio
async def test_code_read_batch_small_file_needs_no_continuation(tmp_path, monkeypatch):
    monkeypatch.setenv("DEVCOLLAB_PERF_METRICS_PATH", str(tmp_path / "metrics.jsonl"))
    perf_metrics.reset()
    client = FakeCodeReadBatchClient({"src/App.py": "print('ok')"})
    reader = SourceReader(client, code_read_batch_enabled=True)

    result = await reader.read_batch(
        WORKSPACE_ID,
        REPOSITORY_ID,
        selection(("src/App.py",)),
    )

    assert result.files[0].content == "print('ok')\n"
    assert [call[0] for call in client.calls] == ["devcollab.code.read_batch"]
    counters = perf_metrics.snapshot()["counters"]
    assert counters["code_read_batch_calls"] == 1
    assert counters.get("code_read_batch_continuations", 0) == 0


@pytest.mark.asyncio
async def test_code_read_batch_large_file_matches_individual_path_byte_for_byte():
    content = "\n".join(f"line-{line}" for line in range(1, 803))
    individual_client = FakeCodeReadBatchClient({"src/Large.py": content})
    batch_client = FakeCodeReadBatchClient({"src/Large.py": content})
    individual = SourceReader(individual_client, code_read_batch_enabled=False)
    batched = SourceReader(batch_client, code_read_batch_enabled=True)

    individual_result = await individual.read_batch(
        WORKSPACE_ID, REPOSITORY_ID, selection(("src/Large.py",))
    )
    batch_result = await batched.read_batch(
        WORKSPACE_ID, REPOSITORY_ID, selection(("src/Large.py",))
    )

    assert batch_result.files[0].content == individual_result.files[0].content
    assert batch_result.files[0].truncated == individual_result.files[0].truncated
    assert [call[0] for call in batch_client.calls] == [
        "devcollab.code.read_batch",
        "devcollab.code.read",
        "devcollab.code.read",
    ]


@pytest.mark.asyncio
async def test_code_read_batch_exactly_400_lines_does_not_continue():
    content = "\n".join(f"line-{line}" for line in range(1, 401))
    client = FakeCodeReadBatchClient({"src/Exact.py": content})
    reader = SourceReader(client, code_read_batch_enabled=True)

    result = await reader.read_batch(WORKSPACE_ID, REPOSITORY_ID, selection(("src/Exact.py",)))

    assert result.files[0].content.endswith("line-400\n")
    assert [call[0] for call in client.calls] == ["devcollab.code.read_batch"]


@pytest.mark.asyncio
async def test_code_read_batch_empty_file_does_not_continue():
    client = FakeCodeReadBatchClient({"src/Empty.py": ""})
    reader = SourceReader(client, code_read_batch_enabled=True)

    result = await reader.read_batch(WORKSPACE_ID, REPOSITORY_ID, selection(("src/Empty.py",)))

    assert result.files[0].content == "\n"
    assert [call[0] for call in client.calls] == ["devcollab.code.read_batch"]


@pytest.mark.asyncio
async def test_code_read_batch_splits_paths_over_service_limit():
    contents = {f"src/File{index}.py": f"print({index})" for index in range(101)}
    client = FakeCodeReadBatchClient(contents)
    reader = SourceReader(client, code_read_batch_enabled=True)

    result = await reader.read_batch(
        WORKSPACE_ID,
        REPOSITORY_ID,
        selection(tuple(contents.keys())),
    )

    assert len(result.files) == 101
    batch_calls = [call for call in client.calls if call[0] == "devcollab.code.read_batch"]
    assert [len(call[1]["paths"]) for call in batch_calls] == [100, 1]


@pytest.mark.asyncio
async def test_code_read_batch_uses_blob_cache_for_full_content():
    content = "\n".join(f"line-{line}" for line in range(1, 402))
    client = FakeCodeReadBatchClient({"src/Cached.py": content})
    reader = SourceReader(client, code_read_batch_enabled=True)

    await reader.read_batch(
        WORKSPACE_ID,
        REPOSITORY_ID,
        selection(("src/Cached.py",)),
        blob_shas={"src/Cached.py": "blob-a"},
    )
    second = await reader.read_batch(
        WORKSPACE_ID,
        REPOSITORY_ID,
        selection(("src/Cached.py",)),
        blob_shas={"src/Cached.py": "blob-a"},
    )

    assert second.files[0].content.endswith("line-401\n")
    assert [call[0] for call in client.calls] == [
        "devcollab.code.read_batch",
        "devcollab.code.read",
    ]


def selection(paths: tuple[str, ...]) -> SelectedSourceFileBatch:
    return SelectedSourceFileBatch(
        repository_id=str(REPOSITORY_ID),
        revision="abc123",
        paths=paths,
        total_count=len(paths),
    )


@pytest.mark.asyncio
async def test_source_reader_without_blob_sha_keeps_direct_read_behavior():
    client = FakeMcpClient()
    reader = SourceReader(client)
    selection = SelectedSourceFileBatch(
        repository_id=str(REPOSITORY_ID),
        revision="abc123",
        paths=("src/App.java",),
        total_count=1,
    )

    await reader.read_batch(WORKSPACE_ID, REPOSITORY_ID, selection)
    await reader.read_batch(WORKSPACE_ID, REPOSITORY_ID, selection)

    assert [call[0] for call in client.calls] == [
        "devcollab.code.read",
        "devcollab.code.read",
    ]
