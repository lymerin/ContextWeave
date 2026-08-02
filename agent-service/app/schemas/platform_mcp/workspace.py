"""平台 MCP workspace 模型——Pydantic strict，MCP 边界数据。"""

from uuid import UUID

from pydantic import BaseModel, ConfigDict


class RepositoryRef(BaseModel):
    """仓库引用——workspace.get_context 返回的单个仓库。"""
    model_config = ConfigDict(extra="forbid", strict=True)

    repository_id: UUID
    name: str
    default_branch: str = "main"
    last_synced_commit: str | None = None


class WorkspaceContext(BaseModel):
    """工作区上下文——workspace.get_context 返回。"""
    model_config = ConfigDict(extra="forbid", strict=True)

    workspace_id: UUID
    repositories: list[RepositoryRef]

    def repository(self, repository_id: UUID) -> RepositoryRef | None:
        return next(
            (r for r in self.repositories if r.repository_id == repository_id), None
        )
