"""Java 语言解析器——LanguageParser 协议的 Java 实现（tree-sitter-java）。

原子映射（Phase 1 边界）:
  - class_declaration→CLASS、interface_declaration→INTERFACE、
    enum_declaration→ENUM、record_declaration→RECORD、method_declaration→METHOD
  - 构造器 / 注解类型 / 字段 / 枚举常量 → 不产出原子
  - 嵌套类型压 class_stack 形成 qualified_name（如 AuthController.LoginRequest）

字段语义（与 Python 路径对齐）:
  - start/end 行号 = start_point.row+1 / end_point.row+1（注解在 modifiers 内，
    行号含注解行，与 Python 装饰器语义一致）
  - signature = source[start_byte : body.start_byte]（含修饰符/注解/泛型，天然完整）
  - decorator_names = modifiers 下注解简单名（不带 @）
  - docstring = 紧邻前置 Javadoc（/** 前缀 + 中间仅空白）
  - access_level = modifiers 的 public/private/protected（皆无 → "package"）
  - Spring 路由注解 → http_method / http_path（类级 @RequestMapping 前缀拼接）
"""

from __future__ import annotations

from pathlib import PurePosixPath
from typing import Any

import tree_sitter_java
from tree_sitter import Language, Node, Parser

from app.schemas.ast_atom import CLASS_LIKE_KINDS, ModuleAtom, SymbolAtom, SymbolKind
from app.schemas.repository_graph import Relation, RelationKind
from app.source_analysis.import_resolver import FileImportScope
from app.source_analysis.parsers import hash_id, symbol_key

_LANGUAGE = Language(tree_sitter_java.language())

# 类型容器声明 → SymbolKind（嵌套类型递归下钻）
_TYPE_KINDS: dict[str, str] = {
    "class_declaration": SymbolKind.CLASS,
    "interface_declaration": SymbolKind.INTERFACE,
    "enum_declaration": SymbolKind.ENUM,
    "record_declaration": SymbolKind.RECORD,
}

# 类型体内可产出原子的成员
_MEMBER_KINDS: dict[str, str] = {
    "method_declaration": SymbolKind.METHOD,
    **_TYPE_KINDS,
}

# 类型体容器节点（class_body / enum_body / interface_body / record_body 通用）
_BODY_KINDS = {"class_body", "enum_body", "interface_body"}

# Spring 方法级映射注解 → HTTP 方法
_SPRING_METHOD_ANNOTATIONS: dict[str, str] = {
    "GetMapping": "GET",
    "PostMapping": "POST",
    "PutMapping": "PUT",
    "DeleteMapping": "DELETE",
    "PatchMapping": "PATCH",
}

# 类级 controller 注解
_SPRING_CONTROLLER_ANNOTATIONS = {"RestController", "Controller"}


