from __future__ import annotations

from uuid import UUID

from app.runtime.project_discovery import _build_file_dependencies
from app.runtime.semantic_planner import ProjectFile
from app.schemas.unit_plans import UnitPlan


def pfile(path: str, imports: tuple[str, ...]) -> ProjectFile:
    return ProjectFile(
        id=UUID("11111111-1111-1111-1111-111111111111"),
        file_path=path,
        language="Python",
        size_bytes=100,
        import_keys=imports,
    )


# ── _build_file_dependencies（轻量 import 边）────────────────────────────


def test_dependencies_resolve_existing_files() -> None:
    files = [
        pfile("app/main.py", ("app.service", "fastapi")),
        pfile("app/service.py", ("app.util",)),
        pfile("app/util.py", ()),
    ]
    edges = _build_file_dependencies(files)
    assert {"from": "app/main.py", "to": "app/service.py", "kind": "IMPORT"} in edges
    assert {"from": "app/service.py", "to": "app/util.py", "kind": "IMPORT"} in edges
    # 外部/不存在的模块不产生边
    assert not any(e["to"] == "fastapi" for e in edges)


def test_dependencies_ambiguous_module_no_edge() -> None:
    # app/x.py 与 app/x/__init__.py 同时存在 → 歧义，不收边
    files = [
        pfile("app/a.py", ("app.x",)),
        pfile("app/x.py", ()),
        pfile("app/x/__init__.py", ()),
    ]
    edges = _build_file_dependencies(files)
    assert not edges


def test_dependencies_deterministic() -> None:
    files = [
        pfile("b.py", ("a",)),
        pfile("a.py", ()),
    ]
    first = _build_file_dependencies(files)
    second = _build_file_dependencies(files)
    assert first == second
    assert first == [{"from": "b.py", "to": "a.py", "kind": "IMPORT"}]


# ── UnitPlan 业务字段 ────────────────────────────────────────────────────


def test_unit_plan_accepts_business_role_and_primary_flow() -> None:
    plan = UnitPlan.model_validate({
        "units": [
            {
                "name": "工作区管理",
                "kind": "BUSINESS_SERVICE",
                "summary": "工作区创建、成员管理与权限校验。",
                "primaryFiles": ["app/workspace/service.py"],
                "supportingFiles": [],
                "relatedDocumentIds": [],
                "groupingEvidence": [
                    "fileDependencies: app/workspace/service.py → app/workspace/repo.py"
                ],
                "businessRole": "负责工作区创建、成员管理与权限校验",
                "primaryFlow": "POST /workspaces → WorkspaceService.create → MemberRepository.save",
            }
        ]
    })
    item = plan.units[0]
    assert item.businessRole.startswith("负责工作区")
    assert "WorkspaceService.create" in item.primaryFlow


def test_unit_plan_business_fields_optional() -> None:
    plan = UnitPlan.model_validate({
        "units": [
            {
                "name": "订单",
                "kind": "BUSINESS_SERVICE",
                "summary": "订单创建。",
                "primaryFiles": ["app/order/service.py"],
                "supportingFiles": [],
                "relatedDocumentIds": [],
                "groupingEvidence": ["SERVICE roleHint"],
            }
        ]
    })
    assert plan.units[0].businessRole == ""
    assert plan.units[0].primaryFlow == ""
