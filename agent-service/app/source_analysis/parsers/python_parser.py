"""Python 语言解析器——LanguageParser 协议的 Python 实现。

从 code_ast_atom.py / atom_relation_graph.py 原子迁移（S1，零行为变化）：
  - parse_file：原 _parse_python（含截断 best-effort 降级）
  - extract_relations：原 _collect_relations（含 _BUILTINS 跳过、intrinsics 过滤）
  - build_import_scope：委托 import_resolver.build_file_scope
  - is_external_name：原 _is_external（stdlib / 外部前缀判定）
"""

from __future__ import annotations

import ast
import logging
from pathlib import PurePosixPath
from typing import Any

from app.schemas.ast_atom import CLASS_LIKE_KINDS, ModuleAtom, SymbolAtom, SymbolKind
from app.schemas.repository_graph import Relation, RelationKind
from app.source_analysis.import_resolver import FileImportScope, build_file_scope
from app.source_analysis.parsers import hash_id, symbol_key

_logger = logging.getLogger("devcollab.agent.code_atom")

ROUTE_METHODS: frozenset[str] = frozenset(
    {"get", "post", "put", "patch", "delete", "api_route"}
)

# 第三方/框架的顶层模块名（import fastapi / from fastapi import ... 的归属判定）
_STDLIB_MODULES: frozenset[str] = frozenset({
    "os", "sys", "re", "json", "time", "datetime", "collections",
    "typing", "io", "pathlib", "logging", "hashlib", "uuid", "math",
    "random", "itertools", "functools", "asyncio", "threading",
    "subprocess", "socket", "http", "urllib", "enum", "abc", "dataclasses",
})

_EXTERNAL_PREFIXES: tuple[str, ...] = (
    "fastapi.", "pydantic.", "sqlalchemy.", "redis.", "celery.",
    "django.", "flask.", "requests.", "httpx.", "aiohttp.",
    "boto3.", "google.", "stripe.", "twilio.", "sendgrid.",
    "starlette.", "uvicorn.", "kafka.", "aiokafka.", "psycopg.",
    "asyncpg.", "numpy.", "pandas.",
)

_EXTERNAL_TOP_LEVEL: frozenset[str] = frozenset({
    "fastapi", "pydantic", "sqlalchemy", "redis", "celery", "django",
    "flask", "requests", "httpx", "aiohttp", "boto3", "google", "stripe",
    "twilio", "sendgrid", "starlette", "uvicorn", "kafka", "aiokafka",
    "psycopg", "asyncpg", "numpy", "pandas",
})

# 类型注解内置/泛型名——不产生关系（纯噪声）
_ANNOTATION_INTRINSICS: frozenset[str] = frozenset({
    "str", "int", "float", "bool", "bytes", "list", "dict", "set", "tuple",
    "frozenset", "Any", "Optional", "Union", "Literal", "Self", "Sequence",
    "Mapping", "Callable", "Iterable", "Iterator", "Type", "ClassVar",
    "Final", "object", "type",
})

_BUILTINS: frozenset[str] = frozenset({
    "print", "len", "str", "int", "float", "bool", "list", "dict",
    "set", "tuple", "type", "range", "enumerate", "zip", "map",
    "filter", "sorted", "reversed", "isinstance", "issubclass",
    "hasattr", "getattr", "setattr", "delattr", "super", "abs",
    "min", "max", "sum", "any", "all", "open", "repr", "id",
    "input", "next", "iter", "format", "pow", "round", "slice",
    "property", "staticmethod", "classmethod",
})