class JavaParser:
    language = "java"

    # ── 解析 ──────────────────────────────────────────────────────────

    def parse_file(
        self,
        *,
        repository_id: str,
        revision: str,
        file_path: str,
        source: str,
    ) -> tuple[ModuleAtom, tuple[SymbolAtom, ...]]:
        """解析单个 Java 文件，返回 ModuleAtom + SymbolAtom[]。"""
        normalized = PurePosixPath(file_path.replace("\\", "/")).as_posix()
        lines = source.splitlines()
        line_count = max(1, len(lines))

        tree = self.parse_source(source)
        # tree-sitter 做错误恢复不会返回 None：语法不完整（ERROR 节点）
        # 视为解析失败 → 空 ModuleAtom（与 Python 路径语义对齐）。
        if tree is None or tree.root_node is None or tree.root_node.has_error:
            return (
                ModuleAtom(
                    atom_id="", file_path=normalized, language="Java",
                    start_line=1, end_line=line_count,
                ),
                (),
            )

        source_bytes = source.encode("utf-8")
        comments = _collect_comments(tree.root_node)

        symbols: list[SymbolAtom] = []
        visitor = _JavaVisitor(
            revision=revision,
            file=normalized,
            source_bytes=source_bytes,
            comments=comments,
            symbols_out=symbols,
        )
        for child in tree.root_node.named_children:
            visitor.visit(child)

        module = ModuleAtom(
            atom_id=hash_id("mod", revision, normalized),
            file_path=normalized,
            language="Java",
            start_line=1,
            end_line=line_count,
        )
        return module, tuple(symbols)

    def parse_source(self, source: str) -> Any:
        """源码 → 解析树；解析失败返回 None（不抛异常）。

        注意：每次调用新建 Parser 实例，绝不共享。共享 Parser 时 tree-sitter
        增量解析复用旧树节点内存，跨调用保存的 Node 引用（_collect_comments
        的注释节点列表）会悬垂 → 访问 .text 触发 SIGSEGV 崩溃循环。
        每文件独立 Parser 成本微秒级，同时消除协程并发调用风险。
        """
        try:
            return Parser(_LANGUAGE).parse(source.encode("utf-8"))
        except Exception:  # tree-sitter 对任意字节序列都不应抛错，防御性兜底
            return None

    # ── 关系收集 ─────────────────────────────────────────────────────

    def extract_relations(
        self, sym: SymbolAtom, tree: Any, file: str
    ) -> list[Relation]:
        """遍历符号对应节点，收集调用/类型引用/字段读写/继承等关系。"""
        return _collect_relations(sym, tree, file)

    # ── import 作用域 ─────────────────────────────────────────────────

    def build_import_scope(
        self, file: str, tree: Any, file_paths: frozenset[str]
    ) -> FileImportScope:
        return _build_import_scope(file, tree)

    # ── 外部名判定 ────────────────────────────────────────────────────

    def is_external_name(self, target: str) -> bool:
        """BOUNDARY 判定：JDK / 框架前缀，或 java.lang 常用简单名 → 外部。"""
        full = target.lower()
        if any(full.startswith(p) for p in _JAVA_EXTERNAL_PREFIXES):
            return True
        return target in _JAVA_LANG_SIMPLE_EXTERNAL

    def field_types(self, tree: Any) -> dict[str, str]:
        """字段名 → 声明类型简单名（跨类收集；同名异型 → 剔除，宁缺勿错）。"""
        return _java_field_types(tree)


PARSER = JavaParser()


# ── 遍历 ────────────────────────────────────────────────────────────────────


