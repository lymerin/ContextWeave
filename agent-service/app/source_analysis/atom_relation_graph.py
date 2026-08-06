"""仓库关系图构建——第 2a 层处理器。

输入  AtomCatalog + 源码读取函数。
输出  RepositoryCodeGraph（AtomCatalog + 完整关系图 + 正向/反向索引）。

解析原则:
  - 跨文件调用/类型引用通过 import 别名表确定性解析（import_resolver）。
  - 符号查找按文件作用域，杜绝跨文件同名符号碰撞。
  - 解析到 0 或 ≥2 个候选 → UNRESOLVED，禁止猜测；动态调用（非 Name/Attribute 基底）→ UNRESOLVED。
  - relation_id 是 (源原子, 类型, 目标, 文件, 行) 的确定性哈希，同 revision 内稳定。
  - 调用目标解析为 CLASS 原子 → 升级为 CREATES（实例化），其余 → CALLS。
"""

from __future__ import annotations

import ast
import hashlib
from collections import defaultdict
from collections.abc import Callable

from app.schemas.ast_atom import AtomCatalog, SymbolAtom, SymbolKind
from app.schemas.repository_graph import (
    Relation,
    RelationCategory,
    RelationKind,
    RepositoryCodeGraph,
)
from app.source_analysis.import_resolver import (
    FileImportScope,
    build_file_scope,
    module_to_file,
)

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

# 第三方/框架的顶层模块名（import fastapi / from fastapi import ... 的归属判定）
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


def build_graph(
    catalog: AtomCatalog,
    source_reader: Callable[[str], str | None],
) -> RepositoryCodeGraph:
    """AtomCatalog → RepositoryCodeGraph（import 感知、按文件作用域解析）。"""

    file_symbols = _group_symbols_by_file(catalog)
    file_paths = frozenset(m.file_path for m in catalog.modules)

    relations: list[Relation] = []
    parsed: dict[str, ast.Module] = {}
    scopes: dict[str, FileImportScope] = {}

    for sym in catalog.symbols:
        file = _file_from_key(sym.symbol_key)
        if not file:
            continue
        tree = _parsed_tree(file, parsed, source_reader)
        if tree is None:
            continue
        if file not in scopes:
            scopes[file] = build_file_scope(file, tree, file_paths)
        relations.extend(_collect_relations(sym, tree, file))

    relations.extend(_collect_contains(catalog))

    # 分类: INTERNAL / BOUNDARY / UNRESOLVED
    internal: list[Relation] = []
    boundary: list[Relation] = []
    unresolved: list[Relation] = []

    for rel in relations:
        # 已确定 INTERNAL 的关系（CONTAINS）直接保留，不再走名字解析
        if rel.category == RelationCategory.INTERNAL and rel.target_atom_id:
            internal.append(
                _finalize(
                    rel,
                    target_atom_id=rel.target_atom_id,
                    category=RelationCategory.INTERNAL,
                )
            )
            continue
        target = _resolve_target(
            rel, scopes.get(rel.file_path), file_symbols, file_paths
        )
        if target is not None:
            kind = rel.kind
            if rel.kind == RelationKind.CALLS and target.kind == SymbolKind.CLASS:
                kind = RelationKind.CREATES
            internal.append(
                _finalize(
                    rel,
                    target_atom_id=target.atom_id,
                    kind=kind,
                    category=RelationCategory.INTERNAL,
                )
            )
        elif _is_external(rel.target_external or ""):
            boundary.append(_finalize(rel, category=RelationCategory.BOUNDARY))
        else:
            unresolved.append(_finalize(rel, category=RelationCategory.UNRESOLVED))

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

    返回的 Relation 中 target_external 是待解析的临时键：
      - CALLS/CREATES: 点路径（"service.create" / "helper"），解析时按最后一个点拆
        base 与 target；
      - PARAMETER_TYPE/RETURN_TYPE/THROWS: 简单名。
    relation_id 留空，由 _finalize 在分类时按最终字段确定。
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
    if sym.kind == SymbolKind.CLASS:
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


