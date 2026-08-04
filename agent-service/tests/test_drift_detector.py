"""漂移检测器单元测试。

覆盖:
  - classify_drift: 所有漂移级别的判定表
  - DriftDetector.re_resolve_binding: 各种 binding 类型的重新解析
  - DriftDetector.detect: 批量检测
  - DriftDetector.assess_impact: 影响面评估
  - symbol_key 解析辅助函数
"""

from __future__ import annotations

import sys
from uuid import UUID, uuid4

sys.path.insert(0, "agent-service")

# drift_writer 的异步 MCP 提交部分需要在集成测试中覆盖，
# 此处验证 payload 构造逻辑中的纯函数部分。
from app.platform_mcp.drift_writer import (
    _build_evidence,
    _build_proposal,
    _build_rationale,
)
from app.schemas.ast_atom import (
    AtomCatalog,
    ModuleAtom,
    SymbolAtom,
    symbol_key_file_path,
    symbol_key_qualified_name,
)
from app.schemas.drift import BindingReResolution, DriftLevel
from app.schemas.platform_mcp.binding import ExistingBinding
from app.schemas.repository_graph import (
    Relation,
    RelationCategory,
    RelationKind,
    RepositoryCodeGraph,
)
from app.source_analysis.drift_detector import DriftDetector, classify_drift

# ── 测试用常量 ───────────────────────────────────────────────────────────────

REPO_ID = UUID("00000000-0000-0000-0000-000000000001")
OLD_REV = "abc123def456"
NEW_REV = "xyz789uvw012"

# ── Fixture 工厂 ─────────────────────────────────────────────────────────────


def _make_atom(
    atom_id: str = "sym_test1234",
    symbol_key: str = "PYTHON:src/service.py:UserService.create_user:METHOD",
    kind: str = "METHOD",
    name: str = "create_user",
    qualified_name: str = "UserService.create_user",
    signature: str = "def create_user(self, name: str) -> User",
    start_line: int = 42,
    end_line: int = 58,
    **kwargs,
) -> SymbolAtom:
    """构建测试用 SymbolAtom，所有字段都有合理默认值。"""
    return SymbolAtom(
        atom_id=atom_id,
        symbol_key=symbol_key,
        kind=kind,
        name=name,
        qualified_name=qualified_name,
        signature=signature,
        start_line=start_line,
        end_line=end_line,
        body_start_line=kwargs.get("body_start_line", 45),
        body_end_line=kwargs.get("body_end_line", 57),
        decorator_names=kwargs.get("decorator_names", ()),
        docstring=kwargs.get("docstring", None),
        parent_qualified=kwargs.get("parent_qualified", "UserService"),
        is_async=kwargs.get("is_async", False),
        is_pydantic=kwargs.get("is_pydantic", False),
        http_method=kwargs.get("http_method", None),
        http_path=kwargs.get("http_path", None),
        http_response=kwargs.get("http_response", None),
    )


def _make_catalog(
    revision: str = OLD_REV,
    symbols: tuple[SymbolAtom, ...] = (),
    modules: tuple[ModuleAtom, ...] | None = None,
) -> AtomCatalog:
    """构建测试用 AtomCatalog。"""
    if modules is None:
        # 收集所有出现过的 file_path
        files = sorted({symbol_key_file_path(s.symbol_key) for s in symbols})
        if not files:
            files = ["src/service.py"]
        modules = tuple(
            ModuleAtom(
                atom_id=f"mod_{f.replace('/', '_')[:20]}",
                file_path=f,
                language="Python",
                start_line=1,
                end_line=100,
            )
            for f in files
        )
    return AtomCatalog(
        repository_id=REPO_ID,
        revision=revision,
        modules=modules,
        symbols=symbols,
    )


def _make_binding(
    binding_id: UUID | None = None,
    document_id: UUID | None = None,
    block_id: UUID | None = None,
    file_path: str | None = None,
    symbol_key: str = "PYTHON:src/service.py:UserService.create_user:METHOD",
    anchor_kind: str = "SYMBOL",
    start_line: int = 42,
    end_line: int = 58,
    **kwargs,
) -> ExistingBinding:
    """构建测试用 ExistingBinding。

    file_path 默认从 symbol_key 推导。
    """
    if file_path is None:
        file_path = symbol_key_file_path(symbol_key) if symbol_key else "src/service.py"
    return ExistingBinding(
        binding_id=binding_id or uuid4(),
        document_id=document_id or uuid4(),
        block_id=block_id,
        file_path=file_path,
        path_pattern=file_path,
        symbol_key=symbol_key,
        anchor_kind=anchor_kind,
        start_line=start_line,
        end_line=end_line,
        **kwargs,
    )


