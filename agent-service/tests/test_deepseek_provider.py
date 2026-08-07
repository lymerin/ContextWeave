import json
from typing import Any

import httpx
import pytest
from pydantic import ValidationError

from app.config import DeepSeekModelName, Settings
from app.providers.deepseek import DeepSeekProvider


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
