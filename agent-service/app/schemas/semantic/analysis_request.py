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
    entry_point_ids: list[str]                    # symbol_key[]
    structure_block_ids: list[str]                # block_id[]
    manifest: AnalysisManifest
    instruction: str = (
        "读取全部结构块后，解释整组代码的完整语义。"
        "为每个 atom 生成 responsibility 和 role。"
        "所有证据引用必须使用结构块中给出的 symbol_key / relation_id / source_chunk_id，"
        "直接从工具返回里照抄，不要自行填写 ID、file_path 或行号。"
    )
    output_contract: dict = {
        "overall_responsibility": "string",
        "execution_flow": [
            {
                "step_order": 0,
                "atom_id": "symbol_key（如 PYTHON:path:Name:KIND，照抄自工具返回）",
                "description": "string",
                "evidence_refs": [
                    {
                        "atom_id": "symbol_key",
                        "relation_id": "string|null",
                        "source_chunk_id": "string|null",
                    }
                ],
            }
        ],
        "semantic_groups": [
            {
                "group_id": "string",
                "order": 0,
                "title": "string",
                "target_kind": "HTTP_ENDPOINT|DATA_MODEL|BUSINESS_RULE|SYMBOL|OTHER",
                "summary": "string",
                "content_markdown": "string（完整正文，遵守基座排版规则；summary 是摘要）",
                "primary_atom_ids": ["symbol_key"],
                "informed_by_atom_ids": ["symbol_key"],
                "evidence_refs": [
                    {
                        "atom_id": "symbol_key",
                        "relation_id": "string|null",
                        "source_chunk_id": "string|null",
                    }
                ],
            }
        ],
        "member_interpretations": [
            {"atom_id": "symbol_key", "responsibility": "string", "role": "string",
             "content_markdown": "string（完整正文，遵守基座排版规则）",
             "evidence_refs": [{"atom_id": "symbol_key", "relation_id": "string|null",
                                "source_chunk_id": "string|null"}]}
        ],
        "inputs": ["string"],
        "outputs": ["string"],
        "effects": [{"description": "string", "evidence_refs": []}],
        "unresolved_findings": ["string"],
    }