class PythonParser:
    language = "python"

    # ── 解析 ──────────────────────────────────────────────────────────

    def parse_file(
        self,
        *,
        repository_id: str,
        revision: str,
        file_path: str,
        source: str,
    ) -> tuple[ModuleAtom, tuple[SymbolAtom, ...]]:
        """解析单个 Python 文件，返回 ModuleAtom + SymbolAtom[]。"""
        normalized = PurePosixPath(file_path.replace("\\", "/")).as_posix()
        lines = source.splitlines()
        line_count = max(1, len(lines))

        tree = self.parse_source(source)
        if tree is None:
            # MCP code.read 可能截断返回值导致语法不完整。
            # 逐行削去尾部再尝试解析，直到成功或剩余过少。
            if len(source) < 500:
                _logger.warning(
                    "Cannot parse %s (%s chars): too short or invalid",
                    normalized, len(source),
                )
                return (ModuleAtom(atom_id="", file_path=normalized, language="Python",
                                   start_line=1, end_line=line_count), ())
            for cut in range(1, min(len(lines), 100)):
                stripped = "\n".join(lines[:-cut]) if cut < len(lines) else ""
                if not stripped.strip():
                    continue
                candidate = self.parse_source(stripped)
                if candidate is not None:
                    _logger.info(
                        "Best-effort parse succeeded for %s after stripping %s trailing lines",
                        normalized, cut,
                    )
                    tree = candidate
                    break
            if tree is None:
                _logger.warning(
                    "Cannot parse %s (%s chars): truncated or invalid syntax "
                    "after stripping up to 50 lines",
                    normalized, len(source),
                )
                return (ModuleAtom(atom_id="", file_path=normalized, language="Python",
                                   start_line=1, end_line=line_count), ())

        module_doc = ast.get_docstring(tree)
        module = ModuleAtom(
            atom_id=hash_id("mod", revision, normalized),
            file_path=normalized,
            language="Python",
            start_line=1,
            end_line=line_count,
            docstring=module_doc,
        )

        symbols: list[SymbolAtom] = []
        visitor = _AtomVisitor(repository_id, revision, normalized, lines, symbols)
        visitor.visit(tree)

        return module, tuple(symbols)

    def parse_source(self, source: str) -> ast.Module | None:
        """源码 → AST；语法错误返回 None（不抛异常）。"""
        try:
            return ast.parse(source)
        except (SyntaxError, ValueError):
            return None

    # ── 关系收集 ──────────────────────────────────────────────────────

    def extract_relations(
        self, sym: SymbolAtom, tree: ast.Module, file: str
    ) -> list[Relation]:
        """遍历符号对应的 AST 节点，收集调用、类型引用等关系。

        返回的 Relation 中 target_external 是待解析的临时键：
          - CALLS/CREATES: 点路径（"service.create" / "helper"），解析时按最后一个点拆
            base 与 target；
          - PARAMETER_TYPE/RETURN_TYPE/THROWS: 简单名。
        relation_id 留空，由 build_graph 的 _finalize 在分类时按最终字段确定。
        """
        return _collect_relations(sym, tree, file)

    # ── import 作用域 ─────────────────────────────────────────────────

    def build_import_scope(
        self, file: str, tree: ast.Module, file_paths: frozenset[str]
    ) -> FileImportScope:
        return build_file_scope(file, tree, file_paths)

    # ── 外部名判定 ────────────────────────────────────────────────────

    def is_external_name(self, target: str) -> bool:
        parts = target.split(".")
        if parts and parts[0] in _STDLIB_MODULES:
            return True
        if parts and parts[0] in _EXTERNAL_TOP_LEVEL:
            return True
        full = target.lower()
        return any(full.startswith(prefix) for prefix in _EXTERNAL_PREFIXES)


PARSER = PythonParser()


# ── AST 遍历器 ─────────────────────────────────────────────────────────────