class _JavaVisitor:
    """递归遍历类型体，产出 SymbolAtom。

    class_stack 保存外层类型的 qualified_name；构造器/字段/枚举常量跳过。
    """

    def __init__(
        self,
        *,
        revision: str,
        file: str,
        source_bytes: bytes,
        comments: list[Node],
        symbols_out: list[SymbolAtom],
    ) -> None:
        self._rev = revision
        self._file = file
        self._source = source_bytes
        self._comments = comments
        self._symbols = symbols_out
        self._class_stack: list[str] = []
        # 与 class_stack 同步的类级 @RequestMapping 前缀栈（无前缀 → None）
        self._prefix_stack: list[str | None] = []

    def visit(self, node: Node) -> None:
        if node.type in _TYPE_KINDS:
            self._visit_type(node)
        elif node.type == "method_declaration":
            self._visit_method(node)

    def _visit_type(self, node: Node) -> None:
        kind = _TYPE_KINDS[node.type]
        name = _node_text(node, "identifier")
        if name is None:
            return
        qualified = ".".join([*self._class_stack, name])
        parent = self._class_stack[-1] if self._class_stack else None

        modifiers = _modifiers_node(node)
        body = next(
            (c for c in node.named_children if c.type in _BODY_KINDS), None
        )

        start = node.start_point.row + 1
        end = node.end_point.row + 1
        body_start, body_end = _body_lines(body, start, end)

        atom = SymbolAtom(
            atom_id=hash_id("sym", self._rev, self._file, kind, qualified),
            symbol_key=symbol_key("JAVA", self._file, qualified, kind),
            kind=kind,
            name=name,
            qualified_name=qualified,
            signature=_declaration_signature(self._source, node, body),
            start_line=start,
            end_line=end,
            body_start_line=body_start,
            body_end_line=body_end,
            decorator_names=_annotation_names(modifiers),
            docstring=_javadoc_before(self._source, self._comments, node),
            parent_qualified=parent,
            is_async=False,
            is_pydantic=False,
            access_level=_access_level(modifiers),
        )
        self._symbols.append(atom)

        # 递归下钻嵌套类型（类中类/接口中 record 等）
        if body is not None:
            prefix = None
            if modifiers is not None:
                for child in modifiers.named_children:
                    if (
                        child.type in _ANNOTATION_TYPES
                        and _node_text(child, "identifier") == "RequestMapping"
                    ):
                        value = _annotation_string_value(child)
                        if value:
                            prefix = "/" + value.strip("/")
            self._class_stack.append(qualified)
            self._prefix_stack.append(prefix)
            for member in body.named_children:
                if member.type in _MEMBER_KINDS:
                    self.visit(member)
            self._prefix_stack.pop()
            self._class_stack.pop()

    def _visit_method(self, node: Node) -> None:
        name = _node_text(node, "identifier")
        if name is None:
            return
        qualified = ".".join([*self._class_stack, name])
        parent = self._class_stack[-1] if self._class_stack else None

        modifiers = _modifiers_node(node)
        block = next(
            (c for c in node.named_children if c.type == "block"), None
        )

        start = node.start_point.row + 1
        end = node.end_point.row + 1
        body_start, body_end = _body_lines(block, start, end)

        class_prefix = self._class_prefix()
        route = _detect_route(modifiers, class_prefix)

        atom = SymbolAtom(
            atom_id=hash_id("sym", self._rev, self._file, SymbolKind.METHOD, qualified),
            symbol_key=symbol_key("JAVA", self._file, qualified, SymbolKind.METHOD),
            kind=SymbolKind.METHOD,
            name=name,
            qualified_name=qualified,
            signature=_declaration_signature(self._source, node, block),
            start_line=start,
            end_line=end,
            body_start_line=body_start,
            body_end_line=body_end,
            decorator_names=_annotation_names(modifiers),
            docstring=_javadoc_before(self._source, self._comments, node),
            parent_qualified=parent,
            is_async=False,
            is_pydantic=False,
            http_method=route[0] if route else None,
            http_path=route[1] if route else None,
            http_response=None,
            access_level=_access_level(modifiers),
        )
        self._symbols.append(atom)

    def _class_prefix(self) -> str | None:
        """当前最内层类的类级 @RequestMapping 前缀（无 → None）。"""
        return self._prefix_stack[-1] if self._prefix_stack else None


# ── 字段提取 ────────────────────────────────────────────────────────────────


def _modifiers_node(node: Node) -> Node | None:
    return next(
        (c for c in node.named_children if c.type == "modifiers"), None
    )


def _node_text(node: Node, child_type: str) -> str | None:
    child = next(
        (c for c in node.named_children if c.type == child_type), None
    )
    if child is None:
        return None
    return child.text.decode("utf-8", errors="replace")


def _body_lines(
    body: Node | None, start: int, end: int
) -> tuple[int, int]:
    """body_start/body_end：无体（抽象方法/接口方法）与 Python 空体语义对齐。"""
    if body is not None:
        return body.start_point.row + 1, body.end_point.row + 1
    return (end if end > start else start + 1, end)


def _declaration_signature(
    source: bytes, node: Node, body: Node | None
) -> str:
    """签名 = 声明起始到 body 前的完整文本（含修饰符/注解/泛型）。"""
    if body is not None:
        return source[node.start_byte : body.start_byte].decode(
            "utf-8", errors="replace"
        ).strip()
    return source[node.start_byte : node.end_byte].decode(
        "utf-8", errors="replace"
    ).strip()


