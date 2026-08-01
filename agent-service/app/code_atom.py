"""Deterministic code anchors used by the binding pipeline.

The extractor deliberately stops at declarations.  It is not a call graph and
does not infer runtime behaviour.
"""

from __future__ import annotations

import ast
import hashlib
from dataclasses import dataclass
from enum import StrEnum
from pathlib import PurePosixPath
from typing import Any


class CodeAtomKind(StrEnum):
    MODULE = "MODULE"
    CLASS = "CLASS"
    FUNCTION = "FUNCTION"
    ASYNC_FUNCTION = "ASYNC_FUNCTION"
    METHOD = "METHOD"
    CLASS_METHOD = "CLASS_METHOD"
    HTTP_ROUTE = "HTTP_ROUTE"


@dataclass(frozen=True)
class CodeAtom:
    atom_id: str
    repository_id: str
    revision: str
    file_path: str
    language: str
    kind: CodeAtomKind
    symbol_key: str
    qualified_name: str
    display_name: str
    signature: str
    start_line: int
    end_line: int
    parent_atom_id: str | None = None
    route_method: str | None = None
    route_path: str | None = None
    response_model: str | None = None
    metadata: tuple[tuple[str, str], ...] = ()

    def metadata_value(self, key: str) -> str | None:
        return dict(self.metadata).get(key)


class PythonCodeAtomExtractor:
    """Extract stable module/class/function/method/FastAPI route atoms."""

    _ROUTE_METHODS = {"get", "post", "put", "patch", "delete", "api_route"}

    def extract(
        self,
        source: str,
        *,
        file_path: str,
        repository_id: str,
        revision: str,
    ) -> tuple[CodeAtom, ...]:
        normalized_path = PurePosixPath(file_path.replace("\\", "/")).as_posix()
        try:
            tree = ast.parse(source, filename=normalized_path)
        except (SyntaxError, ValueError):
            return ()

        line_count = max(1, len(source.splitlines()))
        module_id = _atom_id(repository_id, revision, normalized_path, "MODULE", normalized_path)
        atoms: list[CodeAtom] = [
            CodeAtom(
                atom_id=module_id,
                repository_id=repository_id,
                revision=revision,
                file_path=normalized_path,
                language="Python",
                kind=CodeAtomKind.MODULE,
                symbol_key=_symbol_key(normalized_path, normalized_path, CodeAtomKind.MODULE),
                qualified_name=normalized_path,
                display_name=PurePosixPath(normalized_path).name,
                signature="",
                start_line=1,
                end_line=line_count,
            )
        ]

        class Visitor(ast.NodeVisitor):
            def __init__(self) -> None:
                self.class_stack: list[tuple[str, str]] = []

            def visit_ClassDef(self, node: ast.ClassDef) -> Any:
                qualified = ".".join([*(name for name, _ in self.class_stack), node.name])
                atom_id = _atom_id(
                    repository_id, revision, normalized_path, CodeAtomKind.CLASS, qualified
                )
                parent = self.class_stack[-1][1] if self.class_stack else module_id
                bases = ", ".join(ast.unparse(base) for base in node.bases)
                atoms.append(
                    _atom(
                        atom_id,
                        repository_id,
                        revision,
                        normalized_path,
                        CodeAtomKind.CLASS,
                        qualified,
                        node.name,
                        f"class {node.name}({bases})" if bases else f"class {node.name}",
                        node,
                        parent,
                        metadata=(
                            ("bases", bases),
                            (
                                "isPydanticModel",
                                str(
                                    any(_base_name(base) == "BaseModel" for base in node.bases)
                                ).lower(),
                            ),
                        ),
                    )
                )
                self.class_stack.append((node.name, atom_id))
                self.generic_visit(node)
                self.class_stack.pop()

            def visit_FunctionDef(self, node: ast.FunctionDef) -> Any:
                self._visit_function(node, is_async=False)

            def visit_AsyncFunctionDef(self, node: ast.AsyncFunctionDef) -> Any:
                self._visit_function(node, is_async=True)

            def _visit_function(
                self, node: ast.FunctionDef | ast.AsyncFunctionDef, *, is_async: bool
            ) -> None:
                qualified = ".".join([*(name for name, _ in self.class_stack), node.name])
                route = _route_metadata(node.decorator_list)
                decorators = {_decorator_name(item) for item in node.decorator_list}
                if route is not None and not self.class_stack:
                    kind = CodeAtomKind.HTTP_ROUTE
                elif self.class_stack and "classmethod" in decorators:
                    kind = CodeAtomKind.CLASS_METHOD
                elif self.class_stack:
                    kind = CodeAtomKind.METHOD
                else:
                    kind = CodeAtomKind.ASYNC_FUNCTION if is_async else CodeAtomKind.FUNCTION
                atom_id = _atom_id(repository_id, revision, normalized_path, kind, qualified)
                parent = self.class_stack[-1][1] if self.class_stack else module_id
                calls = tuple(sorted(_direct_call_names(node)))
                annotations = tuple(sorted(_annotation_names(node)))
                metadata = (
                    ("decorators", ",".join(sorted(filter(None, decorators)))),
                    ("directCalls", ",".join(calls)),
                    ("annotations", ",".join(annotations)),
                )
                atoms.append(
                    _atom(
                        atom_id,
                        repository_id,
                        revision,
                        normalized_path,
                        kind,
                        qualified,
                        node.name,
                        _signature(node, is_async),
                        node,
                        parent,
                        route_method=route[0] if route else None,
                        route_path=route[1] if route else None,
                        response_model=route[2] if route else None,
                        metadata=metadata,
                    )
                )

        Visitor().visit(tree)
        return tuple(
            sorted(
                atoms,
                key=lambda item: (item.file_path, item.start_line, item.end_line, item.symbol_key),
            )
        )


