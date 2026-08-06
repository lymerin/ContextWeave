from __future__ import annotations

import ast

from app.source_analysis.import_resolver import (
    build_file_scope,
    module_to_file,
    resolve_relative_module,
)


def scope_for(source: str, file_path: str, file_paths: frozenset[str]):
    return build_file_scope(file_path, ast.parse(source), file_paths)


# ── module_to_file ───────────────────────────────────────────────────────


def test_module_to_file_single_candidate() -> None:
    paths = frozenset({"app/schemas/unit_plans.py", "app/other.py"})
    assert module_to_file("app.schemas.unit_plans", paths) == "app/schemas/unit_plans.py"


def test_module_to_file_package_init() -> None:
    paths = frozenset({"app/services/__init__.py"})
    assert module_to_file("app.services", paths) == "app/services/__init__.py"


def test_module_to_file_ambiguous_is_none() -> None:
    paths = frozenset({"app/x.py", "app/x/__init__.py"})
    assert module_to_file("app.x", paths) is None


def test_module_to_file_missing_is_none() -> None:
    paths = frozenset({"a.py"})
    assert module_to_file("b", paths) is None


# ── resolve_relative_module ──────────────────────────────────────────────


def test_relative_level_one() -> None:
    assert resolve_relative_module("services", 1, "app/runtime/foo.py") == "app.runtime.services"


def test_relative_level_two() -> None:
    assert resolve_relative_module("", 2, "app/runtime/foo.py") == "app"


def test_relative_level_three_to_root() -> None:
    assert resolve_relative_module("x", 3, "app/runtime/foo.py") == "x"


def test_relative_beyond_root_is_none() -> None:
    assert resolve_relative_module("x", 4, "app/runtime/foo.py") is None


def test_absolute_import_passthrough() -> None:
    assert resolve_relative_module("a.b", 0, "app/runtime/foo.py") == "a.b"


# ── build_file_scope ─────────────────────────────────────────────────────


def test_import_as_creates_alias() -> None:
    scope = scope_for(
        "import app.schemas as s\n",
        "app/main.py",
        frozenset({"app/schemas/unit_plans.py"}),
    )
    assert ("s", "app.schemas") in scope.module_aliases


def test_plain_import_binds_first_segment() -> None:
    scope = scope_for(
        "import app.schemas.unit_plans\n",
        "app/main.py",
        frozenset({"app/schemas/unit_plans.py"}),
    )
    assert ("app", "app.schemas.unit_plans") in scope.module_aliases


def test_relative_submodule_import_is_module_alias() -> None:
    scope = scope_for(
        "from . import service\n",
        "app/runtime/foo.py",
        frozenset({"app/runtime/service.py"}),
    )
    assert ("service", "app.runtime.service") in scope.module_aliases


def test_from_module_import_submodule_is_module_alias() -> None:
    scope = scope_for(
        "from app.runtime import semantic_planner\n",
        "app/main.py",
        frozenset({"app/runtime/semantic_planner.py"}),
    )
    assert ("semantic_planner", "app.runtime.semantic_planner") in scope.module_aliases


def test_from_module_import_symbol_is_symbol_import() -> None:
    scope = scope_for(
        "from .domain import ReviewResult\n",
        "app/runtime/foo.py",
        frozenset({"app/runtime/domain.py"}),
    )
    assert ("ReviewResult", "app.runtime.domain") in scope.symbol_imports


def test_from_import_as_alias() -> None:
    scope = scope_for(
        "from .domain import ReviewResult as R\n",
        "app/runtime/foo.py",
        frozenset({"app/runtime/domain.py"}),
    )
    assert ("R", "app.runtime.domain") in scope.symbol_imports


def test_external_from_import_is_symbol_import() -> None:
    scope = scope_for(
        "from fastapi import APIRouter\n",
        "app/main.py",
        frozenset({"app/main.py"}),
    )
    assert ("APIRouter", "fastapi") in scope.symbol_imports


def test_star_import_recorded() -> None:
    scope = scope_for(
        "from app.shared import *\n",
        "app/main.py",
        frozenset({"app/shared.py"}),
    )
    assert ("app.shared",) == scope.star_imports
    assert not scope.module_aliases
    assert not scope.symbol_imports


def test_scope_output_sorted_and_deterministic() -> None:
    source = "import b\nimport a\nfrom .x import y\n"
    paths = frozenset({"app/runtime/a.py", "app/runtime/x.py"})
    first = scope_for(source, "app/runtime/a.py", paths)
    second = scope_for(source, "app/runtime/a.py", paths)
    assert first == second
    assert first.module_aliases == tuple(sorted(first.module_aliases))
    assert first.symbol_imports == tuple(sorted(first.symbol_imports))
