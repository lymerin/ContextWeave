"""仓库代码关系图模型——第 2a 层产出。

Relation           一条带证据的代码关系（主键 atom_id）。
RelationKind       关系类型枚举。
RelationCategory   关系分类（内部 / 边界 / 未解析）。
RepositoryCodeGraph  AtomCatalog + 完整关系图 + 索引。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from uuid import UUID

from app.schemas.ast_atom import AtomCatalog


class RelationKind:
    CALLS = "CALLS"
    PARAMETER_TYPE = "PARAMETER_TYPE"
    RETURN_TYPE = "RETURN_TYPE"
    CREATES = "CREATES"
    CONTAINS = "CONTAINS"
    INHERITS = "INHERITS"
    IMPLEMENTS = "IMPLEMENTS"
    FIELD_READS = "FIELD_READS"
    FIELD_WRITES = "FIELD_WRITES"
    THROWS = "THROWS"
    PUBLISHES = "PUBLISHES"
    CONSUMES = "CONSUMES"
    PERSISTS = "PERSISTS"


class RelationCategory:
    INTERNAL = "INTERNAL"
    BOUNDARY = "BOUNDARY"
    UNRESOLVED = "UNRESOLVED"


@dataclass(frozen=True)
class Relation:
    """一条带证据的代码关系，主键 atom_id。

    source_atom_id    源原子 atom_id。
    kind              关系类型。
    target_atom_id    目标原子 atom_id（INTERNAL 时非空）。
    target_external   外部符号名（BOUNDARY / UNRESOLVED 时非空）。
    category          关系分类。
    file_path         关系发生的文件。
    line              关系发生的行号（1-based）。
    """

    relation_id: str
    source_atom_id: str
    kind: str
    target_atom_id: str | None = None
    target_external: str | None = None
    category: str = RelationCategory.UNRESOLVED
    file_path: str = ""
    line: int = 0


@dataclass(frozen=True)
class RepositoryCodeGraph:
    """AtomCatalog + 完整关系图。

    forward_index    source_atom_id → [Relation]（出边）。
    reverse_index    target_atom_id → [Relation]（入边），不含外部/未解析。
    boundary         所有边界关系。
    unresolved       所有未解析关系。
    """

    catalog: AtomCatalog
    relations: tuple[Relation, ...] = ()
    forward_index: dict[str, tuple[Relation, ...]] = field(default_factory=dict)
    reverse_index: dict[str, tuple[Relation, ...]] = field(default_factory=dict)
    boundary: tuple[Relation, ...] = ()
    unresolved: tuple[Relation, ...] = ()

    @property
    def repository_id(self) -> UUID:
        return self.catalog.repository_id

    @property
    def revision(self) -> str:
        return self.catalog.revision

    @property
    def total_relations(self) -> int:
        return len(self.relations)

    @property
    def total_boundary(self) -> int:
        return len(self.boundary)

    @property
    def total_unresolved(self) -> int:
        return len(self.unresolved)
