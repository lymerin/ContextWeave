from __future__ import annotations

from datetime import UTC, datetime
from typing import Any
from uuid import UUID, uuid4

import pytest
from conftest import (
    FakeDelegationClient,
    FakeMcpClient,
    FakeModelProvider,
    MemoryAgentJobRepository,
    MemoryRunStore,
)
from fastapi.testclient import TestClient

from app.config import Settings
from app.execution.errors import JobExecutionError
from app.main import create_app
from app.runtime.file_classification import classify_file
from app.runtime.project_discovery import ProjectDiscoveryService
from app.runtime.semantic_planner import ProjectFile, build_semantic_units
from app.schemas.unit_plans import UnitPlan
from app.worker import AgentWorker

WORKSPACE_ID = UUID("11111111-1111-1111-1111-111111111111")
REPOSITORY_ID = UUID("22222222-2222-2222-2222-222222222222")
AUTH = {"Authorization": "Bearer transient-secret"}


def _file(
    path: str,
    role: str,
    *,
    imports: tuple[str, ...] = (),
    module: str | None = None,
) -> ProjectFile:
    return ProjectFile(
        id=uuid4(),
        file_path=path,
        language="TypeScript" if path.endswith(".ts") else "Java",
        size_bytes=100,
        package_name="com.devcollab.auth" if path.endswith(".java") else None,
        module_key=module or path.split("/", 1)[0],
        layer_hint=role,
        role_hints=(role,),
        import_keys=imports,
    )