_ANNOTATION_TYPES = ("annotation", "marker_annotation")


def _annotation_names(modifiers: Node | None) -> tuple[str, ...]:
    """modifiers 下注解简单名（不带 @），如 PostMapping / RequestMapping。"""
    if modifiers is None:
        return ()
    names: set[str] = set()
    for child in modifiers.named_children:
        if child.type not in _ANNOTATION_TYPES:
            continue
        name = _node_text(child, "identifier")
        if name:
            names.add(name)
    return tuple(sorted(names))


def _access_level(modifiers: Node | None) -> str:
    """四态：public / private / protected / package（无声明）。"""
    if modifiers is None:
        return "package"
    for child in modifiers.children:  # public/private/protected 是 unnamed 节点
        if child.type in ("public", "private", "protected"):
            return child.type
    return "package"


def _collect_comments(root: Node) -> list[Node]:
    """收集全部注释节点（block_comment / line_comment）。"""
    result: list[Node] = []
    stack = [root]
    while stack:
        node = stack.pop()
        if node.type in ("block_comment", "line_comment"):
            result.append(node)
        stack.extend(node.children)
    return result


def _javadoc_before(
    source: bytes, comments: list[Node], decl: Node
) -> str | None:
    """紧邻前置 Javadoc：/** 前缀 + decl 前最近注释且中间仅空白。"""
    best: Node | None = None
    for comment in comments:
        if comment.type != "block_comment" or comment.end_byte > decl.start_byte:
            continue
        if not source[comment.end_byte : decl.start_byte].isspace():
            continue
        if best is None or comment.end_byte > best.end_byte:
            best = comment
    if best is None or not best.text.decode("utf-8", errors="replace").startswith("/**"):
        return None
    return best.text.decode("utf-8", errors="replace")


# ── Spring 路由 ──────────────────────────────────────────────────────────────


def _detect_route(
    modifiers: Node | None, class_prefix: str | None
) -> tuple[str, str] | None:
    """从方法注解提取 (http_method, http_path)。

    映射注解（GetMapping 等）→ 固定 method + value/path 字符串；
    @RequestMapping(method=...) → method 属性末段 + value/path。
    路径与类级前缀拼接；两者缺一不可（入口判定需要 method + path）。
    """
    if modifiers is None:
        return None
    for child in modifiers.named_children:
        if child.type not in _ANNOTATION_TYPES:
            continue
        name = _node_text(child, "identifier")
        if name is None:
            continue
        if name in _SPRING_METHOD_ANNOTATIONS:
            path = _annotation_string_value(child)
            if path is None:
                continue
            return _SPRING_METHOD_ANNOTATIONS[name], _join_path(class_prefix, path)
        if name == "RequestMapping":
            method = _request_mapping_method(child)
            path = _annotation_string_value(child)
            if method is None or path is None:
                continue
            return method, _join_path(class_prefix, path)
    return None


def _annotation_string_value(annotation: Node) -> str | None:
    """注解参数列表里的字符串字面量：裸值优先，其次 path=/value= 命名参数。"""
    args = next(
        (c for c in annotation.named_children if c.type == "annotation_argument_list"),
        None,
    )
    if args is None:
        return None
    for child in args.named_children:
        if child.type == "string_literal":
            return _string_value(child)
    for key in ("path", "value"):
        for child in args.named_children:
            if child.type != "element_value_pair":
                continue
            name = _node_text(child, "identifier")
            if name != key:
                continue
            lit = next(
                (c for c in child.named_children if c.type == "string_literal"),
                None,
            )
            if lit is not None:
                return _string_value(lit)
    return None


