"""规划证据目录——从 ContextSnapshot 确定性生成，只含 Binding 所需事实。"""

from dataclasses import dataclass

from app.schemas.ast_atom import AtomCatalog, SymbolAtom
from app.schemas.model_context.snapshot import ContextSnapshot


@dataclass(frozen=True)
class EvidenceAtom:
    """一个符号的 Binding 所需的最简信息。不含源码正文。"""

    atom_id: str
    symbol_key: str
    file_path: str
    qualified_name: str
    kind: str
    start_line: int
    end_line: int


@dataclass(frozen=True)
class PlanningEvidenceCatalog:
    """从快照提取的规划证据目录。

    atom_id → file_path / start_line / end_line 的映射。
    binding_resolver 用这个把 informed_by (atom_id[]) 转成 BindingProposal。
    """

    context_id: str
    revision: str
    atoms: tuple[EvidenceAtom, ...]

    def by_atom_id(self, atom_id: str) -> EvidenceAtom | None:
        return next((a for a in self.atoms if a.atom_id == atom_id), None)

    def by_symbol_key(self, key: str) -> EvidenceAtom | None:
        return next((a for a in self.atoms if a.symbol_key == key), None)


def build_evidence_catalog(
    snapshot: ContextSnapshot,
    catalog: AtomCatalog,
    extra_symbols: tuple[SymbolAtom, ...] = (),
) -> PlanningEvidenceCatalog:
    """从快照和原始 AtomCatalog 构建 PlanningEvidenceCatalog。

    需要 catalog 是因为快照中的 AtomRef 不含 file_path / start_line / end_line。
    extra_symbols：骨架施工时传入模块文件内快照未覆盖的符号（BFS 闭包外），
    保证骨架占位块的绑定覆盖模块全量公开符号。
    """
    by_key: dict[str, SymbolAtom] = {s.symbol_key: s for s in catalog.symbols}
    atoms: list[EvidenceAtom] = []
    seen: set[str] = set()

    for ref in snapshot.atoms:
        sym = by_key.get(ref.symbol_key)
        if sym is None:
            continue
        atoms.append(_to_evidence(sym))
        seen.add(sym.atom_id)

    for sym in extra_symbols:
        if sym.atom_id in seen:
            continue
        atoms.append(_to_evidence(sym))

    return PlanningEvidenceCatalog(
        context_id=snapshot.context_id,
        revision=snapshot.revision,
        atoms=tuple(atoms),
    )


def _to_evidence(sym: SymbolAtom) -> EvidenceAtom:
    return EvidenceAtom(
        atom_id=sym.atom_id,
        symbol_key=sym.symbol_key,
        file_path=_file_from_key(sym.symbol_key),
        qualified_name=sym.qualified_name,
        kind=sym.kind,
        start_line=sym.start_line,
        end_line=sym.end_line,
    )


def _file_from_key(key: str) -> str:
    parts = key.split(":", 2)
    return parts[1] if len(parts) > 1 else ""