class _AtomVisitor(ast.NodeVisitor):
    """遍历 ClassDef / FunctionDef / AsyncFunctionDef，产出 SymbolAtom。"""

    def __init__(
        self,
        repository_id: str,
        revision: str,
        file_path: str,
        source_lines: list[str],
        symbols_out: list[SymbolAtom],
    ) -> None:
        self._repo = repository_id
        self._rev = revision
        self._file = file_path
        self._lines = source_lines
        self._symbols = symbols_out
        self._class_stack: list[str] = []        # qualified_name 栈
        self._function_stack: list[str] = []     # 当前函数 qualified，供 Block 归属用

    # ── 类 ────────────────────────────────────────────────────────────

    def visit_ClassDef(self, node: ast.ClassDef) -> Any:
        qualified = ".".join([*self._class_stack, node.name])
        parent = self._class_stack[-1] if self._class_stack else None
        bases = [_base_name(b) for b in node.bases]

        atom = _build_symbol(
            repo=self._repo,
            rev=self._rev,
            file=self._file,
            node=node,
            kind=SymbolKind.CLASS,
            name=node.name,
            qualified=qualified,
            parent_qualified=parent,
            lines=self._lines,
            is_pydantic="BaseModel" in bases,
        )
        self._symbols.append(atom)
        self._class_stack.append(qualified)

        self.generic_visit(node)
        self._class_stack.pop()

    # ── 函数 ──────────────────────────────────────────────────────────

    def visit_FunctionDef(self, node: ast.FunctionDef) -> Any:
        self._visit_function(node, is_async=False)

    def visit_AsyncFunctionDef(self, node: ast.AsyncFunctionDef) -> Any:
        self._visit_function(node, is_async=True)

    def _visit_function(
        self,
        node: ast.FunctionDef | ast.AsyncFunctionDef,
        *,
        is_async: bool,
    ) -> None:
        in_class = len(self._class_stack) > 0
        prefix = self._class_stack if in_class else []
        qualified = ".".join([*prefix, node.name])

        # 路由检测（仅顶层函数）
        # FastAPI/Starlette 也允许把路由处理器放在类方法中，不能因为
        # 存在 class scope 就丢弃 decorator 上的 HTTP 路由字段。
        route = _detect_route(node.decorator_list)

        decorators = tuple(
            sorted({_decorator_name(d) for d in node.decorator_list} - {""})
        )

        kind = SymbolKind.ASYNC_FUNCTION if is_async else SymbolKind.FUNCTION
        if in_class:
            kind = (
                SymbolKind.CLASS_METHOD
                if "classmethod" in decorators
                else SymbolKind.METHOD
            )

        parent_qualified = prefix[-1] if prefix else None

        atom = _build_symbol(
            repo=self._repo,
            rev=self._rev,
            file=self._file,
            node=node,
            kind=kind,
            name=node.name,
            qualified=qualified,
            parent_qualified=parent_qualified,
            lines=self._lines,
            is_async=is_async,
            decorators=decorators,
            route=route,
        )
        self._symbols.append(atom)
        self._function_stack.append(qualified)
        self.generic_visit(node)
        self._function_stack.pop()


# ── 构造 SymbolAtom ─────────────────────────────────────────────────────────


def _build_symbol(
    *,
    repo: str,
    rev: str,
    file: str,
    node: ast.ClassDef | ast.FunctionDef | ast.AsyncFunctionDef,
    kind: str,
    name: str,
    qualified: str,
    parent_qualified: str | None,
    lines: list[str],
    is_async: bool = False,
    is_pydantic: bool = False,
    decorators: tuple[str, ...] = (),
    route: tuple[str, str, str | None] | None = None,
) -> SymbolAtom:
    """从 AST 节点构造 SymbolAtom。"""
    start = int(node.lineno)
    end = int(node.end_lineno or node.lineno)

    body_start = _find_body_start(node, start, end)
    body_end = _find_body_end(node, start, end, body_start)

    if kind == SymbolKind.CLASS:
        sig = _class_signature(node)
    else:
        sig = _function_signature(node, is_async)

    doc = ast.get_docstring(node)

    return SymbolAtom(
        atom_id=hash_id("sym", rev, file, kind, qualified),
        symbol_key=symbol_key("PYTHON", file, qualified, kind),
        kind=kind,
        name=name,
        qualified_name=qualified,
        signature=sig,
        start_line=start,
        end_line=end,
        body_start_line=body_start,
        body_end_line=body_end,
        decorator_names=decorators,
        docstring=doc,
        parent_qualified=parent_qualified,
        is_async=is_async,
        is_pydantic=is_pydantic,
        http_method=route[0] if route else None,
        http_path=route[1] if route else None,
        http_response=route[2] if route else None,
    )


def _find_body_start(
    node: ast.AST,
    start: int,
    end: int,
) -> int:
    """找到函数体第一行（跳过装饰器、签名、docstring）。"""
    for child in ast.iter_child_nodes(node):
        if not hasattr(child, "lineno"):
            continue
        if isinstance(child, ast.Expr) and isinstance(child.value, ast.Constant):
            continue  # docstring
        return child.lineno
    return end if end > start else start + 1


