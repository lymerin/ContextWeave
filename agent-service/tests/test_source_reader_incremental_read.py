from uuid import UUID

import pytest

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