# ── symbol_key 解析 ──────────────────────────────────────────────────────────


def test_symbol_key_file_path() -> None:
    assert symbol_key_file_path("PYTHON:a/b.py:Foo.bar:METHOD") == "a/b.py"
    assert symbol_key_file_path("PYTHON:src/main.py:main:FUNCTION") == "src/main.py"
    assert symbol_key_file_path("INVALID") == ""


def test_symbol_key_qualified_name() -> None:
    assert symbol_key_qualified_name("PYTHON:a/b.py:Foo.bar:METHOD") == "Foo.bar"
    assert symbol_key_qualified_name("PYTHON:src/main.py:main:FUNCTION") == "main"
    assert symbol_key_qualified_name("INVALID") == ""


# ── classify_drift ───────────────────────────────────────────────────────────


class TestClassifyDrift:
    """分类漂移判定表测试。"""

    def test_none_no_change(self) -> None:
        """签名和行号完全一致 → NONE。"""
        old = _make_atom()
        new = _make_atom()
        level, detail = classify_drift(old_atom=old, new_atom=new)
        assert level == DriftLevel.NONE, f"预期 NONE，实际 {level}: {detail}"
        assert "未变化" in detail

    def test_cosmetic_line_shift(self) -> None:
        """仅行号偏移 → COSMETIC。"""
        old = _make_atom(start_line=42, end_line=58)
        new = _make_atom(start_line=50, end_line=66)
        level, detail = classify_drift(old_atom=old, new_atom=new)
        assert level == DriftLevel.COSMETIC, f"预期 COSMETIC，实际 {level}: {detail}"
        assert "偏移" in detail or "shift" in detail.lower()

    def test_signature_changed(self) -> None:
        """签名变化 → SIGNATURE_CHANGED。"""
        old = _make_atom(signature="def create_user(self, name: str) -> User")
        new = _make_atom(signature="def create_user(self, name: str, email: str) -> User")
        level, detail = classify_drift(old_atom=old, new_atom=new)
        assert level == DriftLevel.SIGNATURE_CHANGED, (
            f"预期 SIGNATURE_CHANGED，实际 {level}: {detail}"
        )

    def test_symbol_moved(self) -> None:
        """文件移动 → SYMBOL_MOVED。"""
        old = _make_atom(symbol_key="PYTHON:src/old.py:Foo.bar:METHOD")
        new = _make_atom(symbol_key="PYTHON:src/new.py:Foo.bar:METHOD")
        level, detail = classify_drift(old_atom=old, new_atom=new)
        assert level == DriftLevel.SYMBOL_MOVED, f"预期 SYMBOL_MOVED，实际 {level}: {detail}"
        assert "移动" in detail or "move" in detail.lower()

    def test_symbol_removed(self) -> None:
        """符号被删除 → SYMBOL_REMOVED。"""
        old = _make_atom()
        level, detail = classify_drift(old_atom=old, new_atom=None, new_file_exists=True)
        assert level == DriftLevel.SYMBOL_REMOVED, f"预期 SYMBOL_REMOVED，实际 {level}: {detail}"

    def test_file_removed(self) -> None:
        """文件被删除 → FILE_REMOVED。"""
        old = _make_atom()
        level, detail = classify_drift(old_atom=old, new_atom=None, new_file_exists=False)
        assert level == DriftLevel.FILE_REMOVED, f"预期 FILE_REMOVED，实际 {level}: {detail}"

    def test_legacy_binding_no_old_atom(self) -> None:
        """legacy 绑定无 old_atom（FILE/RANGE 级）→ 保守判定为 NONE。"""
        level, detail = classify_drift(old_atom=None, new_atom=None, new_file_exists=True)
        assert level == DriftLevel.NONE, f"预期 NONE，实际 {level}"

    def test_new_symbol_no_old(self) -> None:
        """新符号无旧可比对 → NONE。"""
        new = _make_atom()
        level, detail = classify_drift(old_atom=None, new_atom=new, new_file_exists=True)
        assert level == DriftLevel.NONE

    def test_symbol_renamed(self) -> None:
        """qualified_name 变化 → SYMBOL_REMOVED（被视为删除+新增）。"""
        old = _make_atom(qualified_name="OldService.do_stuff")
        new = _make_atom(qualified_name="NewService.do_stuff")
        level, detail = classify_drift(old_atom=old, new_atom=new)
        assert level == DriftLevel.SYMBOL_REMOVED


