"""冻结快照——ShapedCodeContext → ContextSnapshot。"""

import hashlib

from app.schemas.shaped_context import ShapedCodeContext
from app.schemas.model_context.snapshot import ContextSnapshot, freeze


def freeze_context(shaped: ShapedCodeContext) -> ContextSnapshot:
    """冻结整形上下文为不可变快照。

    生成 context_id，校验完整性后冻结。
    """
    raw = f"{shaped.repository_id}\0{shaped.revision}\0{shaped.scope_id}"
    context_id = "ctx_" + hashlib.sha256(raw.encode()).hexdigest()[:24]

    _validate(shaped)
    return freeze(shaped, context_id)


def _validate(shaped: ShapedCodeContext) -> None:
    if not shaped.repository_id:
        raise ValueError("repository_id required")
    if not shaped.revision:
        raise ValueError("revision required")
    if not shaped.atoms:
        raise ValueError("at least one atom required")
    if not shaped.structure_blocks:
        raise ValueError("at least one structure block required")
    chunk_ids = {c.chunk_id for c in shaped.chunks}
    for atom in shaped.atoms:
        for cid in atom.chunk_ids:
            if cid not in chunk_ids:
                raise ValueError(f"atom {atom.symbol_key} refs missing chunk {cid}")
