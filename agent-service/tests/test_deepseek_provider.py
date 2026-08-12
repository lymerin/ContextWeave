import json
from typing import Any

import httpx
import pytest
from pydantic import ValidationError

from app.config import DeepSeekModelName, Settings
from app import perf_metrics
from app.providers.base import ModelProviderError
from app.providers.deepseek import DeepSeekProvider
from app.schemas.semantic.analysis_request import SemanticAnalysisRequest


def provider(handler: Any, key: str = "secret-key") -> DeepSeekProvider:
    return DeepSeekProvider(
        api_key=key,
        base_url="https://model.invalid",
        model="deepseek-chat",
        connect_timeout_seconds=1,
        total_timeout_seconds=2,
        transport=httpx.MockTransport(handler),
    )


def response(plan: dict[str, Any]) -> httpx.Response:
    return httpx.Response(
        200,
        json={"choices": [{"message": {"content": json.dumps(plan)}}]},
    )


def unit_plan() -> dict[str, Any]:
    return {
        "units": [
            {
                "name": "认证服务",
                "kind": "BUSINESS_SERVICE",
                "summary": "描述认证服务职责。",
                "primaryFiles": ["src/AuthService.java"],
                "supportingFiles": [],
                "relatedDocumentIds": [],
                "groupingEvidence": ["SERVICE roleHint"],
            }
        ]
    }


PROJECT_INDEX: dict[str, Any] = {
    "repositoryId": "repo",
    "revision": "abc",
    "files": [{"filePath": "src/AuthService.java"}],
    "documents": [],
}

MINIMAL_ANALYSIS_RESULT: dict[str, Any] = {
    "overall_responsibility": "module summary",
    "semantic_groups": [],
    "member_interpretations": [],
    "execution_flow": [],
}


@pytest.mark.asyncio
async def test_deepseek_project_planner_uses_separate_unit_plan_schema() -> None:
    seen: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen.update(json.loads(request.content))
        return response(unit_plan())

    result = await provider(handler).plan_project_units(PROJECT_INDEX)
    user = json.loads(seen["messages"][1]["content"])
    assert result.units[0].name == "认证服务"
    assert user["unitPlanSchema"]["title"] == "UnitPlan"
    assert "agentPlanSchema" not in user


# ── Model name validation ─────────────────────────────────────────────


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("deepseek-v4-flash", "deepseek-v4-flash"),
        ("deepseekflash", "deepseek-v4-flash"),
        ("deepseek-flash", "deepseek-v4-flash"),
        ("deepseek_flash", "deepseek-v4-flash"),
        ("flash", "deepseek-v4-flash"),
        ("DEEPSEEK-V4-FLASH", "deepseek-v4-flash"),
        ("  Flash  ", "deepseek-v4-flash"),
        ("deepseek-v4-pro", "deepseek-v4-pro"),
        ("deepseekpro", "deepseek-v4-pro"),
        ("deepseek-pro", "deepseek-v4-pro"),
        ("deepseek_pro", "deepseek-v4-pro"),
        ("pro", "deepseek-v4-pro"),
        ("PRO", "deepseek-v4-pro"),
    ],
)
def test_model_name_normalized(raw: str, expected: str) -> None:
    settings = Settings(
        deepseek_model=raw,
        _env_file=None,  # type: ignore[call-arg]
    )
    assert settings.deepseek_model == expected


def test_empty_model_name_allowed() -> None:
    settings = Settings(deepseek_model="", _env_file=None)  # type: ignore[call-arg]
    assert settings.deepseek_model == ""


@pytest.mark.parametrize(
    "bad_name",
    ["deepseek-chat", "gpt-4o", "deepseek-v3", "random-name"],
)
def test_invalid_model_name_rejected(bad_name: str) -> None:
    with pytest.raises(ValidationError, match="Invalid DeepSeek model"):
        Settings(deepseek_model=bad_name, _env_file=None)  # type: ignore[call-arg]


def test_deepseek_model_enum_values() -> None:
    assert DeepSeekModelName.FLASH == "deepseek-v4-flash"
    assert DeepSeekModelName.PRO == "deepseek-v4-pro"


# ── Thinking parameter ────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_deepseek_thinking_enabled_adds_body_field() -> None:
    seen: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen.update(json.loads(request.content))
        return response(unit_plan())

    p = DeepSeekProvider(
        api_key="key",
        base_url="https://model.invalid",
        model="deepseek-v4-flash",
        connect_timeout_seconds=1,
        total_timeout_seconds=2,
        thinking=True,
        transport=httpx.MockTransport(handler),
    )
    await p.plan_project_units(PROJECT_INDEX)
    assert seen["thinking"] == {"type": "enabled"}