def _request_mapping_method(annotation: Node) -> str | None:
    """@RequestMapping(method = RequestMethod.POST) → "POST"。"""
    args = next(
        (c for c in annotation.named_children if c.type == "annotation_argument_list"),
        None,
    )
    if args is None:
        return None
    for child in args.named_children:
        if child.type != "element_value_pair":
            continue
        if _node_text(child, "identifier") != "method":
            continue
        value = next(
            (c for c in child.named_children if c.type != "identifier"),
            None,
        )
        if value is None:
            continue
        text = value.text.decode("utf-8", errors="replace")
        segment = text.rsplit(".", 1)[-1].upper()
        if segment in {"GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"}:
            return segment
    return None


def _string_value(literal: Node) -> str:
    """string_literal → 去引号文本（tree-sitter-java 字符串内部含转义原样保留）。"""
    text = literal.text.decode("utf-8", errors="replace")
    if len(text) >= 2 and text[0] == '"' and text[-1] == '"':
        return text[1:-1]
    return text


def _join_path(prefix: str | None, path: str) -> str:
    """类级前缀 + 方法路径拼接，规范化斜杠（"/api" + "/login" → "/api/login"）。"""
    if not prefix:
        return "/" + path.lstrip("/") if not path.startswith("/") else path
    left = "/" + prefix.strip("/") if prefix else ""
    right = path if path.startswith("/") else "/" + path
    joined = (left + right).replace("//", "/")
    return joined.rstrip("/") if len(joined) > 1 else "/"


# ╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌
# S4：import 作用域、外部名判定、关系收集


_JAVA_EXTERNAL_PREFIXES: tuple[str, ...] = (
    "java.", "javax.", "jakarta.",
    "org.springframework.", "org.slf4j.", "lombok.",
    "com.fasterxml.", "org.junit.", "org.mockito.",
)

# java.lang 常用名：简单名不带包前缀，无法从名字看出包——显式列入判外部
_JAVA_LANG_SIMPLE_EXTERNAL: frozenset[str] = frozenset({
    "Exception", "RuntimeException", "Throwable", "Error",
    "IllegalArgumentException", "IllegalStateException", "NullPointerException",
    "UnsupportedOperationException", "NoSuchElementException",
    "InterruptedException", "ClassNotFoundException", "IllegalAccessException",
    "IOException", "FileNotFoundException", "ParseException", "SQLException",
    "Serializable", "Cloneable", "Comparable", "Iterable",
    "AutoCloseable", "Closeable", "Thread", "Runnable", "System", "Math",
})

# 原始类型 + java.lang 核心 + var + 常用集合——类型注解不产生关系（纯噪声）
_TYPE_INTRINSICS: frozenset[str] = frozenset({
    "byte", "short", "int", "long", "float", "double", "boolean", "char",
    "void", "var", "String", "Object", "Class", "Enum", "Record",
    "Byte", "Short", "Integer", "Long", "Float", "Double", "Boolean",
    "Character", "Void",
    "List", "Map", "Set", "Collection", "Optional", "Iterable",
})


def _build_import_scope(file: str, tree: Any) -> FileImportScope:
    """Java 文件 import 事实：包名、类型导入、静态导入、本文件类型名。"""
    package = ""
    symbol_imports: list[tuple[str, str]] = []
    star_imports: list[str] = []
    local_types: list[str] = []
    for child in tree.root_node.named_children:
        if child.type == "package_declaration":
            text = child.text.decode("utf-8", errors="replace")
            package = text.removeprefix("package").removesuffix(";").strip()
        elif child.type == "import_declaration":
            _collect_java_import(child, symbol_imports, star_imports)
        elif child.type in _TYPE_KINDS:
            name = _node_text(child, "identifier")
            if name:
                local_types.append(name)
    return FileImportScope(
        file_path=file,
        symbol_imports=tuple(sorted(symbol_imports)),
        star_imports=tuple(sorted(star_imports)),
        package=package,
        local_types=tuple(sorted(set(local_types))),
    )