def _find_body_end(
    node: ast.AST,
    start: int,
    end: int,
    body_start: int,
) -> int:
    """找到函数体最后一行，排除内部嵌套定义占用的行。"""
    nested: list[tuple[int, int]] = []
    for child in ast.iter_child_nodes(node):
        if not hasattr(child, "lineno"):
            continue
        if isinstance(child, (ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef)):
            nested.append((child.lineno, int(child.end_lineno or child.lineno)))

    if not nested:
        return end

    nested.sort()
    # 如果嵌套定义占据了末尾 → body_end 移到第一个嵌套定义之前
    result = end
    for nest_start, nest_end in reversed(nested):
        if nest_end == result:
            result = nest_start - 1
        elif nest_end < result:
            break
    if result < body_start:
        result = nested[0][0] - 1
    return result


# ── 辅助 ────────────────────────────────────────────────────────────────────


def _class_signature(node: ast.ClassDef) -> str:
    bases = ", ".join(ast.unparse(b) for b in node.bases)
    if bases:
        return f"class {node.name}({bases})"
    return f"class {node.name}"


def _function_signature(
    node: ast.FunctionDef | ast.AsyncFunctionDef,
    is_async: bool,
) -> str:
    prefix = "async def" if is_async else "def"
    sig = f"{prefix} {node.name}({ast.unparse(node.args)})"
    if node.returns:
        sig += f" -> {ast.unparse(node.returns)}"
    return sig


def _base_name(node: ast.expr) -> str:
    return node.id if isinstance(node, ast.Name) else ast.unparse(node)


def _decorator_name(node: ast.expr) -> str:
    target = node.func if isinstance(node, ast.Call) else node
    if isinstance(target, ast.Name):
        return target.id
    if isinstance(target, ast.Attribute):
        return target.attr
    return ""


def _detect_route(
    decorators: list[ast.expr],
) -> tuple[str, str, str | None] | None:
    """从装饰器列表提取 FastAPI 路由信息。

    @router.post("/users", response_model=UserResponse)
    → ("POST", "/users", "UserResponse")
    """
    for d in decorators:
        if not isinstance(d, ast.Call) or not isinstance(d.func, ast.Attribute):
            continue
        method = d.func.attr.lower()
        if method not in ROUTE_METHODS:
            continue
        if (
            not d.args
            or not isinstance(d.args[0], ast.Constant)
            or not isinstance(d.args[0].value, str)
        ):
            continue
        if method == "api_route":
            methods_kw = next(
                (kw.value for kw in d.keywords if kw.arg == "methods"), None
            )
            http_m = _single_http_method(methods_kw)
            if http_m is None:
                continue
        else:
            http_m = method.upper()
        resp = next(
            (kw.value for kw in d.keywords if kw.arg == "response_model"), None
        )
        return (
            http_m,
            d.args[0].value,
            ast.unparse(resp) if resp is not None else None,
        )
    return None


def _single_http_method(node: ast.expr | None) -> str | None:
    if not isinstance(node, (ast.List, ast.Tuple)) or len(node.elts) != 1:
        return None
    value = node.elts[0]
    if isinstance(value, ast.Constant) and isinstance(value.value, str):
        return value.value.upper()
    return None


# ── 关系收集 ────────────────────────────────────────────────────────────────


