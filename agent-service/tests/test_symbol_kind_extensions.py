"""S2 模型扩展：SymbolKind 新增 kind、CLASS_LIKE_KINDS、access_level、is_private_symbol。"""

from __future__ import annotations

from app.schemas.ast_atom import (
    CLASS_LIKE_KINDS,
    SymbolAtom,
    SymbolKind,
    is_private_symbol,
)


def _sym(name: str = "Thing", *, kind: str = "CLASS", access_level: str = "") -> SymbolAtom:
    return SymbolAtom(
        atom_id="sym_x",
        symbol_key=f"JAVA:src/Thing.java:{name}:{kind}",
        kind=kind,
        name=name,
        qualified_name=name,
        signature="",
        access_level=access_level,
    )


def test_class_like_kinds_include_new_kinds() -> None:
    assert SymbolKind.INTERFACE == "INTERFACE"
    assert SymbolKind.ENUM == "ENUM"
    assert SymbolKind.RECORD == "RECORD"
    assert SymbolKind.CLASS in CLASS_LIKE_KINDS
    assert SymbolKind.INTERFACE in CLASS_LIKE_KINDS
    assert SymbolKind.ENUM in CLASS_LIKE_KINDS
    assert SymbolKind.RECORD in CLASS_LIKE_KINDS
    # 函数/方法不是类语义
    assert SymbolKind.FUNCTION not in CLASS_LIKE_KINDS
    assert SymbolKind.METHOD not in CLASS_LIKE_KINDS


def test_access_level_defaults_to_empty_string() -> None:
    sym = _sym("Thing")
    assert sym.access_level == ""
    # Python 路径无访问级别：走命名约定
    assert is_private_symbol(_sym("_helper")) is True
    assert is_private_symbol(_sym("helper")) is False
    # dunder 开头不算私有（与 Python 语义一致）
    assert is_private_symbol(_sym("__init__")) is False


def test_explicit_access_level_overrides_naming_convention() -> None:
    # 显式声明优先于命名约定
    assert is_private_symbol(_sym("_odd_name", access_level="public")) is False
    assert is_private_symbol(_sym("helper", access_level="private")) is True
    # 四态：仅 private 判定为私有
    assert is_private_symbol(_sym("x", access_level="protected")) is False
    assert is_private_symbol(_sym("x", access_level="package")) is False
    assert is_private_symbol(_sym("x", access_level="public")) is False


def test_access_level_works_across_kinds() -> None:
    for kind in (SymbolKind.CLASS, SymbolKind.INTERFACE, SymbolKind.ENUM, SymbolKind.RECORD):
        assert is_private_symbol(_sym("Nested", kind=kind, access_level="private")) is True
        assert is_private_symbol(_sym("Nested", kind=kind, access_level="public")) is False
