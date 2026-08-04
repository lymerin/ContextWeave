"""漂移检测服务 —— 在新 revision 下重新解析绑定。

Phase 1: 对每条活跃绑定，在新 revision 的 AtomCatalog 中定位对应符号，
对比 old vs new 以判定漂移级别。

Phase 2 (可选): 对漂移的绑定，沿 RepositoryCodeGraph 反向遍历，
评估对调用方和相关绑定的影响面。

快速路径: 两个 revision 的 structural_fingerprint 相同时，
整个仓库无漂移，跳过详细分析。

用法::

    detector = DriftDetector(old_catalog, new_catalog)
    resolutions = detector.detect(bindings)
    # 可选: 评估影响面
    reports = detector.assess_impact(resolutions, new_graph)
"""

from __future__ import annotations

from app.schemas.ast_atom import (
    AtomCatalog,
    SymbolAtom,
    symbol_key_file_path,
    symbol_key_qualified_name,
)
from app.schemas.drift import BindingReResolution, DriftLevel, DriftReport
from app.schemas.platform_mcp.binding import ExistingBinding
from app.schemas.repository_graph import RepositoryCodeGraph

# ── Public API ────────────────────────────────────────────────────────────────


class DriftDetector:
    """在新 revision 下重新解析绑定以检测漂移。

    构造函数接收新旧两个 AtomCatalog，内部构建 symbol_key 和 qualified_name
    的查找索引以加速解析。
    """

    def __init__(
        self,
        old_catalog: AtomCatalog,
        new_catalog: AtomCatalog,
    ) -> None:
        self._old = old_catalog
        self._new = new_catalog

        # 新 catalog 查找索引
        self._new_by_symbol_key: dict[str, SymbolAtom] = {
            s.symbol_key: s for s in new_catalog.symbols
        }
        self._new_by_qualified: dict[str, list[SymbolAtom]] = {}
        for s in new_catalog.symbols:
            self._new_by_qualified.setdefault(s.qualified_name, []).append(s)

        # 旧 catalog 查找索引
        self._old_by_symbol_key: dict[str, SymbolAtom] = {
            s.symbol_key: s for s in old_catalog.symbols
        }

        # 文件存在集合
        self._old_files: frozenset[str] = frozenset(
            m.file_path for m in old_catalog.modules
        )
        self._new_files: frozenset[str] = frozenset(
            m.file_path for m in new_catalog.modules
        )

    # ── Phase 1: 重新解析绑定 ────────────────────────────────────────────

    def detect(
        self,
        bindings: list[ExistingBinding],
    ) -> list[BindingReResolution]:
        """对每条绑定执行重新解析，返回与输入同序的结果列表。"""
        return [self.re_resolve_binding(b) for b in bindings]

    def re_resolve_binding(
        self,
        binding: ExistingBinding,
    ) -> BindingReResolution:
        """重新解析单条绑定。

        解析策略（按优先级）:
          1. symbol_key 精确匹配 — 最快、最可靠
          2. qualified_name 匹配 — 处理文件移动/重命名
          3. 行范围匹配 — RANGE 级绑定的回退方案
          4. 文件存在检查 — FILE 级绑定的回退方案

        注意: 先尝试符号查找，再判断文件状态。
        符号通过 qualified_name 找到但文件路径不同 → SYMBOL_MOVED
        文件不存在且符号也找不到 → FILE_REMOVED
        """
        # Step 1: 定位旧原子
        old_atom = self._resolve_old_atom(binding)
        old_file_exists = self._file_exists(binding, self._old_files)

        # Step 2: 在新 catalog 中定位符号（优先于文件检查）
        new_atom, method = self._resolve_new_atom(binding, old_atom)

        # Step 3: 检查绑定文件是否仍存在
        new_file_exists = self._file_exists(binding, self._new_files)

        # 文件不存在且符号也定位不到 → FILE_REMOVED
        if not new_file_exists and new_atom is None:
            return BindingReResolution(
                binding_id=binding.binding_id,
                document_id=binding.document_id,
                block_id=binding.block_id,
                old_atom=old_atom,
                old_file_exists=old_file_exists,
                new_atom=None,
                new_file_exists=False,
                resolution_method=method or "file_only",
                drift_level=DriftLevel.FILE_REMOVED,
                drift_detail=_file_removed_detail(binding),
            )

        # Step 4: 判定漂移
        level, detail = classify_drift(
            old_atom=old_atom,
            new_atom=new_atom,
            old_file_exists=old_file_exists,
            new_file_exists=new_file_exists,
        )

        return BindingReResolution(
            binding_id=binding.binding_id,
            document_id=binding.document_id,
            block_id=binding.block_id,
            old_atom=old_atom,
            old_file_exists=old_file_exists,
            new_atom=new_atom,
            new_file_exists=new_file_exists,
            resolution_method=method,
            drift_level=level,
            drift_detail=detail,
        )

    # ── Phase 2: 影响面评估 ──────────────────────────────────────────────

    def assess_impact(
        self,
        resolutions: list[BindingReResolution],
        graph: RepositoryCodeGraph,
        max_depth: int = 2,
    ) -> list[DriftReport]:
        """对漂移的绑定评估调用方影响面。

        沿 code graph 的 reverse_index 做 BFS，找到依赖漂移符号的调用方。
        """
        drifted = [r for r in resolutions if r.drift_level != DriftLevel.NONE]
        if not drifted:
            return [
                DriftReport(resolution=r, recommendation="无漂移，无需操作。")
                for r in resolutions
            ]

        # 收集所有漂移的 old_atom_id
        drifted_atom_ids: set[str] = set()
        for r in drifted:
            if r.old_atom:
                drifted_atom_ids.add(r.old_atom.atom_id)

        # 反向 BFS 查找受影响调用方
        affected_callers = _find_affected_callers(
            drifted_atom_ids, graph, max_depth
        )

        # 构建 graph 上的 symbol_key 查找
        sym_by_atom_id: dict[str, SymbolAtom] = {
            s.atom_id: s for s in graph.catalog.symbols
        }

        reports: list[DriftReport] = []
        for r in resolutions:
            if r.drift_level == DriftLevel.NONE:
                reports.append(DriftReport(
                    resolution=r,
                    recommendation="无漂移，无需操作。",
                ))
            else:
                old_id = r.old_atom.atom_id if r.old_atom else ""
                caller_ids = affected_callers.get(old_id, set())
                caller_keys = tuple(sorted(
                    sym_by_atom_id.get(aid, SymbolAtom(
                        atom_id=aid, symbol_key=aid, kind="", name="",
                        qualified_name="", signature="",
                    )).symbol_key
                    for aid in caller_ids
                ))
                reports.append(DriftReport(
                    resolution=r,
                    affected_caller_keys=caller_keys,
                    recommendation=_build_recommendation(r),
                ))

        return reports

    # ── Internal helpers ──────────────────────────────────────────────────

    def _resolve_old_atom(
        self,
        binding: ExistingBinding,
    ) -> SymbolAtom | None:
        """在旧 catalog 中定位 binding 指向的 SymbolAtom。"""
        if binding.symbol_key:
            atom = self._old_by_symbol_key.get(binding.symbol_key)
            if atom:
                return atom
            # 回退: qualified_name 查找
            qname = symbol_key_qualified_name(binding.symbol_key)
            if qname:
                return self._find_by_qualified_in_catalog(
                    qname, self._old, binding
                )
        return None

    def _resolve_new_atom(
        self,
        binding: ExistingBinding,
        old_atom: SymbolAtom | None,
    ) -> tuple[SymbolAtom | None, str]:
        """在新 catalog 中定位符号。

        返回 (atom_or_none, resolution_method)。
        """
        # 方法 1: symbol_key 精确匹配
        if binding.symbol_key:
            atom = self._new_by_symbol_key.get(binding.symbol_key)
            if atom:
                return atom, "symbol_key"

        # 方法 2: qualified_name 匹配（处理文件移动）
        if old_atom:
            file_path = symbol_key_file_path(old_atom.symbol_key)
            matches = self._new_by_qualified.get(old_atom.qualified_name, [])
            if matches:
                # 优先同文件匹配
                same_file = [
                    a for a in matches
                    if symbol_key_file_path(a.symbol_key) == file_path
                ]
                if same_file:
                    return same_file[0], "qualified_name"
                # 符号可能移动了文件
                return matches[0], "qualified_name"

        # 方法 3: 行范围匹配（RANGE 级绑定的回退）
        if binding.start_line is not None and binding.end_line is not None:
            atom = self._find_by_line_range(binding)
            if atom:
                return atom, "line_range"

        # 方法 4: 纯文件存在检查（FILE 级绑定）
        file_path = _binding_file(binding)
        if file_path and file_path in self._new_files:
            return None, "file_only"

        return None, ""

    def _find_by_line_range(
        self,
        binding: ExistingBinding,
    ) -> SymbolAtom | None:
        """在新 catalog 中按文件 + 行范围查找符号。"""
        file_path = _binding_file(binding)
        if not file_path or binding.start_line is None:
            return None
        for sym in self._new.symbols:
            if symbol_key_file_path(sym.symbol_key) != file_path:
                continue
            if (
                sym.start_line <= binding.start_line
                and sym.end_line >= (binding.end_line or binding.start_line)
            ):
                return sym
        return None

    def _file_exists(
        self,
        binding: ExistingBinding,
        file_set: frozenset[str],
    ) -> bool:
        """检查 binding 指向的文件是否存在于给定文件集中。"""
        file_path = _binding_file(binding)
        if not file_path:
            return True  # 无法判断，保守假设存在
        return file_path in file_set

    def _find_by_qualified_in_catalog(
        self,
        qualified_name: str,
        catalog: AtomCatalog,
        binding: ExistingBinding,
    ) -> SymbolAtom | None:
        """在任意 catalog 中按 qualified_name 查找，优先同文件。"""
        file_path = _binding_file(binding)
        matches = [
            s for s in catalog.symbols
            if s.qualified_name == qualified_name
        ]
        if not matches:
            return None
        same_file = [
            m for m in matches
            if symbol_key_file_path(m.symbol_key) == file_path
        ]
        return same_file[0] if same_file else matches[0]


