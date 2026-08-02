"""语义结果校验——semantic_groups + member_interpretations 的完整性校验。"""

from app.schemas.model_context.snapshot import ContextSnapshot
from app.schemas.semantic.analysis_result import SemanticAnalysisResult


class ResultValidator:
    """校验 SemanticAnalysisResult 的正确性。"""

    def __init__(self, snapshot: ContextSnapshot) -> None:
        self._snap = snapshot
        self._errors: list[str] = []

    def validate(self, result: SemanticAnalysisResult) -> list[str]:
        self._errors = []

        self._check_field("analysis_id", result.analysis_id)
        self._check_revision(result.revision)
        self._check_hash(result.snapshot_hash)

        # ── semantic_groups 校验 ────────────────────────────────────
        self._validate_groups(result)

        # ── member_interpretations 校验 ────────────────────────────
        for i, m in enumerate(result.member_interpretations):
            self._check_atom_exists(f"member_interpretations[{i}].atom_id", m.atom_id)
            for j, ref in enumerate(m.evidence_refs):
                self._check_evidence(f"member_interpretations[{i}].evidence_refs[{j}]", ref)

        # ── execution_flow 校验 ─────────────────────────────────────
        for i, step in enumerate(result.execution_flow):
            self._check_atom_exists(f"execution_flow[{i}].atom_id", step.atom_id)
            for j, ref in enumerate(step.evidence_refs):
                self._check_evidence(f"execution_flow[{i}].evidence_refs[{j}]", ref)

        return self._errors

    # ── semantic_groups ─────────────────────────────────────────────

    def _validate_groups(self, result: SemanticAnalysisResult) -> None:
        seen_group_ids: set[str] = set()
        seen_orders: set[int] = set()

        for i, g in enumerate(result.semantic_groups):
            path = f"semantic_groups[{i}]"

            # group_id 存在且不重复
            if not g.group_id:
                self._errors.append(f"{path}: empty group_id")
            elif g.group_id in seen_group_ids:
                self._errors.append(f"{path}: duplicate group_id={g.group_id}")
            seen_group_ids.add(g.group_id)

            # order 不重复
            if g.order in seen_orders:
                self._errors.append(f"{path}: duplicate order={g.order}")
            seen_orders.add(g.order)

            # primary_atom_ids 非空
            if not g.primary_atom_ids:
                self._errors.append(f"{path}: primary_atom_ids is empty")

            # primary_atom_ids ⊆ informed_by_atom_ids
            informed_set = set(g.informed_by_atom_ids)
            for j, atom_id in enumerate(g.primary_atom_ids):
                if not atom_id:
                    self._errors.append(f"{path}.primary_atom_ids[{j}]: empty")
                elif atom_id not in informed_set:
                    self._errors.append(
                        f"{path}.primary_atom_ids[{j}]: {atom_id} not in informed_by_atom_ids"
                    )

            # 所有 atom_id 存在于快照
            seen_atoms: set[str] = set()
            for j, atom_id in enumerate(g.informed_by_atom_ids):
                if not atom_id:
                    self._errors.append(f"{path}.informed_by_atom_ids[{j}]: empty")
                    continue
                self._check_atom_exists(f"{path}.informed_by_atom_ids[{j}]", atom_id)
                if atom_id in seen_atoms:
                    self._errors.append(f"{path}: duplicate atom_id={atom_id}")
                seen_atoms.add(atom_id)

            # evidence_refs 校验
            for j, ref in enumerate(g.evidence_refs):
                self._check_evidence(f"{path}.evidence_refs[{j}]", ref)

    # ── helpers ──────────────────────────────────────────────────────

    def _check_field(self, path: str, value: str) -> None:
        if not value:
            self._errors.append(f"{path}: empty")

    def _check_revision(self, revision: str) -> None:
        if revision != self._snap.revision:
            self._errors.append(f"revision mismatch: {revision} != {self._snap.revision}")

    def _check_hash(self, h: str) -> None:
        if h != self._snap.snapshot_hash:
            self._errors.append("snapshot_hash mismatch")

    def _check_atom_exists(self, path: str, atom_id: str) -> None:
        if atom_id not in self._snap.atom_by_id:
            self._errors.append(f"{path}: atom_id not in snapshot: {atom_id}")

    def _check_evidence(self, path: str, ref) -> None:
        if not ref.atom_id:
            self._errors.append(f"{path}: missing atom_id")
        elif ref.atom_id not in self._snap.atom_by_id:
            self._errors.append(f"{path}: atom_id not in snapshot: {ref.atom_id}")
        if ref.source_chunk_id and ref.source_chunk_id not in self._snap.chunk_by_id:
            self._errors.append(f"{path}: source_chunk_id not in snapshot: {ref.source_chunk_id}")