def test_semantic_units_overlap_preserve_many_to_many_and_are_deterministic() -> None:
    first_document = UUID("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    second_document = UUID("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
    files = [
        _file(
            "knowledge-core/src/auth/AuthController.java",
            "CONTROLLER",
            imports=("com.devcollab.auth.AuthService",),
        ),
        _file(
            "knowledge-core/src/auth/AuthService.java",
            "SERVICE",
            imports=("com.devcollab.auth.AuthRepository",),
        ),
        _file("knowledge-core/src/auth/AuthRepository.java", "REPOSITORY"),
        _file(
            "knowledge-core/src/security/SecurityConfig.java",
            "CONFIG",
            imports=("com.devcollab.security.JwtFilter",),
        ),
        _file("knowledge-core/src/security/JwtFilter.java", "FILTER"),
        _file(
            "web/src/api/auth.ts",
            "API_CLIENT",
            imports=("@/api/http",),
            module="web",
        ),
        _file(
            "web/src/api/document.ts",
            "API_CLIENT",
            imports=("@/api/http",),
            module="web",
        ),
        _file("web/src/api/http.ts", "INTEGRATION", module="web"),
    ]
    bindings = {
        files[0].file_path: [
            {"documentId": str(first_document)},
            {"documentId": str(second_document)},
        ],
        files[1].file_path: [{"documentId": str(first_document)}],
    }

    first = build_semantic_units(
        files,
        bindings,
        job_id=UUID("11111111-1111-1111-1111-111111111111"),
        revision="abc123",
        max_primary_files=6,
        max_supporting_files=4,
        max_total_files=10,
        max_units=50,
    )
    second = build_semantic_units(
        files,
        bindings,
        job_id=UUID("22222222-2222-2222-2222-222222222222"),
        revision="abc123",
        max_primary_files=6,
        max_supporting_files=4,
        max_total_files=10,
        max_units=50,
    )

    assert [unit.unit_fingerprint for unit in first] == [
        unit.unit_fingerprint for unit in second
    ]
    controller_units = [
        unit
        for unit in first
        if files[0].file_path in {item.file_path for item in unit.files}
    ]
    assert len(controller_units) >= 2
    assert any(len(unit.documents) >= 2 for unit in controller_units)
    assert all(any(item.role == "PRIMARY" for item in unit.files) for unit in first)
    assert all(len(unit.files) <= 10 for unit in first)

    backend_primary_units = [
        unit for unit in first if files[0].file_path in unit.primary_paths
    ]
    frontend_primary_units = [
        unit for unit in first if files[5].file_path in unit.primary_paths
    ]
    assert backend_primary_units
    assert frontend_primary_units
    assert not any(
        files[5].file_path in unit.primary_paths for unit in backend_primary_units
    )
    assert any(
        files[7].file_path
        in {item.file_path for item in unit.files if item.role != "PRIMARY"}
        for unit in frontend_primary_units
    )


def test_bound_primary_files_are_stably_split_without_loss() -> None:
    document_id = UUID("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    files = [_file(f"core/src/feature/Service{index}.java", "SERVICE") for index in range(5)]
    bindings = {
        item.file_path: [{"documentId": str(document_id)}] for item in files
    }
    units = build_semantic_units(
        files,
        bindings,
        job_id=UUID("11111111-1111-1111-1111-111111111111"),
        revision="revision-1",
        max_primary_files=2,
        max_supporting_files=0,
        max_total_files=2,
        max_units=10,
    )
    primary = [
        item.file_path
        for unit in units
        for item in unit.files
        if item.role == "PRIMARY"
    ]
    assert sorted(primary) == sorted(item.file_path for item in files)
    assert all("SIZE_LIMIT_SPLIT" in unit.grouping_reasons for unit in units)
    assert all(len(unit.primary_paths) <= 2 for unit in units)


@pytest.mark.parametrize(
    ("path", "size", "readable", "expected"),
    [
        ("src/App.java", 10, True, "SUPPORTED_CODE"),
        ("docs/readme.md", 10, True, "TEXT_NON_CODE_SKIPPED"),
        ("node_modules/pkg/index.js", 10, True, "VENDOR_SKIPPED"),
        ("src/generated/Api.java", 10, True, "GENERATED_SKIPPED"),
        ("src/large.py", 101, True, "OVERSIZED_SKIPPED"),
        ("assets/logo.png", 10, False, "BINARY_SKIPPED"),
        ("src/unknown.xyz", 10, True, "UNSUPPORTED_EXTENSION_SKIPPED"),
    ],
)
def test_project_file_classification(
    path: str,
    size: int,
    readable: bool,
    expected: str,
) -> None:
    classified = classify_file(
        {"filePath": path, "sizeBytes": size, "readable": readable},
        max_size_bytes=100,
    )
    assert classified.classification == expected


class PagingProjectMcp(FakeMcpClient):
    def __init__(self) -> None:
        super().__init__(bound=False)
        self.page = 0

    async def call_tool(
        self, name: str, arguments: dict[str, Any], authorization: str
    ) -> dict[str, Any]:
        if name == "devcollab.repository.list_files":
            self.calls.append((name, arguments, authorization))
            pages = [
                [
                    _metadata("src/AuthController.java", 120),
                    _metadata("README.md", 50),
                ],
                [
                    _metadata("src/AuthController.java", 120),
                    _metadata("src/AuthService.java", 130),
                    _metadata("node_modules/pkg/index.js", 20),
                ],
            ]
            index = 1 if arguments.get("cursor") else 0
            return {
                "revision": "abc",
                "files": pages[index],
                "hasMore": index == 0,
                "nextCursor": "page-2" if index == 0 else None,
            }
        if name == "devcollab.repository.inspect_code_metadata":
            self.calls.append((name, arguments, authorization))
            return {
                "revision": "abc",
                "files": [
                    {
                        "filePath": path,
                        "language": "Java",
                        "moduleKey": "src",
                        "roleHints": ["CONTROLLER"]
                        if path.endswith("Controller.java")
                        else ["SERVICE"],
                        "imports": [],
                        "exportedSymbols": [],
                        "topLevelSymbols": [],
                        "parseStatus": "FAILED"
                        if path.endswith("Service.java")
                        else "PARSED",
                        "errorCode": "PARSE_FAILED"
                        if path.endswith("Service.java")
                        else None,
                    }
                    for path in arguments["filePaths"]
                ]
            }
        return await super().call_tool(name, arguments, authorization)


@pytest.mark.asyncio
async def test_project_discovery_pages_deduplicates_batches_and_never_persists_source(
    settings: Settings,
) -> None:
    client = PagingProjectMcp()
    phases: list[str] = []

    async def on_phase(phase: str) -> None:
        phases.append(phase)

    service = ProjectDiscoveryService(client, settings, on_phase)
    rows, project_files, planner_batches, project_index, stats = await service.execute(
        job_id=uuid4(),
        workspace_id=WORKSPACE_ID,
        repository_id=REPOSITORY_ID,
        revision="abc",
    )

    assert stats["discovered_file_count"] == 4
    assert stats["supported_code_count"] == 2
    assert stats["metadata_failed_count"] == 1
    assert stats["skipped_file_count"] == 2
    assert project_files
    assert project_index["files"]
    assert planner_batches
    assert all("content" not in row and "authorization" not in row for row in rows)
    assert phases == [
        "DISCOVERING_FILES",
        "CLASSIFYING_FILES",
        "LOADING_CODE_METADATA",
        "LOADING_BINDINGS",
    ]
    assert not any(
        name == "devcollab.binding.list" for name, _arguments, _auth in client.calls
    )


@pytest.mark.asyncio
async def test_project_discovery_counts_missing_metadata_and_rejects_wrong_revision(
    settings: Settings,
) -> None:
    class MetadataBoundaryMcp(PagingProjectMcp):
        metadata_revision = "abc"
        omit_last = True

        async def call_tool(
            self,
            name: str,
            arguments: dict[str, Any],
            authorization: str | None,
        ) -> dict[str, Any]:
            if name != "devcollab.repository.inspect_code_metadata":
                return await super().call_tool(name, arguments, authorization)
            paths = list(arguments["filePaths"])
            returned = paths[:-1] if self.omit_last else paths
            return {
                "revision": self.metadata_revision,
                "files": [
                    {
                        "filePath": path,
                        "parseStatus": "PARSED",
                        "roleHints": [],
                        "imports": [],
                        "exportedSymbols": [],
                        "topLevelSymbols": [],
                    }
                    for path in returned
                ],
            }

    client = MetadataBoundaryMcp()

    async def on_phase(_phase: str) -> None:
        return None

    service = ProjectDiscoveryService(client, settings, on_phase)
    rows, _project_files, _planner_batches, _project_index, stats = await service.execute(
        job_id=uuid4(),
        workspace_id=WORKSPACE_ID,
        repository_id=REPOSITORY_ID,
        revision="abc",
    )
    missing_rows = [row for row in rows if row["metadata_error"] == "METADATA_MISSING"]
    assert len(missing_rows) == 1
    assert stats["metadata_failed_count"] == 1

    client.omit_last = False
    client.metadata_revision = "different"
    with pytest.raises(JobExecutionError) as captured:
        await service.execute(
            job_id=uuid4(),
            workspace_id=WORKSPACE_ID,
            repository_id=REPOSITORY_ID,
            revision="abc",
        )
    assert captured.value.code == "REVISION_CHANGED"


@pytest.mark.asyncio
async def test_project_discovery_fails_instead_of_truncating_file_limit(
    settings: Settings,
) -> None:
    client = PagingProjectMcp()

    async def on_phase(_phase: str) -> None:
        return None

    service = ProjectDiscoveryService(
        client,
        settings.model_copy(update={"agent_project_max_files": 2}),
        on_phase,
    )
    with pytest.raises(JobExecutionError) as captured:
        await service.execute(
            job_id=uuid4(),
            workspace_id=WORKSPACE_ID,
            repository_id=REPOSITORY_ID,
            revision="abc",
        )
    assert captured.value.code == "DISCOVERY_LIMIT_EXCEEDED"


def test_create_project_job_is_enqueue_only_and_units_query_is_authorized(
    settings: Settings,
) -> None:
    repository = MemoryAgentJobRepository()
    model = FakeModelProvider()
    with TestClient(
        create_app(
            settings=settings,
            mcp_client=FakeMcpClient(),
            run_store=MemoryRunStore(),
            model_provider=model,
            job_repository=repository,
            delegation_client=FakeDelegationClient(),
        )
    ) as client:
        created = client.post(
            "/api/v1/agent-jobs",
            json={
                "workspaceId": str(WORKSPACE_ID),
                "repositoryId": str(REPOSITORY_ID),
                "scope": {"type": "PROJECT_INITIALIZATION"},
                "userInstruction": None,
            },
            headers=AUTH,
        )
        units = client.get(
            f"/api/v1/agent-jobs/{created.json()['jobId']}/units",
            headers=AUTH,
        )

    assert created.status_code == 202
    assert units.status_code == 200
    assert units.json()["units"] == []
    assert model.calls == []
    stored_unit = next(iter(repository.units.values()))
    assert stored_unit["unit_kind"] == "PROJECT_DISCOVERY"
    assert stored_unit["status"] == "PENDING"


@pytest.mark.asyncio
async def test_worker_plans_project_units_and_enqueues_them_for_execution(
    settings: Settings,
) -> None:
    repository = MemoryAgentJobRepository()
    now = datetime.now(UTC)
    await repository.create_job(
        {
            "id": uuid4(),
            "delegation_id": UUID("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
            "created_by_user_id": UUID("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
            "workspace_id": WORKSPACE_ID,
            "repository_id": REPOSITORY_ID,
            "revision": "abc",
            "scope_type": "PROJECT_INITIALIZATION",
            "scope_payload": {"type": "PROJECT_INITIALIZATION"},
            "user_instruction": None,
            "created_at": now,
        },
        {
            "id": uuid4(),
            "max_attempts": 3,
            "unit_kind": "PROJECT_DISCOVERY",
        },
    )
    model = FakeModelProvider()
    model.unit_plans = [
        UnitPlan.model_validate(
            {
                "units": [
                    {
                        "name": "示例业务能力",
                        "kind": "BUSINESS_SERVICE",
                        "summary": "说明示例业务服务的职责与边界。",
                        "primaryFiles": ["src/Example.java"],
                        "supportingFiles": [],
                        "relatedDocumentIds": [],
                        "groupingEvidence": ["SERVICE roleHint"],
                    },
                    {
                        "name": "示例接口能力",
                        "kind": "BACKEND_REST_API",
                        "summary": "说明示例接口的输入输出边界。",
                        "primaryFiles": ["src/Example.java"],
                        "supportingFiles": [],
                        "relatedDocumentIds": [],
                        "groupingEvidence": ["CONTROLLER roleHint"],
                    },
                ]
            }
        )
    ]
    mcp = FakeMcpClient(bound=False)
    worker = AgentWorker(
        repository,
        mcp,
        FakeDelegationClient(),
        model,
        settings.model_copy(
            update={
                "agent_worker_heartbeat_seconds": 0.01,
                "agent_project_execution_limit": 1,
            }
        ),
        worker_id="project-worker",
    )
    claimed = await repository.claim_next_unit("project-worker", 60)
    assert claimed is not None

    await worker._execute(claimed)

    job = next(iter(repository.jobs.values()))
    assert job["status"] == "RUNNING"
    assert job["phase"] == "EXECUTING_UNITS"
    assert job["completed_units"] == 0
    assert job["review_request_ids"] == []
    assert len(model.unit_plan_calls) == 1
    assert (
        model.unit_plan_calls[0]["projectIndex"]["requestedMaxUnits"]
        == settings.agent_max_analysis_units
    )
    assert mcp.submissions == []
    semantic = [
        item for item in repository.units.values()
        if item["unit_kind"] == "SEMANTIC_ANALYSIS"
    ]
    assert len(semantic) == 2
    assert [item["status"] for item in semantic].count("PENDING") == 1
    assert [item["status"] for item in semantic].count("READY_FOR_ANALYSIS") == 1
    assert await repository.claim_next_unit("other-worker", 60) is not None


def _metadata(path: str, size: int) -> dict[str, Any]:
    suffix = path.rsplit(".", 1)[-1] if "." in path else ""
    return {
        "filePath": path,
        "fileName": path.rsplit("/", 1)[-1],
        "extension": suffix,
        "sizeBytes": size,
        "language": "Java" if suffix == "java" else None,
        "readable": True,
        "isDirectory": False,
    }
