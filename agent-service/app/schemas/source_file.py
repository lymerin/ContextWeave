"""候选源码文件模型——第0层产出。

SourceFileRef  一个候选源文件的最小标识。
SourceFileBatch 一批经过筛选的候选源文件。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from uuid import UUID


@dataclass(frozen=True)
class SourceFileRef:
    """一个候选源文件的标识。

    file_path  仓库相对路径（POSIX 风格）。
    language   编程语言（Python / Java / TypeScript / ...）。
    size_bytes 文件大小（字节）。
    """

    file_path: str
    language: str
    size_bytes: int


@dataclass(frozen=True)
class SourceFileBatch:
    """一次文件粗筛的产出。

    repository_id  仓库 UUID。
    revision       固定的 git commit hash。
    files          通过筛选的候选源文件，按 file_path 排序。
    skipped_count  被排除的文件数量。
    total_count    原始文件总数。
    """

    repository_id: UUID
    revision: str
    files: tuple[SourceFileRef, ...] = ()
    skipped_count: int = 0
    total_count: int = 0
    truncated: bool = False
    truncated_at: int | None = None
