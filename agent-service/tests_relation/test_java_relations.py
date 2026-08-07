"""Java 关系图（S4）：CALLS/CREATES/THROWS/类型依赖/FIELD 读写/继承实现。"""

from __future__ import annotations

from uuid import UUID

from app.schemas.ast_atom import AtomCatalog
from app.schemas.repository_graph import (
    Relation,
    RelationCategory,
    RelationKind,
    RepositoryCodeGraph,
)
from app.schemas.source_file import SourceFileBatch, SourceFileRef
from app.source_analysis.atom_relation_graph import build_graph
from app.source_analysis.code_ast_atom import parse_batch

REPO = UUID("22222222-2222-2222-2222-222222222222")
REV = "rev-java"


def build(files: dict[str, str]) -> tuple[AtomCatalog, RepositoryCodeGraph]:
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
    return catalog, graph


def find(
    graph: RepositoryCodeGraph,
    *,
    category: str,
    kind: str,
    source_file: str,
    target_external: str | None = None,
) -> list[Relation]:
    return [
        r
        for r in graph.relations
        if r.category == category
        and r.kind == kind
        and r.file_path == source_file
        and (target_external is None or r.target_external == target_external)
    ]


def target_name(graph: RepositoryCodeGraph, rel: Relation) -> str:
    return next(
        s for s in graph.catalog.symbols if s.atom_id == rel.target_atom_id
    ).name


# ── 调用 ────────────────────────────────────────────────────────────────


def test_bare_and_this_calls_same_class() -> None:
    _, graph = build(
        {
            "auth/AuthService.java": (
                "package com.devcollab.auth;\n"
                "\n"
                "public class AuthService {\n"
                "    public void run() {\n"
                "        save();\n"
                "        this.reset();\n"
                "    }\n"
                "\n"
                "    public void save() {}\n"
                "\n"
                "    public void reset() {}\n"
                "}\n"
            ),
        }
    )
    calls = find(
        graph, category=RelationCategory.INTERNAL, kind=RelationKind.CALLS,
        source_file="auth/AuthService.java",
    )
    assert {target_name(graph, r) for r in calls} == {"save", "reset"}
    # 类 → 方法 CONTAINS
    contains = find(
        graph, category=RelationCategory.INTERNAL, kind=RelationKind.CONTAINS,
        source_file="auth/AuthService.java",
    )
    assert {target_name(graph, r) for r in contains} == {"run", "save", "reset"}


def test_new_local_record_upgraded_to_creates() -> None:
    _, graph = build(
        {
            "auth/UserService.java": (
                "package com.devcollab.auth;\n"
                "\n"
                "public class UserService {\n"
                "    public record LoginRequest(String name) {}\n"
                "\n"
                "    public void login() {\n"
                "        new LoginRequest(\"x\");\n"
                "    }\n"
                "}\n"
            ),
        }
    )
    creates = find(
        graph, category=RelationCategory.INTERNAL, kind=RelationKind.CREATES,
        source_file="auth/UserService.java",
    )
    assert len(creates) == 1
    assert target_name(graph, creates[0]) == "LoginRequest"
    # 升级后不再残留 CALLS
    calls = find(
        graph, category=RelationCategory.INTERNAL, kind=RelationKind.CALLS,
        source_file="auth/UserService.java",
    )
    assert calls == []


def test_new_imported_type_creates_cross_file() -> None:
    _, graph = build(
        {
            "com/devcollab/svc/UserService.java": (
                "package com.devcollab.svc;\n"
                "\n"
                "public class UserService {\n"
                "    public void run() {}\n"
                "}\n"
            ),
            "com/devcollab/web/Main.java": (
                "package com.devcollab.web;\n"
                "\n"
                "import com.devcollab.svc.UserService;\n"
                "\n"
                "public class Main {\n"
                "    public void start() {\n"
                "        new UserService();\n"
                "    }\n"
                "}\n"
            ),
        }
    )
    creates = find(
        graph, category=RelationCategory.INTERNAL, kind=RelationKind.CREATES,
        source_file="com/devcollab/web/Main.java",
    )
    assert len(creates) == 1
    assert target_name(graph, creates[0]) == "UserService"


def test_field_type_call_cross_file() -> None:
    _, graph = build(
        {
            "com/devcollab/svc/AuthService.java": (
                "package com.devcollab.svc;\n"
                "\n"
                "public class AuthService {\n"
                "    public String create(String name) { return null; }\n"
                "}\n"
            ),
            "com/devcollab/web/AuthController.java": (
                "package com.devcollab.web;\n"
                "\n"
                "import com.devcollab.svc.AuthService;\n"
                "\n"
                "public class AuthController {\n"
                "    private final AuthService service;\n"
                "\n"
                "    public AuthController(AuthService service) {\n"
                "        this.service = service;\n"
                "    }\n"
                "\n"
                "    public void login() {\n"
                "        service.create(\"x\");\n"
                "    }\n"
                "}\n"
            ),
        }
    )
    calls = find(
        graph, category=RelationCategory.INTERNAL, kind=RelationKind.CALLS,
        source_file="com/devcollab/web/AuthController.java",
    )
    assert len(calls) == 1
    assert target_name(graph, calls[0]) == "create"