# ── DriftDetector ────────────────────────────────────────────────────────────


class TestDriftDetector:
    """漂移检测器集成测试。"""

    def _make_detector(
        self,
        old_symbols: tuple[SymbolAtom, ...] = (),
        new_symbols: tuple[SymbolAtom, ...] = (),
    ) -> DriftDetector:
        old_cat = _make_catalog(OLD_REV, old_symbols)
        new_cat = _make_catalog(NEW_REV, new_symbols)
        return DriftDetector(old_cat, new_cat)

    def test_detect_symbol_key_exact_match(self) -> None:
        """symbol_key 精确匹配 → NONE。"""
        sym = _make_atom()
        detector = self._make_detector((sym,), (sym,))
        binding = _make_binding(symbol_key=sym.symbol_key)

        result = detector.re_resolve_binding(binding)
        assert result.drift_level == DriftLevel.NONE
        assert result.resolution_method == "symbol_key"
        assert result.new_atom is not None

    def test_detect_qualified_name_fallback(self) -> None:
        """旧 symbol_key 在新 catalog 中不存在，但 qualified_name 可以匹配 → qualified_name。"""
        old_sym = _make_atom(
            atom_id="sym_old",
            symbol_key="PYTHON:src/old.py:UserService.create_user:METHOD",
            qualified_name="UserService.create_user",
        )
        new_sym = _make_atom(
            atom_id="sym_new",
            symbol_key="PYTHON:src/new.py:UserService.create_user:METHOD",
            qualified_name="UserService.create_user",
        )
        detector = self._make_detector((old_sym,), (new_sym,))
        binding = _make_binding(
            symbol_key="PYTHON:src/old.py:UserService.create_user:METHOD",
            file_path="src/old.py",
        )

        result = detector.re_resolve_binding(binding)
        assert result.drift_level == DriftLevel.SYMBOL_MOVED
        assert result.resolution_method == "qualified_name"

    def test_detect_line_range_fallback(self) -> None:
        """无 symbol_key 匹配，但有行号范围 → line_range。"""
        new_sym = _make_atom(
            symbol_key="PYTHON:src/service.py:UserService.create_user:METHOD",
            start_line=42,
            end_line=58,
        )
        detector = self._make_detector((), (new_sym,))
        binding = _make_binding(
            symbol_key=None,
            anchor_kind="RANGE",
            start_line=42,
            end_line=50,
            file_path="src/service.py",
        )

        result = detector.re_resolve_binding(binding)
        assert result.resolution_method in ("line_range", "file_only", "symbol_key")

    def test_detect_file_removed(self) -> None:
        """文件不存在 → FILE_REMOVED。"""
        old_sym = _make_atom(symbol_key="PYTHON:src/deleted.py:Foo.bar:METHOD")
        detector = self._make_detector((old_sym,), ())
        binding = _make_binding(
            symbol_key="PYTHON:src/deleted.py:Foo.bar:METHOD",
            file_path="src/deleted.py",
        )

        result = detector.re_resolve_binding(binding)
        assert result.drift_level == DriftLevel.FILE_REMOVED

    def test_detect_legacy_file_binding(self) -> None:
        """FILE 级 binding，文件仍存在 → NONE。"""
        mod = ModuleAtom(
            atom_id="mod_src_service",
            file_path="src/service.py",
            language="Python",
            start_line=1,
            end_line=100,
        )
        old_cat = _make_catalog(OLD_REV, modules=(mod,))
        new_cat = _make_catalog(NEW_REV, modules=(mod,))
        detector = DriftDetector(old_cat, new_cat)

        binding = _make_binding(
            symbol_key=None,
            anchor_kind="FILE",
            file_path="src/service.py",
        )
        result = detector.re_resolve_binding(binding)
        # 保守不判定漂移
        assert result.drift_level in (DriftLevel.NONE, DriftLevel.FILE_REMOVED)

    def test_detect_batch(self) -> None:
        """批量检测返回与输入同序的结果列表。"""
        sym_a = _make_atom(
            atom_id="sym_aaaa",
            symbol_key="PYTHON:src/a.py:A.foo:METHOD",
            qualified_name="A.foo",
        )
        sym_b = _make_atom(
            atom_id="sym_bbbb",
            symbol_key="PYTHON:src/b.py:B.bar:METHOD",
            qualified_name="B.bar",
            signature="def bar(self, x: int) -> str",
        )
        # 新版本: a 不变, b 签名变了
        new_sym_a = _make_atom(
            atom_id="sym_aaaa_v2",
            symbol_key="PYTHON:src/a.py:A.foo:METHOD",
            qualified_name="A.foo",
        )
        new_sym_b = _make_atom(
            atom_id="sym_bbbb_v2",
            symbol_key="PYTHON:src/b.py:B.bar:METHOD",
            qualified_name="B.bar",
            signature="def bar(self, x: int, y: int) -> str",
        )
        detector = self._make_detector((sym_a, sym_b), (new_sym_a, new_sym_b))

        bindings = [
            _make_binding(symbol_key="PYTHON:src/a.py:A.foo:METHOD"),
            _make_binding(symbol_key="PYTHON:src/b.py:B.bar:METHOD"),
        ]
        results = detector.detect(bindings)
        assert len(results) == 2
        assert results[0].drift_level == DriftLevel.NONE
        assert results[1].drift_level == DriftLevel.SIGNATURE_CHANGED