def _collect_contains(catalog: AtomCatalog) -> list[Relation]:
    """类 → 其方法（CONTAINS）。"""
    result: list[Relation] = []
    for sym in catalog.symbols:
        if sym.kind != SymbolKind.CLASS:
            continue
        for child in catalog.symbols:
            if child.parent_qualified == sym.qualified_name:
                file = _file_from_key(child.symbol_key)
                result.append(
                    Relation(
                        relation_id=_rel_id(
                            sym.atom_id, RelationKind.CONTAINS,
                            child.atom_id, file, child.start_line,
                        ),
                        source_atom_id=sym.atom_id,
                        kind=RelationKind.CONTAINS,
                        target_atom_id=child.atom_id,
                        category=RelationCategory.INTERNAL,
                        file_path=file,
                        line=child.start_line,
                    )
                )
    return result


# ── 目标解析（import 感知、按文件作用域）───────────────────────────────────


def _resolve_target(
    rel: Relation,
    scope: FileImportScope | None,
    file_symbols: dict[str, list[SymbolAtom]],
    file_paths: frozenset[str],
) -> SymbolAtom | None:
    """把关系的临时键解析为仓库内符号原子；解析不到返回 None（交由外部/未解析分类）。"""
    if scope is None:
        return None
    name = rel.target_external or ""
    if not name:
        return None
    if rel.kind in (RelationKind.CALLS, RelationKind.CREATES):
        base, target = name.rsplit(".", 1) if "." in name else (None, name)
        return _resolve_call(base, target, rel.file_path, scope, file_symbols, file_paths)
    return _resolve_name(name, rel.file_path, scope, file_symbols, file_paths)


def _resolve_call(
    base: str | None,
    target: str,
    file: str,
    scope: FileImportScope,
    file_symbols: dict[str, list[SymbolAtom]],
    file_paths: frozenset[str],
) -> SymbolAtom | None:
    """调用目标的确定性解析。候选 0 个或 ≥2 个 → None。"""
    if not target:
        return None
    local = file_symbols.get(file, ())
    aliases = dict(scope.module_aliases)
    from_imports = dict(scope.symbol_imports)

    if base is None:
        # 1) 同文件顶层函数/类
        hits = [s for s in local if s.name == target and s.parent_qualified is None]
        if len(hits) == 1:
            return hits[0]
        # 2) from x import Y 的符号导入
        return _lookup_in_module(from_imports.get(target), target, file_symbols, file_paths)

    if base == "self" or base == "cls":
        # 3) 同类内方法（同文件方法名唯一才可判定）
        hits = [s for s in local if s.name == target and s.parent_qualified is not None]
        return hits[0] if len(hits) == 1 else None

    # 4) 模块别名展开：a.b.c → 别名(首段) + 剩余段
    module = _expand_module(base, aliases)
    if module is not None:
        found = _lookup_in_module(module, target, file_symbols, file_paths)
        if found is not None:
            return found
        return None

    # 5) 同文件类静态调用：Config.load()
    cls_hits = [s for s in local if s.name == base and s.kind == SymbolKind.CLASS]
    if len(cls_hits) == 1:
        methods = [
            s for s in local
            if s.parent_qualified == cls_hits[0].qualified_name and s.name == target
        ]
        if len(methods) == 1:
            return methods[0]
    return None


def _resolve_name(
    name: str,
    file: str,
    scope: FileImportScope,
    file_symbols: dict[str, list[SymbolAtom]],
    file_paths: frozenset[str],
) -> SymbolAtom | None:
    """类型注解/抛出的名字解析：同文件符号优先，其次 from-import 符号。"""
    local = file_symbols.get(file, ())
    hits = [s for s in local if s.name == name]
    if len(hits) == 1:
        return hits[0]
    module = dict(scope.symbol_imports).get(name)
    return _lookup_in_module(module, name, file_symbols, file_paths)