# ── 漂移分类 ─────────────────────────────────────────────────────────────────


def classify_drift(
    *,
    old_atom: SymbolAtom | None,
    new_atom: SymbolAtom | None,
    old_file_exists: bool = True,
    new_file_exists: bool = True,
) -> tuple[DriftLevel, str]:
    """对比 old_atom 和 new_atom 判定漂移级别。

    返回 (DriftLevel, 人类可读说明)。

    判定表::

        old_atom  new_atom  new_file  →  结果
        ───────── ────────  ────────     ────
        None      None      True         NONE (legacy FILE 绑定，保守不判定)
        None      None      False        FILE_REMOVED
        Some      None      True         SYMBOL_REMOVED
        Some      None      False        FILE_REMOVED
        Some      Some(*)   True         按差异细分
        None      Some      True         NONE (新增符号，无旧绑定可比)
    """
    # 旧原子不存在（legacy FILE/RANGE 绑定）
    if old_atom is None:
        if not new_file_exists:
            return (
                DriftLevel.FILE_REMOVED,
                "绑定文件在新 revision 中不存在。",
            )
        if new_atom is not None:
            return (
                DriftLevel.NONE,
                "检测到新符号，无旧绑定可比对。",
            )
        return (
            DriftLevel.NONE,
            "Legacy 文件级或范围级绑定，无法进行符号级对比，保守判定为无漂移。",
        )

    # 旧原子存在，新原子不存在
    if new_atom is None:
        if not new_file_exists:
            return (
                DriftLevel.FILE_REMOVED,
                "绑定文件在新 revision 中被删除。",
            )
        return (
            DriftLevel.SYMBOL_REMOVED,
            f"符号 '{old_atom.qualified_name}' ({old_atom.kind}) 在 "
            f"{symbol_key_file_path(old_atom.symbol_key)} 中被删除或重命名。",
        )

    # 新旧原子都存在——逐字段对比
    return _compare_symbols(old_atom, new_atom)


