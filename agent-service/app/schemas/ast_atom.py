"""AST 原子模型——第 1 层产出。

ModuleAtom   源码文件的顶层容器。
SymbolAtom   一个类或函数的不可变快照。
AtomCatalog  一次全量 AST 解析的完整原子目录。
"""

from __future__ import annotations

from dataclasses import dataclass
from uuid import UUID


# ── 符号种类 ────────────────────────────────────────────────────────────────


class SymbolKind:
    MODULE = "MODULE"
    CLASS = "CLASS"
    FUNCTION = "FUNCTION"
    ASYNC_FUNCTION = "ASYNC_FUNCTION"
    METHOD = "METHOD"
    CLASS_METHOD = "CLASS_METHOD"


# ── 模块原子 ────────────────────────────────────────────────────────────────


@dataclass(frozen=True)
class ModuleAtom:
    """源码文件的顶层容器。

    file_path      仓库相对路径（POSIX 风格）。
    language       编程语言。
    start_line     固定为 1。
    end_line       源码总行数。
    docstring      模块顶部三引号字符串，没有则为 None。
    """

    atom_id: str
    file_path: str
    language: str
    start_line: int
    end_line: int
    docstring: str | None = None


# ── 符号原子 ────────────────────────────────────────────────────────────────


@dataclass(frozen=True)
class SymbolAtom:
    """一个类或函数的不可变结构快照——纯 AST 事实，不含任何关系字段。

    ── 身份 ──
    atom_id          sha256(repo+rev+path+kind+qualified_name) 前 24 位十六进制。
    symbol_key       跨文件绑定用的稳定标识（PYTHON:path:name:KIND）。

    ── 命名 ──
    kind             CLASS / FUNCTION / ASYNC_FUNCTION / METHOD / CLASS_METHOD。
    name             简短名（不含模块路径）。
    qualified_name   全限定名（如 UserService.create_user）。

    ── 位置 ──
    signature        源码签名文本（"def create_user(self, name: str) -> User"）。
    start_line       声明起始行（含装饰器），1-based。
    end_line         声明结束行（含函数体末尾），1-based。
    body_start_line  函数体第一行（排除装饰器、签名行、docstring）。
    body_end_line    函数体最后一行（排除内部嵌套定义）。

    ── 附加 ──
    decorator_names  装饰器名字列表（如 ["router.post", "classmethod"]）。
    docstring        函数或类的 docstring 原文，没有则为 None。
    parent_qualified 所属类的 qualified_name（仅 METHOD / CLASS_METHOD）。
    is_async         是否 async def。
    is_pydantic      是否继承 BaseModel（仅 CLASS）。

    ── 路由（仅 HTTP_ROUTE 转换后填写）──
    http_method      路由方法（GET / POST / PUT / …）。
    http_path        路由路径（/users）。
    http_response    响应模型类型名。
    """

    atom_id: str
    symbol_key: str
    kind: str
    name: str
    qualified_name: str
    signature: str
    start_line: int = 0
    end_line: int = 0
    body_start_line: int = 0
    body_end_line: int = 0
    decorator_names: tuple[str, ...] = ()
    docstring: str | None = None
    parent_qualified: str | None = None
    is_async: bool = False
    is_pydantic: bool = False
    http_method: str | None = None
    http_path: str | None = None
    http_response: str | None = None


# ── 原子目录 ────────────────────────────────────────────────────────────────


@dataclass(frozen=True)
class AtomCatalog:
    """一次全量 AST 解析的原子目录。

    repository_id  仓库 UUID。
    revision       解析时的 git commit hash。
    modules        所有被解析文件的模块原子，按 file_path 排序。
    symbols        所有类/函数/方法原子，按 (file_path, start_line) 排序。
    """

    repository_id: UUID
    revision: str
    modules: tuple[ModuleAtom, ...] = ()
    symbols: tuple[SymbolAtom, ...] = ()

    @property
    def total_files(self) -> int:
        return len(self.modules)

    @property
    def total_symbols(self) -> int:
        return len(self.symbols)

    @property
    def error_count(self) -> int:
        return sum(1 for m in self.modules if not m.atom_id)