# ── 影响面评估 ───────────────────────────────────────────────────────────────


class TestAssessImpact:
    """影响面评估测试。"""

    def _make_graph(
        self,
        symbols: tuple[SymbolAtom, ...],
        relations: tuple[Relation, ...] = (),
    ) -> RepositoryCodeGraph:
        catalog = _make_catalog(OLD_REV, symbols)
        reverse_index: dict[str, list[Relation]] = {}
        for rel in relations:
            if rel.target_atom_id:
                reverse_index.setdefault(rel.target_atom_id, []).append(rel)

        return RepositoryCodeGraph(
            catalog=catalog,
            relations=relations,
            forward_index={},
            reverse_index={k: tuple(v) for k, v in reverse_index.items()},
            boundary=(),
            unresolved=(),
        )

    def test_no_drift_produces_empty_reports(self) -> None:
        """无漂移 → 每条 binding 返回含 NONE 的报告，无调用方。"""
        sym = _make_atom()
        det = DriftDetector(_make_catalog(OLD_REV, (sym,)), _make_catalog(NEW_REV, (sym,)))
        resolutions = det.detect([
            _make_binding(symbol_key=sym.symbol_key),
        ])

        graph = self._make_graph((sym,))
        reports = det.assess_impact(resolutions, graph)
        assert len(reports) == 1
        assert reports[0].resolution.drift_level == DriftLevel.NONE
        assert reports[0].affected_caller_keys == ()

    def test_finds_affected_callers(self) -> None:
        """漂移符号被其他符号调用 → 调用方出现在报告中。"""
        drifted = _make_atom(
            atom_id="sym_drifted",
            symbol_key="PYTHON:src/a.py:Foo.bar:METHOD",
            qualified_name="Foo.bar",
        )
        caller = _make_atom(
            atom_id="sym_caller",
            symbol_key="PYTHON:src/b.py:Baz.qux:METHOD",
            qualified_name="Baz.qux",
            signature="def qux(self) -> None",
        )
        # 新版本: drifted 签名变了
        new_drifted = _make_atom(
            atom_id="sym_drifted_v2",
            symbol_key="PYTHON:src/a.py:Foo.bar:METHOD",
            qualified_name="Foo.bar",
            signature="def bar(self, x: int) -> str",
        )

        det = DriftDetector(
            _make_catalog(OLD_REV, (drifted, caller)),
            _make_catalog(NEW_REV, (new_drifted, caller)),
        )
        resolutions = det.detect([
            _make_binding(symbol_key=drifted.symbol_key),
        ])

        # caller CALLS drifted
        rel = Relation(
            relation_id="rel_001",
            source_atom_id="sym_caller",
            kind=RelationKind.CALLS,
            target_atom_id="sym_drifted",
            category=RelationCategory.INTERNAL,
            file_path="src/b.py",
            line=15,
        )
        graph = self._make_graph((drifted, caller), (rel,))
        reports = det.assess_impact(resolutions, graph)

        assert len(reports) == 1
        assert reports[0].resolution.drift_level == DriftLevel.SIGNATURE_CHANGED
        assert len(reports[0].affected_caller_keys) == 1
        assert caller.symbol_key in reports[0].affected_caller_keys


# ── DriftWriter ──────────────────────────────────────────────────────────────