def _compare_symbols(
    old: SymbolAtom,
    new: SymbolAtom,
) -> tuple[DriftLevel, str]:
    """逐字段对比两个 SymbolAtom，返回最严重的漂移级别。"""
    old_file = symbol_key_file_path(old.symbol_key)
    new_file = symbol_key_file_path(new.symbol_key)

    # 文件移动
    if old_file != new_file:
        return (
            DriftLevel.SYMBOL_MOVED,
            f"'{old.qualified_name}' 从 {old_file} 移动到 {new_file}。",
        )

    # 签名变化
    if old.signature != new.signature:
        return (
            DriftLevel.SIGNATURE_CHANGED,
            f"'{old.qualified_name}' 签名已变更:\n"
            f"  旧: {old.signature}\n"
            f"  新: {new.signature}",
        )

    # 重命名
    if old.qualified_name != new.qualified_name:
        return (
            DriftLevel.SYMBOL_REMOVED,
            f"'{old.qualified_name}' 重命名为 '{new.qualified_name}'。",
        )

    # 仅行号偏移
    if old.start_line != new.start_line or old.end_line != new.end_line:
        return (
            DriftLevel.COSMETIC,
            f"'{old.qualified_name}' 从行 {old.start_line}-{old.end_line} "
            f"偏移到 {new.start_line}-{new.end_line}。签名未变。",
        )

    # 完全一致
    return (
        DriftLevel.NONE,
        f"'{old.qualified_name}' 未变化。",
    )