def _collect_relations(
    sym: SymbolAtom,
    tree: ast.Module,
    file: str,
) -> list[Relation]:
    """遍历符号对应的 AST 节点，收集调用、类型引用等关系。"""
    result: list[Relation] = []

    node = _find_node(tree, sym.name, sym.start_line, sym.kind)
    if node is None:
        return result

    # 参数类型
    if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
        for arg in node.args.args + node.args.kwonlyargs:
            if arg.annotation:
                for type_name in _extract_type_names(arg.annotation):
                    if type_name in _ANNOTATION_INTRINSICS:
                        continue
                    result.append(
                        _raw_relation(
                            sym.atom_id, RelationKind.PARAMETER_TYPE,
                            type_name, file, arg.lineno,
                        )
                    )
        # 返回类型
        if node.returns:
            for type_name in _extract_type_names(node.returns):
                if type_name in _ANNOTATION_INTRINSICS:
                    continue
                result.append(
                    _raw_relation(
                        sym.atom_id, RelationKind.RETURN_TYPE, type_name, file,
                        node.returns.lineno
                        if hasattr(node.returns, "lineno")
                        else node.lineno,
                    )
                )

    # 遍历函数/类体
    # 类原子只收类体直接语句（类级赋值等），方法体内的调用归方法原子自己收集，
    # 避免同一条调用在类原子和方法原子各出现一次。
    if sym.kind in CLASS_LIKE_KINDS:
        walk_targets: list[ast.AST] = [
            child
            for child in node.body
            if not isinstance(child, (ast.FunctionDef, ast.AsyncFunctionDef))
        ]
    else:
        walk_targets = [node]

    for walk_target in walk_targets:
        for child in ast.walk(walk_target):
            if isinstance(child, ast.Call):
                base, target = _call_parts(child)
                if not target or target in _BUILTINS:
                    continue
                dotted = f"{base}.{target}" if base else target
                result.append(
                    _raw_relation(sym.atom_id, RelationKind.CALLS, dotted, file, child.lineno)
                )
            elif isinstance(child, ast.Raise):
                exc_name = _exception_name(child)
                if exc_name:
                    result.append(
                        _raw_relation(
                            sym.atom_id, RelationKind.THROWS, exc_name, file, child.lineno
                        )
                    )
            elif isinstance(child, ast.Assign):
                for target in child.targets:
                    if isinstance(target, ast.Attribute) and _is_self(target.value):
                        result.append(
                            _raw_relation(
                                sym.atom_id, RelationKind.FIELD_WRITES,
                                target.attr, file, child.lineno,
                            )
                        )
            elif isinstance(child, ast.Attribute):
                if _is_self(child.value):
                    result.append(
                        _raw_relation(
                            sym.atom_id, RelationKind.FIELD_READS, child.attr, file, child.lineno
                        )
                    )

    return result


def _find_node(tree: ast.Module, name: str, lineno: int, kind: str) -> Any:
    for node in ast.walk(tree):
        if not hasattr(node, "lineno"):
            continue
        if node.lineno != lineno:
            continue
        if kind == SymbolKind.CLASS:
            if isinstance(node, ast.ClassDef) and node.name == name:
                return node
        elif isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name == name:
            return node
    return None


def _call_parts(node: ast.Call) -> tuple[str | None, str]:
    """(base, target)。base 为属性链的可解析基底，target 为最末属性名。

    foo()            → (None, "foo")
    service.create() → ("service", "create")
    self.repo.save() → ("self.repo", "save")
    obj[0].run()     → (None, "")（动态基底，不解析）
    """
    func = node.func
    if isinstance(func, ast.Name):
        return None, func.id
    if isinstance(func, ast.Attribute):
        base = _dotted_base(func.value)  # 从 value 开始，不含最后一层 attr
        return base, func.attr
    return None, ""


def _dotted_base(node: ast.AST) -> str | None:
    """属性链最左 Name 之外的完整点路径；基底不是 Name（动态）→ None。"""
    segments: list[str] = []
    current: ast.AST = node
    while isinstance(current, ast.Attribute):
        segments.append(current.attr)
        current = current.value
    if not isinstance(current, ast.Name):
        return None
    segments.append(current.id)
    return ".".join(reversed(segments))


def _exception_name(node: ast.Raise) -> str | None:
    if node.exc is None:
        return None
    if isinstance(node.exc, ast.Call) and isinstance(node.exc.func, ast.Name):
        return node.exc.func.id
    if isinstance(node.exc, ast.Name):
        return node.exc.id
    return None


def _extract_type_names(node: ast.expr) -> list[str]:
    names: list[str] = []
    if isinstance(node, ast.Name):
        names.append(node.id)
    elif isinstance(node, ast.Attribute):
        names.append(node.attr)
    elif isinstance(node, ast.Subscript):
        names.extend(_extract_type_names(node.value))
    elif isinstance(node, ast.BinOp):
        names.extend(_extract_type_names(node.left))
        names.extend(_extract_type_names(node.right))
    return names


def _is_self(node: ast.expr) -> bool:
    return isinstance(node, ast.Name) and node.id == "self"


def _raw_relation(
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
