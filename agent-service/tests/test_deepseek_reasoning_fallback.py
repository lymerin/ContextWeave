"""DeepSeek 空 content 时从 reasoning_content 提取 JSON 的兜底测试。

回归：deepseek-v4-flash 在深度推理请求下偶发 finish=stop 但 content 为空，
reasoning_content 却包含完整 JSON 草稿（实测 reasoning 可达 5 万+ 字符）。
变异重试前必须先尝试从 reasoning 提取，否则同一失败会确定性复现。
"""

import json

import httpx
import pytest

from app.providers.base import ModelProviderError
from app.providers.deepseek import DeepSeekProvider
from app.schemas.semantic.analysis_request import SemanticAnalysisRequest

MINIMAL_RESULT = {
    "overall_responsibility": "模块总览",
    "semantic_groups": [{
        "group_id": "g1", "order": 1, "title": "总览",
        "target_kind": "SYMBOL", "summary": "总览",
        "content_markdown": "## 总览\n内容",
        "primary_atom_ids": ["PYTHON:a.py:A:CLASS"],
        "informed_by_atom_ids": [],
        "evidence_refs": [{"atom_id": "PYTHON:a.py:A:CLASS"}],
    }],
    "member_interpretations": [],
    "execution_flow": [],
}


def _request() -> SemanticAnalysisRequest:
    return SemanticAnalysisRequest(
        analysis_id="a1", context_id="c1", revision="r1", snapshot_hash="h1",
        entry_point_ids=[], structure_block_ids=[],
        manifest={"atom_count": 0, "block_count": 0, "chunk_count": 0, "relation_count": 0},
        instruction="分析", output_contract={"semantic_groups": []},
    )


def _provider(*, content: str = "", reasoning: str = "") -> DeepSeekProvider:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={
            "choices": [{
                "message": {"content": content, "reasoning_content": reasoning},
                "finish_reason": "stop",
            }],
            "usage": {"prompt_tokens": 100},
        })

    return DeepSeekProvider(
        api_key="sk-test",
        base_url="https://model.invalid",
        model="deepseek-v4-flash",
        connect_timeout_seconds=1,
        total_timeout_seconds=2,
        transport=httpx.MockTransport(handler),
    )


@pytest.mark.asyncio
async def test_reasoning_fallback_returns_result() -> None:
    """content 空、reasoning 含完整 JSON 时，从 reasoning 提取并成功返回。"""
    reasoning = (
        "我需要分析这个模块……\n"
        "思考草稿：\n"
        f"{json.dumps(MINIMAL_RESULT, ensure_ascii=False)}\n"
        "好的，最终答案如上。"
    )
    result = await _provider(content="", reasoning=reasoning).analyze_batch(
        _request(), []
    )
    assert result.overall_responsibility == "模块总览"
    assert len(result.semantic_groups) == 1


@pytest.mark.asyncio
async def test_reasoning_fallback_with_fenced_json() -> None:
    """reasoning 中 JSON 被 ```json fence 包裹时也能提取。"""
    reasoning = (
        "以下是思考过程，最终输出：\n"
        "```json\n"
        f"{json.dumps(MINIMAL_RESULT, ensure_ascii=False)}\n"
        "```\n"
        "完毕。"
    )
    result = await _provider(content="", reasoning=reasoning).analyze_batch(
        _request(), []
    )
    assert result.overall_responsibility == "模块总览"


@pytest.mark.asyncio
async def test_normal_content_takes_precedence() -> None:
    """content 正常时优先用 content，不触碰 reasoning。"""
    result = await _provider(
        content=json.dumps(MINIMAL_RESULT, ensure_ascii=False),
        reasoning="无用草稿",
    ).analyze_batch(_request(), [])
    assert result.overall_responsibility == "模块总览"


@pytest.mark.asyncio
async def test_both_empty_raises_invalid_response() -> None:
    """content 与 reasoning 都无可提取 JSON 时走变异重试并最终报错。"""
    with pytest.raises(ModelProviderError) as exc_info:
        await _provider(content="", reasoning="没有 JSON 的纯思考").analyze_batch(
            _request(), []
        )
    assert exc_info.value.code == "MODEL_INVALID_RESPONSE"