def test_chained_call_dropped_not_emitted() -> None:
    _, graph = build(
        {
            "auth/Thing.java": (
                "package com.devcollab.auth;\n"
                "\n"
                "public class Thing {\n"
                "    public void run() {\n"
                "        this.inner.save();\n"
                "    }\n"
                "}\n"
            ),
        }
    )
    # 链式调用（field_access 基底）→ 不产出 CALLS
    calls = find(
        graph, category=RelationCategory.INTERNAL, kind=RelationKind.CALLS,
        source_file="auth/Thing.java",
    )
    assert calls == []
    # 但 this.inner 的字段读取被记录
    reads = find(
        graph, category=RelationCategory.UNRESOLVED, kind=RelationKind.FIELD_READS,
        source_file="auth/Thing.java", target_external="inner",
    )
    assert len(reads) == 1


def test_unresolved_call_unknown_base() -> None:
    _, graph = build(
        {
            "auth/Service.java": (
                "package com.devcollab.auth;\n"
                "\n"
                "public class Service {\n"
                "    public void run() {\n"
                "        mystery.call();\n"
                "    }\n"
                "}\n"
            ),
        }
    )
    unresolved = find(
        graph, category=RelationCategory.UNRESOLVED, kind=RelationKind.CALLS,
        source_file="auth/Service.java", target_external="mystery.call",
    )
    assert len(unresolved) == 1


def test_static_call_on_local_type() -> None:
    _, graph = build(
        {
            "auth/Helper.java": (
                "package com.devcollab.auth;\n"
                "\n"
                "public class Helper {\n"
                "    public static String build(String x) { return null; }\n"
                "\n"
                "    public void go() {\n"
                "        Helper.build(\"x\");\n"
                "    }\n"
                "}\n"
            ),
        }
    )
    calls = find(
        graph, category=RelationCategory.INTERNAL, kind=RelationKind.CALLS,
        source_file="auth/Helper.java",
    )
    assert len(calls) == 1
    assert target_name(graph, calls[0]) == "build"


# ── THROWS ─────────────────────────────────────────────────────────────


def test_throws_statement_and_clause() -> None:
    _, graph = build(
        {
            "auth/OrderService.java": (
                "package com.devcollab.auth;\n"
                "\n"
                "public class OrderService {\n"
                "    public static class BusinessException extends RuntimeException {}\n"
                "\n"
                "    public void create() throws java.io.IOException {\n"
                "        throw new BusinessException(\"bad\");\n"
                "    }\n"
                "}\n"
            ),
        }
    )
    # 自定义异常 → INTERNAL
    internal_throws = find(
        graph, category=RelationCategory.INTERNAL, kind=RelationKind.THROWS,
        source_file="auth/OrderService.java",
    )
    assert len(internal_throws) == 1
    assert target_name(graph, internal_throws[0]) == "BusinessException"
    # JDK 异常（含全限定引用与 java.lang 简单名）→ BOUNDARY
    boundary_throws = find(
        graph, category=RelationCategory.BOUNDARY, kind=RelationKind.THROWS,
        source_file="auth/OrderService.java",
    )
    assert {r.target_external for r in boundary_throws} == {"IOException"}
    # java.lang 异常基类 → BOUNDARY
    inherits = find(
        graph, category=RelationCategory.BOUNDARY, kind=RelationKind.INHERITS,
        source_file="auth/OrderService.java",
    )
    assert {r.target_external for r in inherits} == {"RuntimeException"}


# ── 参数/返回类型 ───────────────────────────────────────────────────────


def test_param_and_return_type_via_import() -> None:
    _, graph = build(
        {
            "com/devcollab/model/UserRequest.java": (
                "package com.devcollab.model;\n"
                "\n"
                "public record UserRequest(String name) {}\n"
            ),
            "com/devcollab/model/UserResponse.java": (
                "package com.devcollab.model;\n"
                "\n"
                "public record UserResponse(String id) {}\n"
            ),
            "com/devcollab/web/UserController.java": (
                "package com.devcollab.web;\n"
                "\n"
                "import com.devcollab.model.UserRequest;\n"
                "import com.devcollab.model.UserResponse;\n"
                "\n"
                "public class UserController {\n"
                "    public UserResponse convert(UserRequest req) { return null; }\n"
                "}\n"
            ),
        }
    )
    params = find(
        graph, category=RelationCategory.INTERNAL, kind=RelationKind.PARAMETER_TYPE,
        source_file="com/devcollab/web/UserController.java",
    )
    assert {target_name(graph, r) for r in params} == {"UserRequest"}
    returns = find(
        graph, category=RelationCategory.INTERNAL, kind=RelationKind.RETURN_TYPE,
        source_file="com/devcollab/web/UserController.java",
    )
    assert {target_name(graph, r) for r in returns} == {"UserResponse"}


