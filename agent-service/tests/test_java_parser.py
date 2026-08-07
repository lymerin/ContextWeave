"""Java 解析器（S3）：原子映射、字段语义、Spring 路由、鲁棒性。"""

from __future__ import annotations

from uuid import UUID

from app.schemas.ast_atom import SymbolKind
from app.schemas.source_file import SourceFileBatch, SourceFileRef
from app.source_analysis.code_ast_atom import parse_batch
from app.source_analysis.parsers.java_parser import JavaParser

REPO_ID = UUID("aaaaaaaa-1111-1111-1111-111111111111")

PARSER = JavaParser()


def _parse(source: str):
    return PARSER.parse_file(
        repository_id=str(REPO_ID),
        revision="rev1",
        file_path="src/Example.java",
        source=source,
    )


def test_class_interface_enum_record_kinds() -> None:
    source = """
package com.demo;

public class UserService {}
public interface AuthGateway {}
public enum Status { ACTIVE, DISABLED }
public record Point(int x, int y) {}
""".strip()
    _, symbols = _parse(source)
    by_name = {s.name: s for s in symbols}
    assert set(by_name) == {"UserService", "AuthGateway", "Status", "Point"}
    assert by_name["UserService"].kind == SymbolKind.CLASS
    assert by_name["AuthGateway"].kind == SymbolKind.INTERFACE
    assert by_name["Status"].kind == SymbolKind.ENUM
    assert by_name["Point"].kind == SymbolKind.RECORD
    # 行号 1-based（strip 后：1=package, 2=空, 3=类声明）
    assert by_name["UserService"].start_line == 3
    assert by_name["UserService"].end_line == 3
    # 无 body 类型：body_start 与 Python 空体语义对齐
    assert by_name["UserService"].body_start_line == 3


def test_nested_record_qualified_name() -> None:
    source = """
package com.demo;

public class AuthController {
    public record LoginRequest(String username, String password) {}

    public void login() {}
}
""".strip()
    _, symbols = _parse(source)
    login_request = next(s for s in symbols if s.name == "LoginRequest")
    assert login_request.kind == SymbolKind.RECORD
    assert login_request.qualified_name == "AuthController.LoginRequest"
    assert login_request.parent_qualified == "AuthController"
    login = next(s for s in symbols if s.name == "login")
    assert login.parent_qualified == "AuthController"


def test_constructor_and_fields_produce_no_atoms() -> None:
    source = """
public class AuthController {
    private final AuthService service;
    public static final String VERSION = "1.0";

    public AuthController(AuthService service) {
        this.service = service;
    }

    public void login() {}
}
""".strip()
    _, symbols = _parse(source)
    assert [s.name for s in symbols] == ["AuthController", "login"]


def test_signature_includes_modifiers_annotations_and_generics() -> None:
    source = """
public class Api<T> {
    @PostMapping("/login")
    public synchronized Map<String, T> fetch(@RequestBody LoginRequest req) {
        return null;
    }
}
""".strip()
    _, symbols = _parse(source)
    fetch = next(s for s in symbols if s.name == "fetch")
    assert fetch.signature == (
        '@PostMapping("/login")\n'
        "    public synchronized Map<String, T> fetch(@RequestBody LoginRequest req)"
    )
    api = next(s for s in symbols if s.name == "Api")
    assert api.signature == "public class Api<T>"


def test_spring_routes_with_class_prefix_join() -> None:
    source = """
@RestController
@RequestMapping("/api/v1")
public class AuthController {

    @GetMapping("/users")
    public String list() { return null; }

    @PostMapping(value = "/login", produces = "application/json")
    public String login() { return null; }

    @RequestMapping(method = RequestMethod.PUT, path = "/reset")
    public String reset() { return null; }

    @PatchMapping("/{id}")
    public String patch() { return null; }
}
""".strip()
    _, symbols = _parse(source)
    routes = {
        s.name: (s.http_method, s.http_path)
        for s in symbols
        if s.kind == SymbolKind.METHOD
    }
    assert routes["list"] == ("GET", "/api/v1/users")
    assert routes["login"] == ("POST", "/api/v1/login")
    assert routes["reset"] == ("PUT", "/api/v1/reset")
    assert routes["patch"] == ("PATCH", "/api/v1/{id}")


