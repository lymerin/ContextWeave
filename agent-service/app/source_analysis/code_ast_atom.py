"""AST 结构解析——第 1 层处理器。

输入  SourceFileBatch + 源码读取函数。
输出  AtomCatalog（所有文件的 ModuleAtom + SymbolAtom）。

所有符号按 (file_path, start_line) 排序。
语法错误的文件产生一个空的 ModuleAtom，不中断整批解析。
"""

from __future__ import annotations

import ast
import hashlib
from collections.abc import Callable
from pathlib import PurePosixPath
from typing import Any

from app.schemas.ast_atom import AtomCatalog, ModuleAtom, SymbolAtom, SymbolKind
from app.schemas.source_file import SourceFileBatch


# ── 常量 ────────────────────────────────────────────────────────────────────

ROUTE_METHODS: frozenset[str] = frozenset(
    {"get", "post", "put", "patch", "delete", "api_route"}
)


# ── 入口 ────────────────────────────────────────────────────────────────────


def parse_batch(
    batch: SourceFileBatch,
    source_reader: Callable[[str], str | None],
) -> AtomCatalog:
    """对 SourceFileBatch 中的所有文件执行 AST 解析。

    source_reader 接收 file_path，返回源码文本或 None（文件不可读）。
    """
    modules: list[ModuleAtom] = []
    symbols: list[SymbolAtom] = []

    for ref in batch.files:
        source = source_reader(ref.file_path)
        if source is None:
            continue
        language = (ref.language or "").lower()
        if language == "python":
            module, file_symbols = _parse_python(
                str(batch.repository_id),
                batch.revision,
                ref.file_path,
                source,
            )
        else:
            # 非 Python 文件暂不解析符号，只生成 ModuleAtom。
            lines = source.splitlines()
            module = ModuleAtom(
                atom_id=_hash_id("mod", batch.revision, ref.file_path),
                file_path=ref.file_path,
                language=ref.language,
                start_line=1,
                end_line=max(1, len(lines)),
            )
            file_symbols = ()
        modules.append(module)
        symbols.extend(file_symbols)

    modules.sort(key=lambda m: m.file_path)
    symbols.sort(key=lambda s: (_symbol_file(s), s.start_line, s.symbol_key))

    return AtomCatalog(
        repository_id=batch.repository_id,
        revision=batch.revision,
        modules=tuple(modules),
        symbols=tuple(symbols),
    )


# Python helper for extracting file_path from a symbol.
# (SymbolAtom doesn't carry file_path directly; it's embedded in symbol_key.)
def _symbol_file(s: SymbolAtom) -> str:
    # symbol_key format: PYTHON:file_path:qualified:kind
    parts = s.symbol_key.split(":", 2)
    return parts[1] if len(parts) > 1 else ""


#  ╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌


# ── Python 解析器 ───────────────────────────────────────────────────────────


def _parse_python(
    repository_id: str,
    revision: str,
    file_path: str,
    source: str,
) -> tuple[ModuleAtom, tuple[SymbolAtom, ...]]:
    """解析单个 Python 文件，返回 ModuleAtom + SymbolAtom[]。"""
    normalized = PurePosixPath(file_path.replace("\\", "/")).as_posix()
    lines = source.splitlines()
    line_count = max(1, len(lines))

    try:
        tree = ast.parse(source, filename=normalized)
    except (SyntaxError, ValueError):
        return (
            ModuleAtom(
                atom_id="",
                file_path=normalized,
                language="Python",
                start_line=1,
                end_line=line_count,
            ),
            (),
        )

    module_doc = ast.get_docstring(tree)
    module = ModuleAtom(
        atom_id=_hash_id("mod", revision, normalized),
        file_path=normalized,
        language="Python",
        start_line=1,
        end_line=line_count,
        docstring=module_doc,
    )

    symbols: list[SymbolAtom] = []
    visitor = _AtomVisitor(
        repository_id, revision, normalized, lines, symbols
    )
    visitor.visit(tree)

    return module, tuple(symbols)


# ── AST 遍历器 ──────────────────────────────────────────────────────────────


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
        route = _detect_route(node.decorator_list) if not in_class else None

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
        atom_id=_hash_id("sym", rev, file, kind, qualified),
        symbol_key=f"PYTHON:{file}:{qualified}:{kind}",
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


def _hash_id(prefix: str, *parts: str) -> str:
    return prefix + "_" + hashlib.sha256(
        "\0".join(parts).encode()
    ).hexdigest()[:24]


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
