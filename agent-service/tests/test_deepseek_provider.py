import json
from typing import Any

import httpx
import pytest
from pydantic import ValidationError

from app.config import DeepSeekModelName, Settings
from app.providers.base import ModelProviderError
from app.providers.deepseek import DeepSeekProvider


def no_change() -> dict[str, Any]:
    return {
        "decision": "NO_CHANGE",
        "summary": "No synchronization is needed",
        "rationale": "The implementation and document agree.",
        "operations": [],
        "bindingProposals": [],
        "evidence": [],
    }


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


@pytest.mark.asyncio
async def test_deepseek_parses_valid_json_and_uses_authorization_header() -> None:
    seen: dict[str, str] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["authorization"] = request.headers["Authorization"]
        return response(no_change())

    result = await provider(handler).plan_document_sync({"codeFiles": []})
    assert result.decision == "NO_CHANGE"
    assert seen["authorization"] == "Bearer secret-key"


@pytest.mark.asyncio
async def test_deepseek_timeout() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("timed out", request=request)

    with pytest.raises(ModelProviderError) as caught:
        await provider(handler).plan_document_sync({})
    assert caught.value.code == "MODEL_TIMEOUT"


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("status", "code"),
    [
        (429, "MODEL_RATE_LIMITED"),
        (500, "MODEL_UNAVAILABLE"),
        (503, "MODEL_UNAVAILABLE"),
        (400, "MODEL_CONFIGURATION_ERROR"),
    ],
)
async def test_deepseek_http_error_mapping(status: int, code: str) -> None:
    with pytest.raises(ModelProviderError) as caught:
        await provider(lambda request: httpx.Response(status)).plan_document_sync({})
    assert caught.value.code == code


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "payload",
    [
        b"not-json",
        json.dumps({"choices": []}).encode(),
        json.dumps({"choices": [{"message": {"content": "not-json"}}]}).encode(),
        json.dumps(
            {"choices": [{"message": {"content": json.dumps({"decision": "NO_CHANGE"})}}]}
        ).encode(),
    ],
)
async def test_deepseek_invalid_response(payload: bytes) -> None:
    with pytest.raises(ModelProviderError) as caught:
        await provider(
            lambda request: httpx.Response(
                200, content=payload, headers={"content-type": "application/json"}
            )
        ).plan_document_sync({})
    assert caught.value.code == "MODEL_INVALID_RESPONSE"


@pytest.mark.asyncio
async def test_deepseek_error_never_contains_key() -> None:
    with pytest.raises(ModelProviderError) as caught:
        await provider(lambda request: httpx.Response(500), "top-secret").plan_document_sync({})
    assert "top-secret" not in str(caught.value)


@pytest.mark.asyncio
async def test_deepseek_repair_payload_contains_safe_validation_errors() -> None:
    seen: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen.update(json.loads(request.content))
        return response(no_change())

    await provider(handler).plan_document_sync(
        {"codeFiles": []},
        previous_plan=no_change(),
        validation_errors=[{"path": "operations", "code": "BAD", "message": "fix"}],
    )
    user = json.loads(seen["messages"][1]["content"])
    assert user["agentPlanSchema"]["title"] == "AgentPlan"
    assert user["repair"]["validationErrors"][0]["code"] == "BAD"


@pytest.mark.asyncio
async def test_deepseek_requires_configuration() -> None:
    empty = DeepSeekProvider(
        api_key="",
        base_url="",
        model="",
        connect_timeout_seconds=1,
        total_timeout_seconds=2,
    )
    with pytest.raises(ModelProviderError) as caught:
        await empty.plan_document_sync({})
    assert caught.value.code == "MODEL_CONFIGURATION_ERROR"


@pytest.mark.asyncio
async def test_deepseek_project_planner_uses_separate_unit_plan_schema() -> None:
    seen: dict[str, Any] = {}
    unit_plan = {
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

    def handler(request: httpx.Request) -> httpx.Response:
        seen.update(json.loads(request.content))
        return response(unit_plan)

    result = await provider(handler).plan_project_units(
        {
            "repositoryId": "repo",
            "revision": "abc",
            "files": [{"filePath": "src/AuthService.java"}],
            "documents": [],
        }
    )
    user = json.loads(seen["messages"][1]["content"])
    assert result.units[0].name == "认证服务"
    assert user["unitPlanSchema"]["title"] == "UnitPlan"
    assert "agentPlanSchema" not in user


@pytest.mark.asyncio
async def test_deepseek_binding_pass_uses_only_binding_plan_schema() -> None:
    seen: dict[str, Any] = {}
    binding_plan = {
        "selections": [
            {
                "blockKey": "block_endpoint",
                "codeCandidateId": "code_candidate_123",
                "role": "PRIMARY",
                "ordinal": 1,
                "reason": "代码职责与文档块内容一致。",
                "confidence": 0.93,
            }
        ]
    }

    def handler(request: httpx.Request) -> httpx.Response:
        seen.update(json.loads(request.content))
        return response(binding_plan)

    result = await provider(handler).plan_block_bindings(
        {
            "codeCandidates": [{"candidateId": "code_candidate_123"}],
            "documentAnchorCandidates": [{"candidateId": "doc_candidate_456"}],
            "documentBlockPlans": [{"blockKey": "block_endpoint"}],
        }
    )
    user = json.loads(seen["messages"][1]["content"])
    assert result.selections[0].codeCandidateId == "code_candidate_123"
    assert result.selections[0].blockKey == "block_endpoint"
    assert user["bindingPlanSchema"]["title"] == "BindingPlan"
    assert "agentPlanSchema" not in user
    assert "unitPlanSchema" not in user


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
        return response(no_change())

    p = DeepSeekProvider(
        api_key="key",
        base_url="https://model.invalid",
        model="deepseek-v4-flash",
        connect_timeout_seconds=1,
        total_timeout_seconds=2,
        thinking=True,
        transport=httpx.MockTransport(handler),
    )
    await p.plan_document_sync({"codeFiles": []})
    assert seen["thinking"] == {"type": "enabled"}


@pytest.mark.asyncio
async def test_deepseek_thinking_disabled_excludes_body_field() -> None:
    seen: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen.update(json.loads(request.content))
        return response(no_change())

    p = DeepSeekProvider(
        api_key="key",
        base_url="https://model.invalid",
        model="deepseek-v4-flash",
        connect_timeout_seconds=1,
        total_timeout_seconds=2,
        thinking=False,
        transport=httpx.MockTransport(handler),
    )
    await p.plan_document_sync({"codeFiles": []})
    assert "thinking" not in seen


def test_deepseek_thinking_defaults_false() -> None:
    settings = Settings(deepseek_model="flash", _env_file=None)  # type: ignore[call-arg]
    assert settings.deepseek_thinking is False