def test_controller_without_mapping_has_no_route() -> None:
    source = """
@RestController
public class HealthController {
    @PostMapping("/ping")
    public String ping() { return null; }

    public String noMapping() { return null; }
}
""".strip()
    _, symbols = _parse(source)
    no_mapping = next(s for s in symbols if s.name == "noMapping")
    assert no_mapping.http_method is None
    assert no_mapping.http_path is None
    ping = next(s for s in symbols if s.name == "ping")
    assert ping.http_path == "/ping"


def test_javadoc_extracted_but_line_comment_not_attached() -> None:
    source = """
public class C {
    /** 用户登录 */
    public void login() {}

    // 普通注释
    public void helper() {}
}
""".strip()
    _, symbols = _parse(source)
    login = next(s for s in symbols if s.name == "login")
    assert login.docstring == "/** 用户登录 */"
    helper = next(s for s in symbols if s.name == "helper")
    assert helper.docstring is None


def test_access_level_four_states() -> None:
    source = """
public class C {
    public void a() {}
    private void b() {}
    protected void c() {}
    void d() {}
    static void e() {}
}
""".strip()
    _, symbols = _parse(source)
    levels = {s.name: s.access_level for s in symbols if s.kind == SymbolKind.METHOD}
    assert levels == {
        "a": "public",
        "b": "private",
        "c": "protected",
        "d": "package",
        "e": "package",
    }
    c = next(s for s in symbols if s.name == "C")
    assert c.access_level == "public"


def test_decorator_names_from_annotations() -> None:
    source = """
public class C {
    @Override
    @Deprecated
    public void run() {}
}
""".strip()
    _, symbols = _parse(source)
    run = next(s for s in symbols if s.name == "run")
    assert run.decorator_names == ("Deprecated", "Override")


def test_parse_batch_java_symbol_key_and_language() -> None:
    batch = SourceFileBatch(
        repository_id=REPO_ID,
        revision="rev1",
        files=[
            SourceFileRef(file_path="src/Example.java", language="Java", size_bytes=100),
            SourceFileRef(file_path="src/NoLang.java", language=None, size_bytes=100),
        ],
    )
    sources = {
        "src/Example.java": "public class Example { public void run() {} }",
        "src/NoLang.java": "public class NoLang { public void run() {} }",
    }
    catalog = parse_batch(batch, lambda path: sources.get(path))
    by_file = {s.symbol_key.split(":")[1]: s for s in catalog.symbols}
    example = by_file["src/Example.java"]
    assert example.symbol_key == "JAVA:src/Example.java:Example:CLASS"
    # language 缺失时从扩展名回退推断
    no_lang = by_file["src/NoLang.java"]
    assert no_lang.symbol_key.startswith("JAVA:")
    module_lang = {m.file_path: m.language for m in catalog.modules}
    assert module_lang["src/Example.java"] == "Java"
    assert module_lang["src/NoLang.java"] == "Java"  # 解析器按推断语言产出


def test_syntax_error_yields_empty_module_atom() -> None:
    batch = SourceFileBatch(
        repository_id=REPO_ID,
        revision="rev1",
        files=[SourceFileRef(file_path="src/Broken.java", language="Java", size_bytes=100)],
    )
    catalog = parse_batch(batch, lambda path: "public class Broken { void f( {")
    assert catalog.modules[0].file_path == "src/Broken.java"
    assert catalog.symbols == ()
    assert catalog.error_count == 1


def test_abstract_method_signature_without_body() -> None:
    source = """
public interface Repo {
    void save(String name);

    default int count() { return 0; }
}
""".strip()
    _, symbols = _parse(source)
    save = next(s for s in symbols if s.name == "save")
    assert save.signature == "void save(String name);"
    # 无体方法：body_start 与 Python 空体语义对齐（start == end → start + 1）
    assert save.body_start_line == save.end_line + 1
