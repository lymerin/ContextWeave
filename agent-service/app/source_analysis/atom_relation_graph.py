"""仓库关系图构建——第 2a 层处理器。

输入  AtomCatalog + 源码读取函数。
输出  RepositoryCodeGraph（AtomCatalog + 完整关系图 + 正向/反向索引）。

解析原则:
  - 跨文件调用/类型引用通过 import 别名表确定性解析（import_resolver）。
  - 符号查找按文件作用域，杜绝跨文件同名符号碰撞。
  - 解析到 0 或 ≥2 个候选 → UNRESOLVED，禁止猜测；动态调用（非 Name/Attribute 基底）→ UNRESOLVED。
  - relation_id 是 (源原子, 类型, 目标, 文件, 行) 的确定性哈希，同 revision 内稳定。
  - 调用目标解析为 CLASS 原子 → 升级为 CREATES（实例化），其余 → CALLS。
  - 语言相关逻辑（AST 遍历、关系收集、外部名判定）按语言解析器分派（parsers 包）。
"""

from __future__ import annotations

import hashlib
from collections import defaultdict
from collections.abc import Callable
from typing import Any

from app.schemas.ast_atom import CLASS_LIKE_KINDS, AtomCatalog, SymbolAtom, SymbolKind
from app.schemas.repository_graph import (
    Relation,
    RelationCategory,
    RelationKind,
    RepositoryCodeGraph,
)
from app.source_analysis.import_resolver import (
    FileImportScope,
    java_module_to_file,
    module_to_file,
)
from app.source_analysis.parsers import (
    LanguageParser,
    get_parser,
    symbol_file_from_key,
    symbol_language_from_key,
)


def build_graph(
    catalog: AtomCatalog,
    source_reader: Callable[[str], str | None],
) -> RepositoryCodeGraph:
    """AtomCatalog → RepositoryCodeGraph（import 感知、按文件作用域解析）。"""

    file_symbols = _group_symbols_by_file(catalog)
    file_paths = frozenset(m.file_path for m in catalog.modules)

    relations: list[Relation] = []
    parsed: dict[tuple[str, str], Any] = {}
    scopes: dict[str, FileImportScope] = {}
    parsers_by_file: dict[str, LanguageParser] = {}
    trees_by_file: dict[str, Any] = {}

    for sym in catalog.symbols:
        file = symbol_file_from_key(sym.symbol_key)
        if not file:
            continue
        lang = symbol_language_from_key(sym.symbol_key)
        parser = get_parser(lang)
        if parser is None:
            continue
        parsers_by_file[file] = parser
        tree = _parsed_tree(file, lang, parsed, parser, source_reader)
        if tree is None:
            continue
        trees_by_file[file] = tree
        if file not in scopes:
            scopes[file] = parser.build_import_scope(file, tree, file_paths)
        relations.extend(parser.extract_relations(sym, tree, file))

    relations.extend(_collect_contains(catalog))

    # 分类: INTERNAL / BOUNDARY / UNRESOLVED
    internal: list[Relation] = []
    boundary: list[Relation] = []
    unresolved: list[Relation] = []
    by_atom_id = {s.atom_id: s for s in catalog.symbols}

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
            rel,
            scopes.get(rel.file_path),
            file_symbols,
            file_paths,
            source_sym=by_atom_id.get(rel.source_atom_id),
            tree=trees_by_file.get(rel.file_path),
            parser=parsers_by_file.get(rel.file_path),
        )
        if target is not None:
            kind = rel.kind
            if rel.kind == RelationKind.CALLS and target.kind in CLASS_LIKE_KINDS:
                kind = RelationKind.CREATES
            internal.append(
                _finalize(
                    rel,
                    target_atom_id=target.atom_id,
                    kind=kind,
                    category=RelationCategory.INTERNAL,
                )
            )
        elif _is_external(
            rel.target_external or "", parsers_by_file.get(rel.file_path)
        ):
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


def _collect_contains(catalog: AtomCatalog) -> list[Relation]:
    """类 → 其方法（CONTAINS）。"""
    result: list[Relation] = []
    for sym in catalog.symbols:
        if sym.kind not in CLASS_LIKE_KINDS:
            continue
        for child in catalog.symbols:
            if child.parent_qualified == sym.qualified_name:
                file = symbol_file_from_key(child.symbol_key)
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
    *,
    source_sym: SymbolAtom | None = None,
    tree: Any = None,
    parser: LanguageParser | None = None,
) -> SymbolAtom | None:
    """把关系的临时键解析为仓库内符号原子；解析不到返回 None（交由外部/未解析分类）。"""
    if scope is None:
        return None
    name = rel.target_external or ""
    if not name:
        return None
    is_java = (
        source_sym is not None
        and symbol_language_from_key(source_sym.symbol_key) == "java"
    )
    if rel.kind in (RelationKind.CALLS, RelationKind.CREATES):
        base, target = name.rsplit(".", 1) if "." in name else (None, name)
        if is_java:
            return _resolve_java_call(
                base, target, rel.file_path, scope, file_symbols, file_paths,
                tree, parser,
            )
        return _resolve_call(base, target, rel.file_path, scope, file_symbols, file_paths)
    if is_java:
        return _resolve_java_type(name, rel.file_path, scope, file_symbols, file_paths)
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


# ── Java 目标解析（this/字段类型/静态类型，宁缺勿错）────────────────────────