def _collect_java_import(
    node: Node,
    symbol_imports: list[tuple[str, str]],
    star_imports: list[str],
) -> None:
    """import 文本 → (简单名, 全限定名)；静态导入 (成员名, 类全限定名)。"""
    text = node.text.decode("utf-8", errors="replace")
    body = text.removeprefix("import").removesuffix(";").strip()
    if body.startswith("static "):
        fq = body.removeprefix("static ").strip()
        member = fq.rsplit(".", 1)[-1]
        symbol_imports.append((member, fq.rsplit(".", 1)[0]))
    elif body.endswith(".*"):
        star_imports.append(body.removesuffix(".*"))
    else:
        symbol_imports.append((body.rsplit(".", 1)[-1], body))


# ── 关系收集 ────────────────────────────────────────────────────────────────


def _iter_all(node: Node):
    """DFS 遍历全部节点（含 unnamed 叶节点）。"""
    stack = [node]
    while stack:
        current = stack.pop()
        yield current
        stack.extend(current.children)


def _collect_relations(
    sym: SymbolAtom, tree: Any, file: str
) -> list[Relation]:
    """遍历符号对应节点，收集调用、类型引用等关系。

    返回的 Relation 中 target_external 是待解析临时键（与 Python 路径一致）：
      - CALLS/CREATES: 点路径（"service.create" / "helper"）；
      - PARAMETER_TYPE/RETURN_TYPE/THROWS/INHERITS/IMPLEMENTS: 简单名。
    """
    result: list[Relation] = []
    node = _find_declaration(tree, sym)
    if node is None:
        return result

    if sym.kind in CLASS_LIKE_KINDS:
        # 继承/实现（类型原子）
        for target, kind in _inheritance_names(node):
            result.append(
                _raw_rel(sym.atom_id, kind, target, file, node.start_point.row + 1)
            )
        # 类型原子只收类体直接语句（字段初始化等），方法体归方法原子自己收集
        body = next(
            (c for c in node.named_children if c.type in _BODY_KINDS), None
        )
        if body is None:
            return result
        walk_nodes: list[Node] = [
            c for c in body.named_children
            if c.type not in _MEMBER_KINDS and c.type != "enum_body_declarations"
        ]
    else:
        walk_nodes = [node]
        _collect_method_signature_relations(sym, node, file, result)

    for walk_target in walk_nodes:
        for child in _iter_all(walk_target):
            if child.type == "method_invocation":
                obj = child.child_by_field_name("object")
                if obj is None:
                    base = None
                elif obj.type == "identifier":
                    base = obj.text.decode("utf-8", errors="replace")
                elif obj.type == "this":
                    base = "this"
                else:
                    continue  # 链式/动态表达式 → 宁缺勿错，不解析
                target = _invocation_name(child)
                if not target:
                    continue
                dotted = f"{base}.{target}" if base else target
                result.append(
                    _raw_rel(sym.atom_id, RelationKind.CALLS, dotted, file,
                             child.start_point.row + 1)
                )
            elif child.type == "object_creation_expression":
                type_name = _creation_type_name(child)
                if type_name:
                    result.append(
                        _raw_rel(sym.atom_id, RelationKind.CALLS, type_name, file,
                                 child.start_point.row + 1)
                    )
            elif child.type == "throw_statement":
                exc = _thrown_exception_name(child)
                if exc:
                    result.append(
                        _raw_rel(sym.atom_id, RelationKind.THROWS, exc, file,
                                 child.start_point.row + 1)
                    )
            elif child.type == "assignment_expression":
                left = child.child_by_field_name("left")
                if left is not None and left.type == "field_access":
                    field = _this_field_name(left)
                    if field:
                        result.append(
                            _raw_rel(sym.atom_id, RelationKind.FIELD_WRITES,
                                     field, file, child.start_point.row + 1)
                        )
            elif child.type == "field_access":
                parent = child.parent
                if parent is not None and parent.type == "assignment_expression":
                    if parent.child_by_field_name("left") == child:
                        continue  # 赋值左侧已记 FIELD_WRITES，不再记读取
                field = _this_field_name(child)
                if field:
                    result.append(
                        _raw_rel(sym.atom_id, RelationKind.FIELD_READS,
                                 field, file, child.start_point.row + 1)
                    )

    return result


