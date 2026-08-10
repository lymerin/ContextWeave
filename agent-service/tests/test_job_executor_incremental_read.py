from uuid import UUID

import pytest

from app.execution.job_executor import JobExecutor
from app.model_context_mcp.snapshot_store_registry import SnapshotStoreRegistry
from app.schemas.platform_mcp.source_file import RepositoryFileRef, SourceFileBatch
from app.schemas.platform_mcp.workspace import RepositoryRef, WorkspaceContext
from app.source_selection.file_filter import SourceFileFilter


WORKSPACE_ID = UUID("11111111-1111-1111-1111-111111111111")
REPOSITORY_ID = UUID("22222222-2222-2222-2222-222222222222")


class FakeWorkspaceReader:
    def __init__(self) -> None:
        self.list_files_calls = 0

    async def read_context(self, workspace_id, repository_id):
        return WorkspaceContext(
            workspace_id=workspace_id,
            repositories=[
                RepositoryRef(
                    repository_id=repository_id,
                    name="repo",
                    default_branch="main",
                    last_synced_commit="abc123",
                )
            ],
        )

    async def list_files(self, workspace_id, repository_id):
        self.list_files_calls += 1
        return [
            RepositoryFileRef(
                file_path="src/App.py",
                extension=".py",
                size_bytes=12,
                language="Python",
                readable=True,
                blob_sha="blob-a",
            )
        ]


class FakeSourceReader:
    def __init__(self) -> None:
        self.blob_shas = object()

    async def read_batch(self, workspace_id, repository_id, selection, blob_shas=None):
        self.blob_shas = blob_shas
        return SourceFileBatch(
            repository_id=str(repository_id),
            revision=selection.revision,
            files=(),
            total_count=0,
        )


@pytest.mark.asyncio
async def test_job_executor_passes_blob_shas_when_incremental_read_enabled():
    workspace_reader = FakeWorkspaceReader()
    source_reader = FakeSourceReader()
    executor = executor_with(
        workspace_reader,
        source_reader,
        incremental_read_enabled=True,
    )

    result = await executor.execute(
        workspace_id=WORKSPACE_ID,
        repository_id=REPOSITORY_ID,
        revision="abc123",
        selected_paths=["src/App.py"],
        run_id="run-1",
    )

    assert result["status"] == "EMPTY_SCOPE"
    assert workspace_reader.list_files_calls == 1
    assert source_reader.blob_shas == {"src/App.py": "blob-a"}


@pytest.mark.asyncio
async def test_job_executor_does_not_list_files_when_incremental_read_disabled():
    workspace_reader = FakeWorkspaceReader()
    source_reader = FakeSourceReader()
    executor = executor_with(
        workspace_reader,
        source_reader,
        incremental_read_enabled=False,
    )

    result = await executor.execute(
        workspace_id=WORKSPACE_ID,
        repository_id=REPOSITORY_ID,
        revision="abc123",
        selected_paths=["src/App.py"],
        run_id="run-1",
    )

    assert result["status"] == "EMPTY_SCOPE"
    assert workspace_reader.list_files_calls == 0
    assert source_reader.blob_shas is None


def executor_with(
    workspace_reader,
    source_reader,
    *,
    incremental_read_enabled: bool,
) -> JobExecutor:
    return JobExecutor(
        workspace_reader=workspace_reader,
        source_reader=source_reader,
        document_reader=None,
        binding_reader=None,
        plan_writer=None,
        file_filter=SourceFileFilter(),
        registry=SnapshotStoreRegistry(),
        provider=None,
        incremental_read_enabled=incremental_read_enabled,
    )