def _expand_module(base: str, aliases: dict[str, str]) -> str | None:
    """把调用基名展开为模块点路径（只接受 import 保证可引用的形式）。

    import a.b as c;   c.foo()        → "a.b"
    import a.b;        a.foo()        → "a.b"
    import a.b;        a.b.foo()      → "a.b"
    import a.b;        a.x.foo()      → None（x 不是 b，不匹配）
    import a.b;        a.b.c.foo()    → None（超出 import 保证的深度，不猜）
    """
    parts = base.split(".")
    module = aliases.get(parts[0])
    if module is None:
        return None
    module_parts = module.split(".")
    if len(parts) > len(module_parts):
        return None
    if parts[1:] == module_parts[1:][: len(parts) - 1]:
        return module
    return None


def _lookup_in_module(
    module: str | None,
    name: str,
    file_symbols: dict[str, list[SymbolAtom]],
    file_paths: frozenset[str],
) -> SymbolAtom | None:
    """在模块对应文件的符号里按名字查找，恰好 1 个命中才返回。"""
    if not module:
        return None
    file = module_to_file(module, file_paths)
    if file is None:
        return None
    hits = [s for s in file_symbols.get(file, ()) if s.name == name]
    return hits[0] if len(hits) == 1 else None


# ── helpers ─────────────────────────────────────────────────────────────


def _rel_id(
    source_atom_id: str, kind: str, target: str, file_path: str, line: int
) -> str:
    """确定性关系 ID——同 (源, 类型, 目标, 文件, 行) 幂等，跨解析稳定。"""
    return "rel_" + hashlib.sha256(
        "\0".join([source_atom_id, kind, target, file_path, str(line)]).encode()
    ).hexdigest()[:24]


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


def _finalize(
    rel: Relation,
    *,
    target_atom_id: str | None = None,
    kind: str | None = None,
    category: str,
) -> Relation:
    """按最终字段确定 relation_id 并构造正式 Relation。"""
    final_kind = kind or rel.kind
    target = target_atom_id or rel.target_external or ""
    return Relation(
        relation_id=_rel_id(
            rel.source_atom_id, final_kind, target, rel.file_path, rel.line
        ),
        source_atom_id=rel.source_atom_id,
        kind=final_kind,
        target_atom_id=target_atom_id,
        target_external=None if target_atom_id else rel.target_external,
        category=category,
        file_path=rel.file_path,
        line=rel.line,
    )


def _group_symbols_by_file(catalog: AtomCatalog) -> dict[str, list[SymbolAtom]]:
    by_file: dict[str, list[SymbolAtom]] = defaultdict(list)
    for sym in catalog.symbols:
        by_file[_file_from_key(sym.symbol_key)].append(sym)
    return dict(by_file)


def _parsed_tree(
    file: str,
    parsed: dict[str, ast.Module],
    source_reader: Callable[[str], str | None],
) -> ast.Module | None:
    """读取并缓存文件 AST；不可解析返回 None。"""
    if file in parsed:
        return parsed[file]
    source = source_reader(file)
    if source is None:
        return None
    try:
        tree = ast.parse(source, filename=file)
    except (SyntaxError, ValueError):
        return None
    parsed[file] = tree
    return tree


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


def _dotted_base(node: ast.Attribute) -> str | None:
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
    if parts and parts[0] in _STDLIB_MODULES:
        return True
    if parts and parts[0] in _EXTERNAL_TOP_LEVEL:
        return True
    full = name.lower()
    return any(full.startswith(prefix) for prefix in _EXTERNAL_PREFIXES)


def _file_from_key(symbol_key: str) -> str:
    parts = symbol_key.split(":", 2)
    return parts[1] if len(parts) > 1 else ""