def _resolve_java_call(
    base: str | None,
    target: str,
    file: str,
    scope: FileImportScope,
    file_symbols: dict[str, list[SymbolAtom]],
    file_paths: frozenset[str],
    tree: Any,
    parser: LanguageParser | None,
) -> SymbolAtom | None:
    """Java 调用目标解析：

      - this / 裸调用 → 同文件方法（new X() 同路径命中类 → CREATES 升级）；
      - 字段类型：base 是字段名 → 声明类型 → 跨文件方法；
      - 静态调用：base 本身是类型名（本文件 / import / 同包）→ 成员方法。
    候选 0 个或 ≥2 个 → None。
    """
    if not target:
        return None
    local = file_symbols.get(file, ())
    if base is None or base == "this":
        hits = [
            s for s in local
            if s.name == target
            and (s.kind == SymbolKind.METHOD or s.kind in CLASS_LIKE_KINDS)
        ]
        if len(hits) == 1:
            return hits[0]
        # new 导入类型 → 跨文件类符号（build_graph 升级为 CREATES）
        return _resolve_java_type(target, file, scope, file_symbols, file_paths)

    field_type = _java_field_type(base, tree, parser)
    if field_type:
        owner = _resolve_java_type(field_type, file, scope, file_symbols, file_paths)
        if owner is not None:
            member = _java_member(owner, target, file_symbols)
            if member is not None:
                return member
    # 静态调用：base 是类型名（Java 变量优先于类型，故字段路径先行）
    owner = _resolve_java_type(base, file, scope, file_symbols, file_paths)
    if owner is not None:
        return _java_member(owner, target, file_symbols)
    return None


def _resolve_java_type(
    name: str,
    file: str,
    scope: FileImportScope,
    file_symbols: dict[str, list[SymbolAtom]],
    file_paths: frozenset[str],
) -> SymbolAtom | None:
    """Java 类型名解析：本文件 → import 全限定名 → 同包回退。

    PARAMETER_TYPE/RETURN_TYPE/THROWS/INHERITS/IMPLEMENTS 共用此路径。
    """
    local = file_symbols.get(file, ())
    hits = [s for s in local if s.name == name and s.kind in CLASS_LIKE_KINDS]
    if len(hits) == 1:
        return hits[0]
    fq = dict(scope.symbol_imports).get(name)
    if fq:
        found = _java_type_in_file(name, fq, file_symbols, file_paths)
        if found is not None:
            return found
    if scope.package:
        return _java_type_in_file(
            name, f"{scope.package}.{name}", file_symbols, file_paths
        )
    return None


def _java_type_in_file(
    name: str,
    fq: str,
    file_symbols: dict[str, list[SymbolAtom]],
    file_paths: frozenset[str],
) -> SymbolAtom | None:
    """全限定类型名 → 目标文件中的类符号（恰好 1 个命中才返回）。"""
    target_file = java_module_to_file(fq, file_paths)
    if target_file is None:
        return None
    hits = [
        s for s in file_symbols.get(target_file, ())
        if s.name == name and s.kind in CLASS_LIKE_KINDS
    ]
    return hits[0] if len(hits) == 1 else None


def _java_member(
    owner: SymbolAtom,
    target: str,
    file_symbols: dict[str, list[SymbolAtom]],
) -> SymbolAtom | None:
    """owner 类型下的方法成员，恰好 1 个命中才返回。"""
    owner_file = symbol_file_from_key(owner.symbol_key)
    hits = [
        s for s in file_symbols.get(owner_file, ())
        if s.parent_qualified == owner.qualified_name
        and s.name == target
        and s.kind == SymbolKind.METHOD
    ]
    return hits[0] if len(hits) == 1 else None


def _java_field_type(
    base: str,
    tree: Any,
    parser: LanguageParser | None,
) -> str | None:
    """字段名 → 声明类型简单名（解析器提供 field_types；无 AST → 不解析）。"""
    if tree is None or parser is None:
        return None
    field_types = getattr(parser, "field_types", None)
    if field_types is None:
        return None
    return field_types(tree).get(base)


# ── helpers ─────────────────────────────────────────────────────────────


def _rel_id(
    source_atom_id: str, kind: str, target: str, file_path: str, line: int
) -> str:
    """确定性关系 ID——同 (源, 类型, 目标, 文件, 行) 幂等，跨解析稳定。"""
    return "rel_" + hashlib.sha256(
        "\0".join([source_atom_id, kind, target, file_path, str(line)]).encode()
    ).hexdigest()[:24]


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
        by_file[symbol_file_from_key(sym.symbol_key)].append(sym)
    return dict(by_file)


def _parsed_tree(
    file: str,
    lang: str,
    parsed: dict[tuple[str, str], Any],
    parser: LanguageParser,
    source_reader: Callable[[str], str | None],
) -> Any:
    """读取并缓存文件 AST；不可解析返回 None。缓存键 (file, lang)。"""
    key = (file, lang)
    if key in parsed:
        return parsed[key]
    source = source_reader(file)
    if source is None:
        return None
    tree = parser.parse_source(source)
    if tree is None:
        return None
    parsed[key] = tree
    return tree


def _is_external(name: str, parser: LanguageParser | None) -> bool:
    """BOUNDARY 判定：按关系所在文件的解析器判定；无解析器 → 不判外部。"""
    if parser is None:
        return False
    return parser.is_external_name(name)
