"""仓库关系图构建——第 2a 层处理器。

输入  AtomCatalog + 源码读取函数。
输出  RepositoryCodeGraph（AtomCatalog + 完整关系图 + 正向/反向索引）。

全链路主键统一使用 atom_id。
"""

from __future__ import annotations

import ast
import hashlib
from collections import defaultdict
from collections.abc import Callable
from typing import Any

from app.schemas.ast_atom import AtomCatalog, SymbolAtom, SymbolKind
from app.schemas.repository_graph import (
    Relation,
    RelationCategory,
    RelationKind,
    RepositoryCodeGraph,
)

_STDLIB_MODULES: frozenset[str] = frozenset({
    "os", "sys", "re", "json", "time", "datetime", "collections",
    "typing", "io", "pathlib", "logging", "hashlib", "uuid", "math",
    "random", "itertools", "functools", "asyncio", "threading",
    "subprocess", "socket", "http", "urllib",
})

_EXTERNAL_PREFIXES: tuple[str, ...] = (
    "fastapi.", "pydantic.", "sqlalchemy.", "redis.", "celery.",
    "django.", "flask.", "requests.", "httpx.", "aiohttp.",
    "boto3.", "google.", "stripe.", "twilio.", "sendgrid.",
)

_BUILTINS: frozenset[str] = frozenset({
    "print", "len", "str", "int", "float", "bool", "list", "dict",
    "set", "tuple", "type", "range", "enumerate", "zip", "map",
    "filter", "sorted", "reversed", "isinstance", "issubclass",
    "hasattr", "getattr", "setattr", "delattr", "super", "abs",
    "min", "max", "sum", "any", "all", "open", "repr", "id",
    "input", "next", "iter", "format", "pow", "round", "slice",
})


def build_graph(
    catalog: AtomCatalog,
    source_reader: Callable[[str], str | None],
) -> RepositoryCodeGraph:
    """AtomCatalog → RepositoryCodeGraph。"""

    # 查找索引
    by_atom_id: dict[str, SymbolAtom] = {s.atom_id: s for s in catalog.symbols}
    by_qualified: dict[str, SymbolAtom] = {
        s.qualified_name: s for s in catalog.symbols
    }

    relations: list[Relation] = []
    parsed: dict[str, ast.Module] = {}

    for sym in catalog.symbols:
        file = _file_from_key(sym.symbol_key)
        if not file:
            continue
        source = source_reader(file)
        if source is None:
            continue
        if file not in parsed:
            try:
                tree = ast.parse(source, filename=file)
            except (SyntaxError, ValueError):
                continue
            parsed[file] = tree
        relations.extend(_collect_relations(sym, parsed[file], file))

    relations.extend(_collect_contains(catalog))

    # 分类: INTERNAL / BOUNDARY / UNRESOLVED
    internal: list[Relation] = []
    boundary: list[Relation] = []
    unresolved: list[Relation] = []

    for rel in relations:
        # 尝试从 qualified_name 或 atom_id 查找目标
        target = (
            by_qualified.get(rel.target_external or "")
            or by_atom_id.get(rel.target_external or "")
        )
        if target is not None:
            internal.append(Relation(
                relation_id=_rel_id(),
                source_atom_id=rel.source_atom_id,
                kind=rel.kind,
                target_atom_id=target.atom_id,
                category=RelationCategory.INTERNAL,
                file_path=rel.file_path,
                line=rel.line,
            ))
        elif _is_external(rel.target_external or ""):
            boundary.append(Relation(
                relation_id=_rel_id(),
                source_atom_id=rel.source_atom_id,
                kind=rel.kind,
                target_external=rel.target_external,
                category=RelationCategory.BOUNDARY,
                file_path=rel.file_path,
                line=rel.line,
            ))
        else:
            unresolved.append(Relation(
                relation_id=_rel_id(),
                source_atom_id=rel.source_atom_id,
                kind=rel.kind,
                target_external=rel.target_external,
                category=RelationCategory.UNRESOLVED,
                file_path=rel.file_path,
                line=rel.line,
            ))

    all_relations = [*internal, *boundary, *unresolved]

    # 正向索引
    fwd: dict[str, list[Relation]] = defaultdict(list)
    for r in all_relations:
        fwd[r.source_atom_id].append(r)

    # 反向索引（仅 INTERNAL）
    rev: dict[str, list[Relation]] = defaultdict(list)
    for r in internal:
        if r.target_atom_id:
            rev[r.target_atom_id].append(r)

    return RepositoryCodeGraph(
        catalog=catalog,
        relations=tuple(all_relations),
        forward_index={k: tuple(v) for k, v in fwd.items()},
        reverse_index={k: tuple(v) for k, v in rev.items()},
        boundary=tuple(boundary),
        unresolved=tuple(unresolved),
    )