def _find_declaration(tree: Any, sym: SymbolAtom) -> Node | None:
    """按 (声明类型, start_line, 名字) 定位符号节点。"""
    for node in _iter_all(tree.root_node):
        if node.type not in _MEMBER_KINDS:
            continue
        if node.start_point.row + 1 != sym.start_line:
            continue
        if _node_text(node, "identifier") == sym.name:
            return node
    return None


def _collect_method_signature_relations(
    sym: SymbolAtom, node: Node, file: str, result: list[Relation]
) -> None:
    """方法签名关系：参数类型 / 返回类型 / throws 子句。"""
    parameters = node.child_by_field_name("parameters")
    if parameters is not None:
        for param in parameters.named_children:
            if param.type != "formal_parameter":
                continue
            type_node = param.child_by_field_name("type")
            type_name = _type_base_name(type_node)
            if not type_name or type_name in _TYPE_INTRINSICS:
                continue
            result.append(
                _raw_rel(sym.atom_id, RelationKind.PARAMETER_TYPE, type_name,
                         file, param.start_point.row + 1)
            )

    return_type = _method_return_type_node(node)
    type_name = _type_base_name(return_type)
    if type_name and type_name not in _TYPE_INTRINSICS:
        result.append(
            _raw_rel(sym.atom_id, RelationKind.RETURN_TYPE, type_name,
                     file, node.start_point.row + 1)
        )

    for throws in node.named_children:
        if throws.type != "throws":
            continue
        for type_node in throws.named_children:
            type_name = _type_base_name(type_node)
            if not type_name:
                continue
            result.append(
                _raw_rel(sym.atom_id, RelationKind.THROWS, type_name,
                         file, throws.start_point.row + 1)
            )


def _method_return_type_node(node: Node) -> Node | None:
    """method_declaration 的返回类型节点（type_identifier/generic_type/...）。"""
    for child in node.named_children:
        if child.type in (
            "type_identifier", "generic_type", "scoped_type_identifier",
            "array_type", "integral_type", "floating_point_type",
            "boolean_type", "void_type", "union_type", "intersection_type",
        ):
            return child
    return None


_TYPE_NODE_TYPES = frozenset({
    "type_identifier", "generic_type", "scoped_type_identifier", "array_type",
    "integral_type", "floating_point_type", "boolean_type", "void_type",
    "union_type", "intersection_type",
})


def _type_base_name(node: Node | None) -> str | None:
    """类型节点 → 简单名（generic 取基名，全限定取末段，包裹层下钻）。"""
    if node is None:
        return None
    if node.type == "type_identifier":
        return node.text.decode("utf-8", errors="replace")
    if node.type == "scoped_type_identifier":
        return node.text.decode("utf-8", errors="replace").rsplit(".", 1)[-1]
    if node.type == "generic_type":
        inner = node.child_by_field_name("type")
        if inner is not None:
            return _type_base_name(inner)
        inner = next(
            (c for c in node.named_children
             if c.type in ("type_identifier", "scoped_type_identifier")),
            None,
        )
        return _type_base_name(inner)
    if node.type == "array_type":
        element = node.child_by_field_name("element")
        return _type_base_name(element)
    if node.type in ("integral_type", "floating_point_type",
                     "boolean_type", "void_type"):
        return node.text.decode("utf-8", errors="replace")
    # 包裹层（superclass/super_interfaces/extends_interfaces 等）：下钻找类型节点
    for child in node.named_children:
        if child.type in _TYPE_NODE_TYPES:
            return _type_base_name(child)
    return None


