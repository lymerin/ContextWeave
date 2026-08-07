"""AST 结构解析——第 1 层处理器。

输入  SourceFileBatch + 源码读取函数。
输出  AtomCatalog（所有文件的 ModuleAtom + SymbolAtom）。

所有符号按 (file_path, start_line) 排序。
语法错误的文件产生一个空的 ModuleAtom，不中断整批解析。

语言实现按语言解析器注册表（parsers 包）分派；未注册语言维持
"仅 ModuleAtom" 回退（Python/Java 等已注册语言见 parsers/）。
"""

from __future__ import annotations

from collections.abc import Callable

from app.schemas.ast_atom import AtomCatalog, ModuleAtom, SymbolAtom
from app.schemas.source_file import SourceFileBatch, SourceFileRef
from app.source_analysis.parsers import (
    get_parser,
    hash_id,
    symbol_file_from_key,
)


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
        language = (_language(ref) or "").lower()
        parser = get_parser(language)
        if parser is not None:
            module, file_symbols = parser.parse_file(
                repository_id=str(batch.repository_id),
                revision=batch.revision,
                file_path=ref.file_path,
                source=source,
            )
        else:
            # 未注册语言不解析符号，只生成 ModuleAtom。
            lines = source.splitlines()
            module = ModuleAtom(
                atom_id=hash_id("mod", batch.revision, ref.file_path),
                file_path=ref.file_path,
                language=ref.language,
                start_line=1,
                end_line=max(1, len(lines)),
            )
            file_symbols = ()
        modules.append(module)
        symbols.extend(file_symbols)

    modules.sort(key=lambda m: m.file_path)
    symbols.sort(key=lambda s: (symbol_file_from_key(s.symbol_key), s.start_line, s.symbol_key))

    return AtomCatalog(
        repository_id=batch.repository_id,
        revision=batch.revision,
        modules=tuple(modules),
        symbols=tuple(symbols),
    )


_EXT_LANG: dict[str, str] = {
    ".py": "Python",
    ".java": "Java",
}


def _language(ref: SourceFileRef) -> str:
    """取语言标识——MCP code.read 的 language 字段可能为 null。
    若缺失则从文件扩展名回退推断。
    """
    if ref.language:
        return ref.language
    path = ref.file_path.lower()
    for ext, lang in _EXT_LANG.items():
        if path.endswith(ext):
            return lang
    return ""
