"""平台 MCP 源码文件模型——三层严格区分路径、筛选、内容。"""

from pydantic import BaseModel, ConfigDict


class RepositoryFileRef(BaseModel):
    """仓库文件元数据——只含路径、后缀、大小，不含源码。
    workspace_reader 从 repository.list_files 产出。
    """
    model_config = ConfigDict(extra="forbid", strict=True)

    file_path: str
    extension: str = ""
    size_bytes: int = 0
    language: str | None = None
    readable: bool = True


class SelectedSourceFileBatch(BaseModel):
    """筛选后的待读取路径列表——source_selection 产出，source_reader 输入。"""
    model_config = ConfigDict(extra="forbid", strict=True)

    repository_id: str
    revision: str
    paths: tuple[str, ...]
    skipped_count: int = 0
    total_count: int = 0


class SourceFileRef(BaseModel):
    """一个已读取源码的文件。"""
    model_config = ConfigDict(extra="forbid", strict=True)

    file_path: str
    language: str
    content: str
    size_bytes: int = 0
    truncated: bool = False


class SourceFileBatch(BaseModel):
    """已读取源码的文件集合——source_reader 产出，source_analysis 输入。"""
    model_config = ConfigDict(extra="forbid", strict=True)

    repository_id: str
    revision: str
    files: tuple[SourceFileRef, ...]
    total_count: int = 0