def test_same_package_type_resolution_without_import() -> None:
    _, graph = build(
        {
            "com/devcollab/auth/UserRequest.java": (
                "package com.devcollab.auth;\n"
                "\n"
                "public record UserRequest(String name) {}\n"
            ),
            "com/devcollab/auth/UserService.java": (
                "package com.devcollab.auth;\n"
                "\n"
                "public class UserService {\n"
                "    public void save(UserRequest req) {}\n"
                "}\n"
            ),
        }
    )
    params = find(
        graph, category=RelationCategory.INTERNAL, kind=RelationKind.PARAMETER_TYPE,
        source_file="com/devcollab/auth/UserService.java",
    )
    assert len(params) == 1
    assert target_name(graph, params[0]) == "UserRequest"


def test_generic_param_takes_base_name() -> None:
    _, graph = build(
        {
            "com/devcollab/auth/Result.java": (
                "package com.devcollab.auth;\n"
                "\n"
                "public class Result<T> {}\n"
            ),
            "com/devcollab/auth/UserRequest.java": (
                "package com.devcollab.auth;\n"
                "\n"
                "public record UserRequest(String name) {}\n"
            ),
            "com/devcollab/auth/Box.java": (
                "package com.devcollab.auth;\n"
                "\n"
                "public class Box {\n"
                "    public void store(Result<UserRequest> result) {}\n"
                "}\n"
            ),
        }
    )
    params = find(
        graph, category=RelationCategory.INTERNAL, kind=RelationKind.PARAMETER_TYPE,
        source_file="com/devcollab/auth/Box.java",
    )
    assert len(params) == 1
    assert target_name(graph, params[0]) == "Result"


def test_unknown_param_type_unresolved() -> None:
    _, graph = build(
        {
            "auth/Box.java": (
                "package com.devcollab.auth;\n"
                "\n"
                "public class Box {\n"
                "    public void store(UnknownThing thing) {}\n"
                "}\n"
            ),
        }
    )
    unresolved = find(
        graph, category=RelationCategory.UNRESOLVED, kind=RelationKind.PARAMETER_TYPE,
        source_file="auth/Box.java", target_external="UnknownThing",
    )
    assert len(unresolved) == 1


# ── 继承/实现 ──────────────────────────────────────────────────────────


def test_inherits_and_implements() -> None:
    _, graph = build(
        {
            "com/devcollab/auth/BaseService.java": (
                "package com.devcollab.auth;\n"
                "\n"
                "public class BaseService {}\n"
            ),
            "com/devcollab/auth/IAuth.java": (
                "package com.devcollab.auth;\n"
                "\n"
                "public interface IAuth {\n"
                "    void check();\n"
                "}\n"
            ),
            "com/devcollab/auth/UserService.java": (
                "package com.devcollab.auth;\n"
                "\n"
                "public class UserService extends BaseService implements IAuth {\n"
                "    public void check() {}\n"
                "}\n"
            ),
        }
    )
    inherits = find(
        graph, category=RelationCategory.INTERNAL, kind=RelationKind.INHERITS,
        source_file="com/devcollab/auth/UserService.java",
    )
    assert {target_name(graph, r) for r in inherits} == {"BaseService"}
    implements = find(
        graph, category=RelationCategory.INTERNAL, kind=RelationKind.IMPLEMENTS,
        source_file="com/devcollab/auth/UserService.java",
    )
    assert {target_name(graph, r) for r in implements} == {"IAuth"}


def test_interface_extends_is_inherits() -> None:
    _, graph = build(
        {
            "com/devcollab/auth/IAuth.java": (
                "package com.devcollab.auth;\n"
                "\n"
                "public interface IAuth {}\n"
            ),
            "com/devcollab/auth/IAuthExtended.java": (
                "package com.devcollab.auth;\n"
                "\n"
                "public interface IAuthExtended extends IAuth {}\n"
            ),
        }
    )
    inherits = find(
        graph, category=RelationCategory.INTERNAL, kind=RelationKind.INHERITS,
        source_file="com/devcollab/auth/IAuthExtended.java",
    )
    assert {target_name(graph, r) for r in inherits} == {"IAuth"}
    implements = find(
        graph, category=RelationCategory.INTERNAL, kind=RelationKind.IMPLEMENTS,
        source_file="com/devcollab/auth/IAuthExtended.java",
    )
    assert implements == []


# ── 字段读写 ───────────────────────────────────────────────────────────


def test_field_reads_and_writes_emitted() -> None:
    _, graph = build(
        {
            "auth/Counter.java": (
                "package com.devcollab.auth;\n"
                "\n"
                "public class Counter {\n"
                "    private int value;\n"
                "\n"
                "    public void bump() {\n"
                "        this.value = this.value + 1;\n"
                "    }\n"
                "}\n"
            ),
        }
    )
    writes = find(
        graph, category=RelationCategory.UNRESOLVED, kind=RelationKind.FIELD_WRITES,
        source_file="auth/Counter.java", target_external="value",
    )
    assert len(writes) == 1
    reads = find(
        graph, category=RelationCategory.UNRESOLVED, kind=RelationKind.FIELD_READS,
        source_file="auth/Counter.java", target_external="value",
    )
    assert len(reads) == 1
