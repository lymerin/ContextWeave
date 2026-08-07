"""Java 入口发现与范围圈定（S5）：HTTP_ROUTE 入口、跨文件 BFS、类型依赖。"""

from __future__ import annotations

from uuid import UUID

from app.schemas.scope import EntryKind, MemberRole, ScopeMember
from app.schemas.source_file import SourceFileBatch, SourceFileRef
from app.source_analysis.atom_relation_graph import build_graph
from app.source_analysis.code_ast_atom import parse_batch
from app.source_analysis.graph_entry_scope import discover_scopes

REPO = UUID("33333333-3333-3333-3333-333333333333")
REV = "rev-java-scopes"


def build(files: dict[str, str]):
    batch = SourceFileBatch(
        repository_id=REPO,
        revision=REV,
        files=tuple(
            SourceFileRef(file_path=path, language="Java", size_bytes=len(source))
            for path, source in sorted(files.items())
        ),
        total_count=len(files),
    )
    sources = dict(files)
    catalog = parse_batch(batch, lambda path: sources.get(path))
    graph = build_graph(catalog, lambda path: sources.get(path))
    return graph


def member_by_name(graph, scope, name: str) -> ScopeMember:
    key = next(s.symbol_key for s in graph.catalog.symbols if s.name == name)
    return next(m for m in scope.members if m.symbol_key == key)


# ── 入口 ─────────────────────────────────────────────────────────────────


def test_http_route_entry_with_class_prefix() -> None:
    graph = build(
        {
            "com/devcollab/web/AuthController.java": (
                "package com.devcollab.web;\n"
                "\n"
                "@RestController\n"
                "@RequestMapping(\"/api/v1\")\n"
                "public class AuthController {\n"
                "    @GetMapping(\"/users\")\n"
                "    public String list() { return null; }\n"
                "}\n"
            ),
        }
    )
    scopes = discover_scopes(graph)
    assert len(scopes) == 1
    entry = scopes[0].entries[0]
    assert entry.kind == EntryKind.HTTP_ROUTE
    assert entry.label == "GET /api/v1/users"


def test_private_method_not_an_entry() -> None:
    graph = build(
        {
            "com/devcollab/auth/Helper.java": (
                "package com.devcollab.auth;\n"
                "\n"
                "public class Helper {\n"
                "    private void secret() {}\n"
                "\n"
                "    public void run() {}\n"
                "}\n"
            ),
        }
    )
    scopes = discover_scopes(graph)
    assert len(scopes) == 1
    helper = next(s for s in graph.catalog.symbols if s.name == "Helper")
    assert {e.symbol_key for e in scopes[0].entries} == {helper.symbol_key}


# ── 跨文件 BFS ───────────────────────────────────────────────────────────


def test_cross_file_bfs_distances() -> None:
    graph = build(
        {
            "com/devcollab/web/UserController.java": (
                "package com.devcollab.web;\n"
                "\n"
                "import com.devcollab.svc.AuthService;\n"
                "\n"
                "@RestController\n"
                "public class UserController {\n"
                "    private final AuthService service;\n"
                "\n"
                "    public UserController(AuthService service) {\n"
                "        this.service = service;\n"
                "    }\n"
                "\n"
                "    @GetMapping(\"/users\")\n"
                "    public String list() {\n"
                "        return service.create();\n"
                "    }\n"
                "}\n"
            ),
            "com/devcollab/svc/AuthService.java": (
                "package com.devcollab.svc;\n"
                "\n"
                "import com.devcollab.repo.UserRepo;\n"
                "\n"
                "public class AuthService {\n"
                "    private final UserRepo repo;\n"
                "\n"
                "    public AuthService(UserRepo repo) {\n"
                "        this.repo = repo;\n"
                "    }\n"
                "\n"
                "    public String create() {\n"
                "        return repo.save();\n"
                "    }\n"
                "}\n"
            ),
            "com/devcollab/repo/UserRepo.java": (
                "package com.devcollab.repo;\n"
                "\n"
                "public class UserRepo {\n"
                "    public String save() { return null; }\n"
                "}\n"
            ),
        }
    )
    scopes = discover_scopes(graph)
    assert len(scopes) == 1
    scope = scopes[0]
    assert scope.entries[0].label == "GET /users"
    list_m = member_by_name(graph, scope, "list")
    create_m = member_by_name(graph, scope, "create")
    save_m = member_by_name(graph, scope, "save")
    assert list_m.role == MemberRole.ENTRY and list_m.distance == 0
    assert create_m.role == MemberRole.DIRECT_CALLEE and create_m.distance == 1
    assert save_m.role == MemberRole.INDIRECT_CALLEE and save_m.distance == 2
    assert set(scope.related_files) == {
        "com/devcollab/web/UserController.java",
        "com/devcollab/svc/AuthService.java",
        "com/devcollab/repo/UserRepo.java",
    }


# ── 类型依赖 ─────────────────────────────────────────────────────────────


def test_nested_record_pulled_as_type_dependency() -> None:
    graph = build(
        {
            "com/devcollab/web/AuthController.java": (
                "package com.devcollab.web;\n"
                "\n"
                "public class AuthController {\n"
                "    public record LoginRequest(String name) {}\n"
                "\n"
                "    @GetMapping(\"/login\")\n"
                "    public LoginRequest login() {\n"
                "        return null;\n"
                "    }\n"
                "}\n"
            ),
        }
    )
    scopes = discover_scopes(graph)
    assert len(scopes) == 1
    scope = scopes[0]
    assert scope.entries[0].kind == EntryKind.HTTP_ROUTE
    rec = next(s for s in graph.catalog.symbols if s.name == "LoginRequest")
    member = next(m for m in scope.members if m.symbol_key == rec.symbol_key)
    assert member.role == MemberRole.TYPE_DEPENDENCY


def test_inherits_and_implements_pulled_into_scope() -> None:
    graph = build(
        {
            "com/devcollab/web/UserController.java": (
                "package com.devcollab.web;\n"
                "\n"
                "import com.devcollab.svc.UserService;\n"
                "\n"
                "public class UserController {\n"
                "    @GetMapping(\"/run\")\n"
                "    public void run() {\n"
                "        new UserService();\n"
                "    }\n"
                "}\n"
            ),
            "com/devcollab/svc/BaseService.java": (
                "package com.devcollab.svc;\n"
                "\n"
                "public class BaseService {}\n"
            ),
            "com/devcollab/svc/IAuth.java": (
                "package com.devcollab.svc;\n"
                "\n"
                "public interface IAuth {}\n"
            ),
            "com/devcollab/svc/UserService.java": (
                "package com.devcollab.svc;\n"
                "\n"
                "public class UserService extends BaseService implements IAuth {\n"
                "    public void go() {}\n"
                "}\n"
            ),
        }
    )
    scopes = discover_scopes(graph)
    assert len(scopes) == 1
    scope = scopes[0]
    by_symbol = {s.name: s.symbol_key for s in graph.catalog.symbols}
    member_keys = {m.symbol_key for m in scope.members}
    assert {by_symbol["UserService"], by_symbol["BaseService"], by_symbol["IAuth"]} <= member_keys
    base = next(
        m for m in scope.members if m.symbol_key == by_symbol["BaseService"]
    )
    assert base.role == MemberRole.TYPE_DEPENDENCY