# ── 影响面遍历 ───────────────────────────────────────────────────────────────


def _find_affected_callers(
    drifted_atom_ids: set[str],
    graph: RepositoryCodeGraph,
    max_depth: int,
) -> dict[str, set[str]]:
    """沿 reverse_index 做 BFS，找到每个漂移符号的受影响调用方。

    返回 {drifted_atom_id: {caller_atom_ids}}。
    """
    result: dict[str, set[str]] = {aid: set() for aid in drifted_atom_ids}

    for drifted_id in drifted_atom_ids:
        visited: set[str] = set()
        frontier: set[str] = {drifted_id}

        for _ in range(max_depth):
            next_frontier: set[str] = set()
            for atom_id in frontier:
                for rel in graph.reverse_index.get(atom_id, ()):
                    if rel.source_atom_id in visited:
                        continue
                    if rel.source_atom_id in drifted_atom_ids:
                        continue
                    visited.add(rel.source_atom_id)
                    next_frontier.add(rel.source_atom_id)
                    result[drifted_id].add(rel.source_atom_id)
            frontier = next_frontier
            if not frontier:
                break

    return result


# ── 辅助函数 ─────────────────────────────────────────────────────────────────


def _binding_file(binding: ExistingBinding) -> str:
    """从 binding 获取有效文件路径。"""
    return (
        binding.file_path
        or binding.path_pattern.replace("/**", "").replace("**/*.", "")
        or ""
    )


def _file_removed_detail(binding: ExistingBinding) -> str:
    """构建 FILE_REMOVED 级别的详细说明。"""
    file_path = _binding_file(binding)
    return f"绑定文件 '{file_path}' 在新 revision 中不存在。"


def _build_recommendation(resolution: BindingReResolution) -> str:
    """根据漂移级别生成操作建议。"""
    match resolution.drift_level:
        case DriftLevel.NONE:
            return "无需操作。"
        case DriftLevel.COSMETIC:
            return (
                "更新绑定的行号以反映新的代码位置。"
                "文档内容大概率仍然准确。"
            )
        case DriftLevel.SIGNATURE_CHANGED:
            return (
                "审阅文档 Block 内容——所描述代码的签名已发生变化，"
                "文档可能已过时需要重写。"
            )
        case DriftLevel.SYMBOL_MOVED:
            return (
                "更新绑定的文件路径到新位置。"
                "检查文档中是否有引用旧路径的内容。"
            )
        case DriftLevel.SYMBOL_REMOVED:
            return (
                "该绑定的符号已被删除。移除绑定，"
                "并决定是归档还是重写关联的文档 Block。"
            )
        case DriftLevel.FILE_REMOVED:
            return (
                "该绑定的文件已被删除。移除绑定，"
                "并决定是否归档关联的文档 Block。"
            )
    return ""