@pytest.mark.asyncio
async def test_deepseek_thinking_disabled_excludes_body_field() -> None:
    seen: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen.update(json.loads(request.content))
        return response(unit_plan())

    p = DeepSeekProvider(
        api_key="key",
        base_url="https://model.invalid",
        model="deepseek-v4-flash",
        connect_timeout_seconds=1,
        total_timeout_seconds=2,
        thinking=False,
        transport=httpx.MockTransport(handler),
    )
    await p.plan_project_units(PROJECT_INDEX)
    assert "thinking" not in seen


def test_deepseek_thinking_defaults_false() -> None:
    settings = Settings(deepseek_model="flash", _env_file=None)  # type: ignore[call-arg]
    assert settings.deepseek_thinking is False


@pytest.mark.asyncio
async def test_batch_analysis_puts_stable_context_before_instruction() -> None:
    seen: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen.update(json.loads(request.content))
        return httpx.Response(
            200,
            json={
                "choices": [{
                    "message": {
                        "content": json.dumps(MINIMAL_ANALYSIS_RESULT)
                    }
                }],
            },
        )

    await provider(handler).analyze_batch(
        SemanticAnalysisRequest(
            analysis_id="analysis-1",
            context_id="context-1",
            revision="rev-1",
            snapshot_hash="hash-1",
            entry_point_ids=[],
            structure_block_ids=[],
            manifest={
                "atom_count": 0,
                "block_count": 0,
                "chunk_count": 0,
                "relation_count": 0,
            },
            instruction="write this slot",
            output_contract={"type": "object"},
        ),
        [{
            "file_path": "src/App.java",
            "source": "class App {}",
        }],
    )

    stable = json.loads(seen["messages"][1]["content"])
    task = json.loads(seen["messages"][2]["content"])
    assert "inline_sources" in stable
    assert stable["context"]["snapshot_hash"] == "hash-1"
    assert "instruction" not in stable
    assert task == {"instruction": "write this slot"}


@pytest.mark.asyncio
async def test_batch_analysis_records_deepseek_cache_usage() -> None:
    perf_metrics.reset()

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "choices": [{
                    "message": {
                        "content": json.dumps(MINIMAL_ANALYSIS_RESULT)
                    }
                }],
                "usage": {
                    "prompt_tokens": 100,
                    "completion_tokens": 20,
                    "total_tokens": 120,
                    "prompt_cache_hit_tokens": 80,
                    "prompt_cache_miss_tokens": 20,
                },
            },
        )

    await provider(handler).analyze_batch(
        SemanticAnalysisRequest(
            analysis_id="analysis-1",
            context_id="context-1",
            revision="rev-1",
            snapshot_hash="hash-1",
            entry_point_ids=[],
            structure_block_ids=[],
            manifest={
                "atom_count": 0,
                "block_count": 0,
                "chunk_count": 0,
                "relation_count": 0,
            },
            instruction="write this slot",
            output_contract={"type": "object"},
        ),
        [],
    )

    model = perf_metrics.snapshot()["model"]
    assert model["usage_count"] == 1
    assert model["prompt_tokens"] == 100
    assert model["completion_tokens"] == 20
    assert model["total_tokens"] == 120
    assert model["prompt_cache_hit_tokens"] == 80
    assert model["prompt_cache_miss_tokens"] == 20


@pytest.mark.asyncio
async def test_batch_analysis_repair_keeps_stable_context_unchanged() -> None:
    stable_messages: list[str] = []
    task_messages: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        stable_messages.append(body["messages"][1]["content"])
        task_messages.append(body["messages"][2]["content"])
        return httpx.Response(
            200,
            json={
                "choices": [{
                    "message": {"content": "not json"},
                    "finish_reason": "stop",
                }],
            },
        )

    with pytest.raises(ModelProviderError):
        await provider(handler).analyze_batch(
            SemanticAnalysisRequest(
                analysis_id="analysis-1",
                context_id="context-1",
                revision="rev-1",
                snapshot_hash="hash-1",
                entry_point_ids=[],
                structure_block_ids=[],
                manifest={
                    "atom_count": 0,
                    "block_count": 0,
                    "chunk_count": 0,
                    "relation_count": 0,
                },
                instruction="write this slot",
                output_contract={"type": "object"},
            ),
            [{"file_path": "src/App.java", "source": "class App {}"}],
        )

    assert len(stable_messages) == 3
    assert len(set(stable_messages)) == 1
    assert task_messages[0] != task_messages[1]
    assert task_messages[1] != task_messages[2]
