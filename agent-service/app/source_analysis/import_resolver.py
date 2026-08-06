"""文件级 import 解析——模块别名表构建（第 2a 层输入准备）。

输入  模块 AST + 文件路径 + 仓库文件集。
输出  FileImportScope：一个文件的模块别名表与符号导入表。

解析原则（确定性铁律）:
  - 解析到 0 个或 ≥2 个候选 → 不解析（宁缺勿错），绝不猜测。
  - module → 文件映射只收恰好 1 个候选（.py 与 __init__.py 同时存在即歧义）。
  - 相对导入按导入方文件目录机械换算，超出仓库根 → 不解析。
"""

from __future__ import annotations

import ast
from dataclasses import dataclass
from pathlib import PurePosixPath


@dataclass(frozen=True)
class FileImportScope:
    """一个文件的 import 事实（全部确定性解析）。

    module_aliases  别名 → 仓库内模块点路径（模块级绑定）。
                    import a.b as c           → ("c", "a.b")
                    import a.b                → ("a", "a.b")
                    from . import service     → ("service", "app.runtime.service")（子模块命中）
                    from app.x import y       → ("y", "app.x.y")（子模块命中）
    symbol_imports  名字 → 源模块点路径（符号级导入，from a.b import Y 且 Y 非子模块）
    star_imports    from a.b import * 的源模块（不可定向解析，仅记录）
    """

    file_path: str
    module_aliases: tuple[tuple[str, str], ...] = ()
    symbol_imports: tuple[tuple[str, str], ...] = ()
    star_imports: tuple[str, ...] = ()


def module_to_file(module: str, file_paths: frozenset[str]) -> str | None:
    """仓库内模块点路径 → 唯一文件。

    "app.schemas.unit_plans" → "app/schemas/unit_plans.py"
    "app.services"           → "app/services.py" 或 "app/services/__init__.py"（二选一，取唯一解）
    候选多于 1 个 → None（宁缺勿错）。
    """
    rel = module.replace(".", "/")
    candidates = (f"{rel}.py", f"{rel}/__init__.py")
    hits = [c for c in candidates if c in file_paths]
    return hits[0] if len(hits) == 1 else None


def resolve_relative_module(
    module: str, level: int, importer_path: str
) -> str | None:
    """把相对导入解析为仓库内模块点路径。

    from .services import x   (app/runtime/foo.py, level=1) → "app.runtime.services"
    from .. import x          (app/runtime/foo.py, level=2) → "app"
    from . import x           (app/runtime/foo.py, level=1) → "app.runtime"

    level <= 0（绝对导入）→ 原样返回；上溯超出仓库根 → None。
    """
    if level <= 0:
        return module or None
    parts = PurePosixPath(importer_path).parts[:-1]  # 去掉文件名
    if level - 1 > len(parts):
        return None
    package = parts[: len(parts) - (level - 1)]
    base = ".".join(package)
    if module:
        return f"{base}.{module}" if base else module
    return base or None


def build_file_scope(
    file_path: str, tree: ast.Module, file_paths: frozenset[str]
) -> FileImportScope:
    """从模块 AST 构建 FileImportScope。

    对每个 from-import 名字先按"子模块"尝试解析：
      from app.x import y → 若 app/x/y.py 或 app/x/y/__init__.py 存在 → 模块别名
      from . import svc   → 若 当前包/svc.py 存在 → 模块别名
    子模块解析失败 → 按符号导入记录（from a.b import Y → ("Y", "a.b")）。
    """
    module_aliases: list[tuple[str, str]] = []
    symbol_imports: list[tuple[str, str]] = []
    star_imports: list[str] = []

    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            for alias in node.names:
                name = alias.asname or alias.name.split(".")[0]
                module_aliases.append((name, alias.name))
        elif isinstance(node, ast.ImportFrom):
            module = (
                resolve_relative_module(node.module or "", node.level, file_path)
                if node.level > 0
                else (node.module or "")
            )
            if module is None:
                continue
            for alias in node.names:
                if alias.name == "*":
                    star_imports.append(module)
                    continue
                name = alias.asname or alias.name
                submodule = f"{module}.{alias.name}"
                if module_to_file(submodule, file_paths) is not None:
                    module_aliases.append((name, submodule))
                else:
                    symbol_imports.append((name, module))

    return FileImportScope(
        file_path=file_path,
        module_aliases=tuple(sorted(module_aliases)),
        symbol_imports=tuple(sorted(symbol_imports)),
        star_imports=tuple(sorted(star_imports)),
    )
