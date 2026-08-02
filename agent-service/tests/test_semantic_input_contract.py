import json
from uuid import uuid4

import httpx
import pytest

from app.providers.deepseek import DeepSeekProvider
from app.schemas.semantic.analysis_request import AnalysisManifest, SemanticAnalysisRequest
from app.schemas.source_file import SourceFileBatch, SourceFileRef
from app.source_analysis.code_ast_atom import parse_batch


def test_python_route_fields_are_kept_for_class_methods() -> None:
    source = """
class Handler:
    @router.post('/orders', response_model=OrderResponse)
    async def create(self):
        return None
"""
    path = "app/api/orders.py"
    batch = SourceFileBatch(
        repository_id=uuid4(),
        revision="rev-1",
        files=(SourceFileRef(path, "Python", len(source)),),
    )

    catalog = parse_batch(batch, lambda _: source)

    route = next(symbol for symbol in catalog.symbols if symbol.name == "create")
    assert route.http_method == "POST"
    assert route.http_path == "/orders"
    assert route.http_response == "OrderResponse"


@pytest.mark.asyncio
async def test_semantic_provider_supplies_program_owned_metadata() -> None:
    request = SemanticAnalysisRequest(
        analysis_id="analysis-1",
        context_id="context-1",
        revision="revision-1",
        snapshot_hash="snapshot-1",
        entry_point_ids=["atom-1"],
        structure_block_ids=["block-1"],
        manifest=AnalysisManifest(
            atom_count=1,
            block_count=1,
            chunk_count=1,
            relation_count=0,
        ),
    )

    def handler(_request: httpx.Request) -> httpx.Response:
        # 模型只返回语义字段，程序元数据由 Provider 补齐。
        return httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "message": {
                            "content": json.dumps(
                                {
                                    "analysis_id": "model-must-not-override",
                                    "context_id": "model-must-not-override",
                                    "overall_responsibility": "订单接口",
                                    "member_interpretations": [],
                                }
                            )
                        }
                    }
                ]
            },
        )

    provider = DeepSeekProvider(
        api_key="test-key",
        base_url="https://model.invalid",
        model="deepseek-chat",
        connect_timeout_seconds=1,
        request_timeout_seconds=2,
        transport=httpx.MockTransport(handler),
    )

    result = await provider.analyze_semantics(request, lambda _name, _args: {})

    assert result.analysis_id == request.analysis_id
    assert result.context_id == request.context_id
    assert result.revision == request.revision
    assert result.snapshot_hash == request.snapshot_hash
    assert result.overall_responsibility == "订单接口"
