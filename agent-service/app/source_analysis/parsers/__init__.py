"""语言解析器注册表——AST 解析层按语言分派。

第 1 层（code_ast_atom.parse_batch）与第 2a 层（atom_relation_graph.build_graph）
通过 get_parser(language) 获取 LanguageParser 实现；未注册语言回退
"仅 ModuleAtom" 路径，管线不中断。

注册表只依赖 schemas/import_resolver，不反向依赖调用方，避免循环导入。
"""

from __future__ import annotations

import hashlib
from typing import Any, Protocol

from app.schemas.ast_atom import ModuleAtom, SymbolAtom
from app.schemas.repository_graph import Relation
from app.source_analysis.import_resolver import FileImportScope

# ── 共享助手 ───────────────────────────────────────────────────────────────


def hash_id(prefix: str, *parts: str) -> str:
    """确定性 ID——同 (前缀, 部分) 幂等，跨解析稳定。"""
    return prefix + "_" + hashlib.sha256(
        "\0".join(parts).encode()
    ).hexdigest()[:24]


def symbol_key(language: str, file_path: str, qualified: str, kind: str) -> str:
    """symbol_key 格式：LANG:file_path:qualified_name:KIND。"""
    return f"{language}:{file_path}:{qualified}:{kind}"


def symbol_file_from_key(symbol_key: str) -> str:
    """从 symbol_key 提取 file_path（下游统一按 split(":", 2) 消费）。"""
    parts = symbol_key.split(":", 2)
    return parts[1] if len(parts) > 1 else ""


def symbol_language_from_key(symbol_key: str) -> str:
    """从 symbol_key 提取语言标识（小写，如 "python"）。"""
    return symbol_key.split(":", 1)[0].lower() if ":" in symbol_key else ""


# ── LanguageParser 协议 ────────────────────────────────────────────────────


class LanguageParser(Protocol):
    """AST 解析 + 关系收集 + import 作用域的语言实现协议。

    实现约定：
      - parse_file / parse_source 对语法错误永不抛异常（降级为空结果 / None）；
      - extract_relations 返回的 Relation.target_external 是待解析临时键，
        由 build_graph 统一分类（INTERNAL/BOUNDARY/UNRESOLVED）。
    """

    language: str

    def parse_file(
        self,
        *,
        repository_id: str,
        revision: str,
        file_path: str,
        source: str,
    ) -> tuple[ModuleAtom, tuple[SymbolAtom, ...]]:
        """源码 → (ModuleAtom, SymbolAtom[])。语法错误降级为空 ModuleAtom。"""
        ...

    def parse_source(self, source: str) -> Any:
        """源码 → 解析树；解析失败返回 None（不抛异常）。"""
        ...

    def extract_relations(
        self, sym: SymbolAtom, tree: Any, file: str
    ) -> list[Relation]:
        """遍历符号对应节点，收集原始关系。"""
        ...

    def build_import_scope(
        self, file: str, tree: Any, file_paths: frozenset[str]
    ) -> FileImportScope:
        """文件 import 事实表。"""
        ...

    def is_external_name(self, target: str) -> bool:
        """BOUNDARY 判定：名字属于外部/框架时返回 True。"""
        ...


# ── 注册表 ─────────────────────────────────────────────────────────────────


_REGISTRY: dict[str, LanguageParser] = {}


def register(parser: LanguageParser) -> None:
    _REGISTRY[parser.language] = parser


def get_parser(language: str) -> LanguageParser | None:
    """按语言取解析器；未注册语言返回 None（调用方回退 ModuleAtom-only）。"""
    return _REGISTRY.get((language or "").lower())


def _bootstrap() -> None:
    from app.source_analysis.parsers.python_parser import PARSER

    register(PARSER)
    try:
        from app.source_analysis.parsers.java_parser import PARSER as JAVA_PARSER

        register(JAVA_PARSER)
    except ImportError:
        # tree-sitter 未安装：Java 回退 ModuleAtom-only，管线不中断
        pass


_bootstrap()