def java_symbol_to_atom(
    symbol: dict[str, Any], *, repository_id: str, revision: str, file_path: str
) -> CodeAtom | None:
    symbol_key = str(symbol.get("symbolKey") or "").strip()
    qualified = str(symbol.get("qualifiedName") or symbol.get("simpleName") or "").strip()
    start = symbol.get("startLine")
    end = symbol.get("endLine")
    if not symbol_key or not qualified or not isinstance(start, int) or not isinstance(end, int):
        return None
    if start < 1 or end < start:
        return None
    raw_kind = str(symbol.get("symbolKind") or "").upper()
    kind = (
        CodeAtomKind.CLASS
        if raw_kind in {"CLASS", "INTERFACE", "ENUM", "RECORD", "ANNOTATION"}
        else CodeAtomKind.METHOD
    )
    return CodeAtom(
        atom_id=_atom_id(repository_id, revision, file_path, kind, symbol_key),
        repository_id=repository_id,
        revision=revision,
        file_path=file_path,
        language=str(symbol.get("language") or "Java"),
        kind=kind,
        symbol_key=symbol_key,
        qualified_name=qualified,
        display_name=str(symbol.get("simpleName") or qualified),
        signature=str(symbol.get("signature") or ""),
        start_line=start,
        end_line=end,
        parent_atom_id=None,
    )


def _atom(
    atom_id: str,
    repository_id: str,
    revision: str,
    file_path: str,
    kind: CodeAtomKind,
    qualified_name: str,
    display_name: str,
    signature: str,
    node: ast.ClassDef | ast.FunctionDef | ast.AsyncFunctionDef,
    parent_atom_id: str | None,
    *,
    route_method: str | None = None,
    route_path: str | None = None,
    response_model: str | None = None,
    metadata: tuple[tuple[str, str], ...] = (),
) -> CodeAtom:
    return CodeAtom(
        atom_id=atom_id,
        repository_id=repository_id,
        revision=revision,
        file_path=file_path,
        language="Python",
        kind=kind,
        symbol_key=_symbol_key(file_path, qualified_name, kind),
        qualified_name=qualified_name,
        display_name=display_name,
        signature=signature,
        start_line=int(node.lineno),
        end_line=int(node.end_lineno or node.lineno),
        parent_atom_id=parent_atom_id,
        route_method=route_method,
        route_path=route_path,
        response_model=response_model,
        metadata=metadata,
    )


def _atom_id(repository_id: str, revision: str, file_path: str, kind: object, name: str) -> str:
    raw = f"{repository_id}\0{revision}\0{file_path}\0{kind}\0{name}"
    return "atom_" + hashlib.sha256(raw.encode()).hexdigest()[:24]


def _symbol_key(file_path: str, qualified_name: str, kind: CodeAtomKind) -> str:
    return f"PYTHON:{file_path}:{qualified_name}:{kind.value}"


def _signature(node: ast.FunctionDef | ast.AsyncFunctionDef, is_async: bool) -> str:
    prefix = "async def" if is_async else "def"
    result = f"{prefix} {node.name}({ast.unparse(node.args)})"
    return result + (f" -> {ast.unparse(node.returns)}" if node.returns else "")


def _base_name(node: ast.expr) -> str:
    return node.id if isinstance(node, ast.Name) else ast.unparse(node)


def _decorator_name(node: ast.expr) -> str:
    target = node.func if isinstance(node, ast.Call) else node
    if isinstance(target, ast.Name):
        return target.id
    if isinstance(target, ast.Attribute):
        return target.attr
    return ""


def _route_metadata(decorators: list[ast.expr]) -> tuple[str, str, str | None] | None:
    for decorator in decorators:
        if not isinstance(decorator, ast.Call) or not isinstance(decorator.func, ast.Attribute):
            continue
        method_name = decorator.func.attr.lower()
        if method_name not in PythonCodeAtomExtractor._ROUTE_METHODS:
            continue
        if (
            not decorator.args
            or not isinstance(decorator.args[0], ast.Constant)
            or not isinstance(decorator.args[0].value, str)
        ):
            continue
        if method_name == "api_route":
            methods = next(
                (item.value for item in decorator.keywords if item.arg == "methods"), None
            )
            method = _literal_http_method(methods)
            if method is None:
                continue
        else:
            method = method_name.upper()
        response = next(
            (item.value for item in decorator.keywords if item.arg == "response_model"), None
        )
        return (
            method,
            decorator.args[0].value,
            ast.unparse(response) if response is not None else None,
        )
    return None


def _literal_http_method(node: ast.expr | None) -> str | None:
    if not isinstance(node, (ast.List, ast.Tuple)) or len(node.elts) != 1:
        return None
    value = node.elts[0]
    return (
        value.value.upper()
        if isinstance(value, ast.Constant) and isinstance(value.value, str)
        else None
    )


def _direct_call_names(node: ast.FunctionDef | ast.AsyncFunctionDef) -> set[str]:
    names: set[str] = set()
    for item in ast.walk(node):
        if not isinstance(item, ast.Call):
            continue
        if isinstance(item.func, ast.Name):
            names.add(item.func.id)
        elif isinstance(item.func, ast.Attribute):
            names.add(item.func.attr)
    return names


def _annotation_names(node: ast.FunctionDef | ast.AsyncFunctionDef) -> set[str]:
    values = [
        argument.annotation
        for argument in [*node.args.posonlyargs, *node.args.args, *node.args.kwonlyargs]
    ]
    if node.returns is not None:
        values.append(node.returns)
    return {ast.unparse(value) for value in values if value is not None}