# ── 关系收集 ────────────────────────────────────────────────────────────

def _collect_relations(
    sym: SymbolAtom,
    tree: ast.Module,
    file: str,
) -> list[Relation]:
    """遍历符号对应的 AST 节点，收集调用、类型引用等关系。

    返回的 Relation 中 target_external 是临时存储的查找键，
    由 build_graph 在分类阶段解析为 target_atom_id 或保留为 external。
    """
    result: list[Relation] = []

    node = _find_node(tree, sym.name, sym.start_line, sym.kind)
    if node is None:
        return result

    # 参数类型
    if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
        for arg in node.args.args + node.args.kwonlyargs:
            if arg.annotation:
                for type_name in _extract_type_names(arg.annotation):
                    result.append(Relation(
                        relation_id=_rel_id(),
                        source_atom_id=sym.atom_id,
                        kind=RelationKind.PARAMETER_TYPE,
                        target_external=type_name,
                        file_path=file, line=arg.lineno,
                    ))
        # 返回类型
        if node.returns:
            for type_name in _extract_type_names(node.returns):
                result.append(Relation(
                    relation_id=_rel_id(),
                    source_atom_id=sym.atom_id,
                    kind=RelationKind.RETURN_TYPE,
                    target_external=type_name,
                    file_path=file,
                    line=node.returns.lineno if hasattr(node.returns, "lineno") else node.lineno,
                ))

    # 遍历函数/类体
    for child in ast.walk(node):
        if isinstance(child, ast.Call):
            call_name = _call_target_name(child)
            if call_name and call_name not in _BUILTINS:
                result.append(Relation(
                    relation_id=_rel_id(),
                    source_atom_id=sym.atom_id,
                    kind=RelationKind.CALLS,
                    target_external=call_name,
                    file_path=file, line=child.lineno,
                ))
        elif isinstance(child, ast.Raise):
            exc_name = _exception_name(child)
            if exc_name:
                result.append(Relation(
                    relation_id=_rel_id(),
                    source_atom_id=sym.atom_id,
                    kind=RelationKind.THROWS,
                    target_external=exc_name,
                    file_path=file, line=child.lineno,
                ))
        elif isinstance(child, ast.Assign):
            for target in child.targets:
                if isinstance(target, ast.Attribute) and _is_self(target.value):
                    result.append(Relation(
                        relation_id=_rel_id(),
                        source_atom_id=sym.atom_id,
                        kind=RelationKind.FIELD_WRITES,
                        target_external=target.attr,
                        file_path=file, line=child.lineno,
                    ))
        elif isinstance(child, ast.Attribute):
            if _is_self(child.value):
                result.append(Relation(
                    relation_id=_rel_id(),
                    source_atom_id=sym.atom_id,
                    kind=RelationKind.FIELD_READS,
                    target_external=child.attr,
                    file_path=file, line=child.lineno,
                ))

    return result


def _collect_contains(catalog: AtomCatalog) -> list[Relation]:
    """类 → 其方法（CONTAINS）。"""
    result: list[Relation] = []
    for sym in catalog.symbols:
        if sym.kind == SymbolKind.CLASS:
            for child in catalog.symbols:
                if child.parent_qualified == sym.qualified_name:
                    result.append(Relation(
                        relation_id=_rel_id(),
                        source_atom_id=sym.atom_id,
                        kind=RelationKind.CONTAINS,
                        target_atom_id=child.atom_id,
                        category=RelationCategory.INTERNAL,
                        file_path=_file_from_key(child.symbol_key),
                        line=child.start_line,
                    ))
    return result


# ── helpers ─────────────────────────────────────────────────────────────

def _rel_id() -> str:
    return "rel_" + hashlib.sha256(b"").hexdigest()[:16]  # placeholder


def _find_node(tree, name, lineno, kind):
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


def _call_target_name(node: ast.Call) -> str | None:
    if isinstance(node.func, ast.Name):
        return node.func.id
    if isinstance(node.func, ast.Attribute):
        return node.func.attr
    return None


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


def _is_external(name: str) -> bool:
    parts = name.split(".")
    if parts[0] in _STDLIB_MODULES:
        return True
    full = name.lower()
    for prefix in _EXTERNAL_PREFIXES:
        if full.startswith(prefix):
            return True
    return False


def _file_from_key(symbol_key: str) -> str:
    parts = symbol_key.split(":", 2)
    return parts[1] if len(parts) > 1 else ""