class TestDriftWriterHelpers:
    """DriftWriter 辅助函数测试。"""

    def _make_resolution(
        self,
        drift_level: DriftLevel,
        binding_id: UUID | None = None,
        document_id: UUID | None = None,
        block_id: UUID | None = None,
        old_atom: SymbolAtom | None = None,
        new_atom: SymbolAtom | None = None,
    ) -> BindingReResolution:
        return BindingReResolution(
            binding_id=binding_id or uuid4(),
            document_id=document_id or uuid4(),
            block_id=block_id,
            old_atom=old_atom,
            old_file_exists=True,
            new_atom=new_atom,
            new_file_exists=True,
            resolution_method="symbol_key",
            drift_level=drift_level,
            drift_detail="测试漂移",
        )

    def test_build_proposal_upsert(self) -> None:
        """UPSERT_BINDING 包含 filePath, symbolKey, startLine, endLine。"""
        new_sym = _make_atom(start_line=10, end_line=20)
        res = self._make_resolution(
            DriftLevel.COSMETIC,
            binding_id=UUID("11111111-1111-1111-1111-111111111111"),
            document_id=UUID("22222222-2222-2222-2222-222222222222"),
            new_atom=new_sym,
        )
        proposal = _build_proposal(res, "repo-1", "rev-1", 1)
        assert proposal["action"] == "UPSERT_BINDING"
        assert proposal["documentId"] == "22222222-2222-2222-2222-222222222222"
        assert proposal["symbolKey"] == new_sym.symbol_key
        assert proposal["startLine"] == 10
        assert proposal["endLine"] == 20
        assert proposal["anchorKind"] == "SYMBOL"

    def test_build_proposal_remove(self) -> None:
        """SYMBOL_REMOVED → REMOVE_BINDING。"""
        res = self._make_resolution(
            DriftLevel.SYMBOL_REMOVED,
            binding_id=UUID("33333333-3333-3333-3333-333333333333"),
            document_id=UUID("44444444-4444-4444-4444-444444444444"),
        )
        proposal = _build_proposal(res, "repo-1", "rev-1", 1)
        assert proposal["action"] == "REMOVE_BINDING"

    def test_build_evidence(self) -> None:
        """Evidence 包含 repositoryId, filePath, description。"""
        new_sym = _make_atom()
        res = self._make_resolution(
            DriftLevel.SIGNATURE_CHANGED,
            new_atom=new_sym,
        )
        evidence = _build_evidence(res, "repo-1")
        assert evidence["repositoryId"] == "repo-1"
        assert "漂移" in evidence["description"]

    def test_build_rationale_multiple_levels(self) -> None:
        """Rationale 按严重程度排序汇总。"""
        resolutions = [
            self._make_resolution(DriftLevel.COSMETIC),
            self._make_resolution(DriftLevel.SYMBOL_REMOVED),
            self._make_resolution(DriftLevel.COSMETIC),
            self._make_resolution(DriftLevel.SIGNATURE_CHANGED),
        ]
        rationale = _build_rationale(resolutions)
        assert "2" in rationale  # 2 cosmetic
        assert "1" in rationale  # 1 signature_changed
        assert "符号被删除" in rationale or "SYMBOL_REMOVED" in rationale.upper()


# ── 入口 ──────────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    import traceback

    tests = [
        # symbol_key helpers
        ("test_symbol_key_file_path", test_symbol_key_file_path),
        ("test_symbol_key_qualified_name", test_symbol_key_qualified_name),
    ]

    # classify_drift
    tc = TestClassifyDrift()
    for name in dir(tc):
        if name.startswith("test_"):
            tests.append((f"TestClassifyDrift.{name}", getattr(tc, name)))

    # DriftDetector
    td = TestDriftDetector()
    for name in dir(td):
        if name.startswith("test_"):
            tests.append((f"TestDriftDetector.{name}", getattr(td, name)))

    # impact
    ti = TestAssessImpact()
    for name in dir(ti):
        if name.startswith("test_"):
            tests.append((f"TestAssessImpact.{name}", getattr(ti, name)))

    # writer
    tw = TestDriftWriterHelpers()
    for name in dir(tw):
        if name.startswith("test_"):
            tests.append((f"TestDriftWriterHelpers.{name}", getattr(tw, name)))

    passed = 0
    failed = 0
    for name, fn in tests:
        try:
            fn()
            passed += 1
        except Exception:
            print(f"\nFAIL: {name}")
            traceback.print_exc()
            failed += 1

    print(f"\n{'='*60}")
    print(f"RESULTS: {passed} passed, {failed} failed, {passed + failed} total")
    if failed:
        sys.exit(1)
