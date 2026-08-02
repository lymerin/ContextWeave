"""候选源码筛选——第0层唯一职责。

输入  仓库文件列表（MCP repository.list_files 返回的条目）。
输出  SourceFileBatch（已筛选、去重、排序的候选源码文件）。

筛选规则（纯程序，不调 DeepSeek）：
  1. 排除二进制 / 图片 / 字体
  2. 排除第三方目录（node_modules / .venv / vendor / ...）
  3. 排除生成代码（generated / *-generated 目录）
  4. 排除测试文件（test_ / _test / tests/ / spec/ / __test__/）
  5. 排除构建产物（dist / build / target / __pycache__）
  6. 排除配置文件（.json / .yaml / .toml / .ini / ...）
  7. 排除超大文件（超过 max_size_bytes）
  8. 排除迁移脚本、CI 脚本、部署脚本
  9. 仅保留可读的编程语言源码
 10. 去重（同 file_path 只保留一次）
 11. 按 file_path 排序
"""

from __future__ import annotations

from pathlib import PurePosixPath
from typing import Any
from uuid import UUID

from app.schemas.source_file import SourceFileBatch, SourceFileRef

# ── 扩展名分类 ──────────────────────────────────────────────────────────────

CODE_EXTENSIONS: dict[str, str] = {
    ".py": "Python",
    ".java": "Java",
    ".kt": "Kotlin",
    ".kts": "Kotlin",
    ".ts": "TypeScript",
    ".tsx": "TypeScript",
    ".js": "JavaScript",
    ".jsx": "JavaScript",
    ".vue": "Vue",
    ".go": "Go",
    ".rs": "Rust",
}

TEXT_NON_CODE: frozenset[str] = frozenset({
    ".md", ".txt", ".json", ".yaml", ".yml", ".xml",
    ".sql", ".toml", ".ini", ".conf", ".properties", ".lock",
    ".cfg", ".env",
})

BINARY: frozenset[str] = frozenset({
    ".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico",
    ".pdf", ".zip", ".jar", ".war", ".class", ".exe", ".dll",
    ".so", ".bin", ".woff", ".woff2", ".pyc", ".pyo",
})

# ── 目录排除 ────────────────────────────────────────────────────────────────

SKIPPED_DIRS: frozenset[str] = frozenset({
    ".git", "node_modules", "vendor", "dist", "build", "target",
    "coverage", ".idea", ".vscode", "__pycache__", ".venv",
    "generated", "logs", ".data", "venv", ".tox", ".eggs",
})

TEST_DIRS: frozenset[str] = frozenset({
    "tests", "test", "spec", "__test__", "testing",
})

# ── 文件级排除模式 ──────────────────────────────────────────────────────────

TEST_FILE_PATTERNS = ("test_", "_test.", "test.", "spec_", "_spec.")
MIGRATION_PATTERNS = ("/migrations/", "/alembic/", "/flyway/")
CONFIG_FILE_NAMES: frozenset[str] = frozenset({
    "setup.py", "setup.cfg", "conftest.py", "noxfile.py",
})

# ── 筛选器 ──────────────────────────────────────────────────────────────────


class SourceFileFilter:
    """仓库文件 → 候选源码文件。

    usage:
        filt = SourceFileFilter(max_size_bytes=200_000, max_file_count=500)
        batch = filt.filter(repository_id, revision, file_entries)
    """

    def __init__(
        self,
        *,
        max_size_bytes: int = 200_000,
        max_file_count: int = 500,
    ) -> None:
        self._max_size = max_size_bytes
        self._max_count = max_file_count

    def filter(
        self,
        repository_id: UUID,
        revision: str,
        entries: list[dict[str, Any]],
    ) -> SourceFileBatch:
        """对 MCP 返回的文件条目执行粗筛。

        entries 的每一项应包含:
          filePath, sizeBytes, readable, binaryFile, language（可选）
        """
        total = len(entries)
        kept: list[SourceFileRef] = []
        seen: set[str] = set()
        skipped = 0
        truncated = False
        truncated_at = None

        for entry in entries:
            path = str(entry.get("filePath", "")).replace("\\", "/")
            pure = PurePosixPath(path)
            ext = pure.suffix.lower()
            name = pure.name.lower()
            parts_lower = {p.lower() for p in pure.parts[:-1]}
            size = max(0, int(entry.get("sizeBytes") or 0))
            language = (
                entry.get("language")
                or CODE_EXTENSIONS.get(ext)
                or ""
            )

            # 去重
            if path in seen:
                skipped += 1
                continue
            seen.add(path)

            # 目录排除: 第三方 + 构建产物 + 生成代码
            if parts_lower & SKIPPED_DIRS:
                skipped += 1
                continue

            # 目录排除: 测试
            if parts_lower & TEST_DIRS:
                skipped += 1
                continue

            # 文件排除: 测试命名
            if any(pattern in name for pattern in TEST_FILE_PATTERNS):
                skipped += 1
                continue

            # 文件排除: 迁移脚本
            if any(pattern in path for pattern in MIGRATION_PATTERNS):
                skipped += 1
                continue

            # 文件排除: 特定配置文件名
            if name in CONFIG_FILE_NAMES:
                skipped += 1
                continue

            # 二进制
            if (
                bool(entry.get("binaryFile"))
                or entry.get("readable") is False
                or ext in BINARY
            ):
                skipped += 1
                continue

            # 文本非代码
            if ext in TEXT_NON_CODE:
                skipped += 1
                continue

            # 非代码扩展名
            if ext not in CODE_EXTENSIONS:
                skipped += 1
                continue

            # 超大文件
            if size > self._max_size:
                skipped += 1
                continue

            kept.append(SourceFileRef(path, language, size))

        # 排序 + 截断
        kept.sort(key=lambda r: r.file_path)
        if len(kept) > self._max_count:
            truncated = True
            truncated_at = self._max_count
            kept = kept[: self._max_count]

        return SourceFileBatch(
            repository_id=repository_id,
            revision=revision,
            files=tuple(kept),
            skipped_count=skipped,
            total_count=total,
            truncated=truncated,
            truncated_at=truncated_at,
        )
