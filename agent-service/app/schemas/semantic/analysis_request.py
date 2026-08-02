"""语义分析请求——只含引用，不含源码。Pydantic strict。"""

from pydantic import BaseModel, ConfigDict


class AnalysisManifest(BaseModel):
    """发给 DeepSeek 的快照摘要——不含源码。"""
    model_config = ConfigDict(extra="forbid", strict=True)

    atom_count: int
    block_count: int
    chunk_count: int
    relation_count: int


class SemanticAnalysisRequest(BaseModel):
    """DeepSeek 语义分析的初始请求。

    只含 context_id 和引用，源码通过 model_context_mcp 按需读取。
    """
    model_config = ConfigDict(extra="forbid", strict=True)

    analysis_id: str
    context_id: str
    revision: str
    snapshot_hash: str
    entry_point_ids: list[str]                    # atom_id[]
    structure_block_ids: list[str]                # block_id[]
    manifest: AnalysisManifest
    instruction: str = (
        "读取全部结构块后，解释整组代码的完整语义。"
        "为每个 atom 生成 responsibility 和 role。"
        "所有证据引用必须使用 atom_id / relation_id / source_chunk_id，"
        "不要自行填写 file_path 或行号。"
    )
    output_contract: dict = {
        "overall_responsibility": "string",
        "member_interpretations": [
            {"atom_id": "string", "responsibility": "string", "role": "string",
             "evidence_refs": [{"atom_id": "string", "relation_id": "string|null",
                                "source_chunk_id": "string|null"}]}
        ],
    }