def _inheritance_names(node: Node) -> list[tuple[str, str]]:
    """[(类型名, INHERITS|IMPLEMENTS)]：superclass → INHERITS；类 implements → IMPLEMENTS；
    interface extends → INHERITS（接口继承语义）。"""
    result: list[tuple[str, str]] = []
    superclass = node.child_by_field_name("superclass")
    if superclass is not None:
        name = _type_base_name(superclass)
        if name:
            result.append((name, RelationKind.INHERITS))
    # interface 的 extends 子句无字段名（None | extends_interfaces）→ 按类型兜底
    for field, holder_type in (
        ("interfaces", "super_interfaces"),
        ("extends_interfaces", "extends_interfaces"),
    ):
        holder = node.child_by_field_name(field)
        if holder is None:
            holder = next(
                (c for c in node.named_children if c.type == holder_type), None
            )
        if holder is None:
            continue
        kind = (
            RelationKind.INHERITS
            if node.type == "interface_declaration"
            else RelationKind.IMPLEMENTS
        )
        # 接口列表有 type_list 包裹层（super_interfaces → type_list → type_identifier）
        candidates: list[Node] = []
        for type_node in holder.named_children:
            if type_node.type == "type_list":
                candidates.extend(type_node.named_children)
            else:
                candidates.append(type_node)
        for type_node in candidates:
            if type_node.type in ("type_identifier", "generic_type",
                                  "scoped_type_identifier"):
                name = _type_base_name(type_node)
                if name:
                    result.append((name, kind))
    return result


def _invocation_name(node: Node) -> str | None:
    name = node.child_by_field_name("name")
    if name is None:
        return None
    return name.text.decode("utf-8", errors="replace")


def _creation_type_name(node: Node) -> str | None:
    """object_creation_expression → 类型简单名（new User() → "User"）。"""
    type_node = node.child_by_field_name("type")
    return _type_base_name(type_node)


def _thrown_exception_name(node: Node) -> str | None:
    """throw_statement → 异常类型名（throw new X() / throw x）。"""
    creation = next(
        (c for c in node.named_children if c.type == "object_creation_expression"),
        None,
    )
    if creation is not None:
        return _creation_type_name(creation)
    ident = next(
        (c for c in node.named_children if c.type == "identifier"), None
    )
    if ident is not None:
        return ident.text.decode("utf-8", errors="replace")
    return None


def _this_field_name(node: Node) -> str | None:
    """field_access 且 object 是 this → 字段名；否则 None。"""
    obj = node.child_by_field_name("object")
    field = node.child_by_field_name("field")
    if obj is None or field is None:
        return None
    if obj.type != "this":
        return None
    return field.text.decode("utf-8", errors="replace")


def _raw_rel(
    source_atom_id: str, kind: str, target_external: str, file_path: str, line: int
) -> Relation:
    return Relation(
        relation_id="",
        source_atom_id=source_atom_id,
        kind=kind,
        target_external=target_external,
        file_path=file_path,
        line=line,
    )


def _java_field_types(tree: Any) -> dict[str, str]:
    """字段名 → 声明类型简单名。

    跨类收集；同名字段在不同类型声明不同 → 从表剔除（宁缺勿错）。
    仅记录非 intrinsic 类型（String/Object/原始类型不进表，无解析价值）。
    """
    mapping: dict[str, str] = {}
    dropped: set[str] = set()
    for node in _iter_all(tree.root_node):
        if node.type != "field_declaration":
            continue
        type_node = node.child_by_field_name("type")
        if type_node is None:
            type_node = next(
                (c for c in node.named_children
                 if c.type not in ("modifiers", "variable_declarator")),
                None,
            )
        type_name = _type_base_name(type_node)
        if not type_name or type_name in _TYPE_INTRINSICS:
            continue
        for declarator in node.named_children:
            if declarator.type != "variable_declarator":
                continue
            name_node = declarator.child_by_field_name("name")
            if name_node is None:
                continue
            fname = name_node.text.decode("utf-8", errors="replace")
            if fname in dropped:
                continue
            if fname in mapping and mapping[fname] != type_name:
                del mapping[fname]  # 同名异型（跨类）→ 剔除
                dropped.add(fname)
            else:
                mapping[fname] = type_name
    return mapping
