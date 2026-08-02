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
) -> PlanningEvidenceCatalog:
    """从快照和原始 AtomCatalog 构建 PlanningEvidenceCatalog。

    需要 catalog 是因为快照中的 AtomRef 不含 file_path / start_line / end_line。
    """
    by_key: dict[str, SymbolAtom] = {s.symbol_key: s for s in catalog.symbols}
    atoms: list[EvidenceAtom] = []

    for ref in snapshot.atoms:
        sym = by_key.get(ref.symbol_key)
        if sym is None:
            continue
        file = _file_from_key(sym.symbol_key)
        atoms.append(EvidenceAtom(
            atom_id=sym.atom_id,
            symbol_key=sym.symbol_key,
            file_path=file,
            qualified_name=sym.qualified_name,
            kind=sym.kind,
            start_line=sym.start_line,
            end_line=sym.end_line,
        ))

    return PlanningEvidenceCatalog(
        context_id=snapshot.context_id,
        revision=snapshot.revision,
        atoms=tuple(atoms),
    )


def _file_from_key(key: str) -> str:
    parts = key.split(":", 2)
    return parts[1] if len(parts) > 1 else ""
