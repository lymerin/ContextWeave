"""语义结果校验——semantic_groups + member_interpretations 的完整性校验。"""

from app.schemas.document_planner.skeleton import SkeletonSlot, SlotType
from app.schemas.model_context.snapshot import ContextSnapshot
from app.schemas.semantic.analysis_result import SemanticAnalysisResult


class ResultValidator:
    """校验 SemanticAnalysisResult 的正确性。

    required_slots 提供时，额外核对批次槽位覆盖：SYMBOL 槽位要求
    member_interpretations 命中目标原子，FLOW/OVERVIEW 槽位要求
    semantic_groups 命中入口原子。缺失的槽位逐个点名（触发 repair/重试）。
    """

    def __init__(
        self,
        snapshot: ContextSnapshot,
        required_slots: tuple[SkeletonSlot, ...] = (),
    ) -> None:
        self._snap = snapshot
        self._required_slots = required_slots
        self._errors: list[str] = []
        # 模型只返回 symbol_key，入口 _bind_result_atoms 已绑定回 atom_id。
        # 校验时两种 ID 都接受（存在即视为有效），避免符号缺失时误判格式错误。
        self._valid_ids: set[str] = set(snapshot.atom_by_id.keys())
        self._valid_ids.update(snapshot.atom_by_symbol.keys())

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

        # ── 批次槽位覆盖 ────────────────────────────────────────────
        self._check_slot_coverage(result)

        return self._errors

    # ── 槽位覆盖 ─────────────────────────────────────────────────────

    def _check_slot_coverage(self, result: SemanticAnalysisResult) -> None:
        if not self._required_slots:
            return
        group_atoms = {
            a
            for g in result.semantic_groups
            for a in (*g.primary_atom_ids, *g.informed_by_atom_ids)
        }
        interp_atoms = {m.atom_id for m in result.member_interpretations}
        for slot in self._required_slots:
            if slot.slot_type == SlotType.OVERVIEW:
                if not result.overall_responsibility:
                    self._errors.append(f"slot 未覆盖: {slot.slot_id}（{slot.title}）")
                continue
            key = slot.primary_symbol_key or ""
            target = self._snap.atom_by_symbol.get(key)
            if target is None:
                # 槽位目标不在快照 = 快照覆盖缺陷（预算裁剪/scope 构建问题）
                self._errors.append(
                    f"slot 目标不在快照: {slot.slot_id}（{slot.title}）"
                )
                continue
            if slot.slot_type == SlotType.SYMBOL:
                # 会话内校验时解释的 atom_id 仍是 symbol_key（绑定在会话后），
                # 两种形式都接受
                if target not in interp_atoms and key not in interp_atoms:
                    self._errors.append(f"slot 未覆盖: {slot.slot_id}（{slot.title}）")
            else:  # FLOW：semantic_group 必须命中入口原子
                if target not in group_atoms and key not in group_atoms:
                    self._errors.append(f"slot 未覆盖: {slot.slot_id}（{slot.title}）")

    # ── semantic_groups ─────────────────────────────────────────────

    def _validate_groups(self, result: SemanticAnalysisResult) -> None:
        # 宽容校验：不做格式层面的硬性拒绝（group_id 去重、order 去重、
        # primary 非空、primary⊆informed 等——这些是模型创作自由，不应触发
        # repair loop）。只校验引用的 atom 确实存在于快照。
        for i, g in enumerate(result.semantic_groups):
            path = f"semantic_groups[{i}]"
            for j, atom_id in enumerate(g.primary_atom_ids):
                self._check_atom_exists(f"{path}.primary_atom_ids[{j}]", atom_id)
            for j, atom_id in enumerate(g.informed_by_atom_ids):
                self._check_atom_exists(f"{path}.informed_by_atom_ids[{j}]", atom_id)
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
        if atom_id not in self._valid_ids:
            self._errors.append(f"{path}: atom_id not in snapshot: {atom_id}")

    def _check_evidence(self, path: str, ref) -> None:
        if not ref.atom_id:
            self._errors.append(f"{path}: missing atom_id")
        elif ref.atom_id not in self._valid_ids:
            self._errors.append(f"{path}: atom_id not in snapshot: {ref.atom_id}")
        if ref.source_chunk_id and ref.source_chunk_id not in self._snap.chunk_by_id:
            self._errors.append(f"{path}: source_chunk_id not in snapshot: {ref.source_chunk_id}")
