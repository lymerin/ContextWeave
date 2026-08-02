import json
import logging
from importlib.resources import files
from typing import Any, cast

import httpx
from pydantic import ValidationError

from app.providers.base import ModelProviderError
from app.schemas.unit_plans import UnitPlan

LOGGER = logging.getLogger("devcollab.agent.deepseek")
# ── 语义分析 System Prompt ───────────────────────────────────────────

_SEMANTIC_SYSTEM_PROMPT = """\
你是 DevCollab 的语义分析器。你会收到一个代码上下文快照的引用信息。

你必须通过工具调用按顺序读取:
1. get_context_overview — 了解代码范围
2. get_structure_block — 读取全部结构块（主要阅读单位）
3. get_atom_detail / trace_structure_path / search_context_symbols — 按需深入

读取完全部结构块后，输出 SemanticAnalysisResult JSON。

规则:
- 所有引用使用 atom_id，不自行填写 file_path 或行号
- primary_atom_ids 非空且是 informed_by_atom_ids 的子集
- 将紧密相关的符号（路由+请求模型+业务方法）合并为一个 semantic_group
- evidence_refs 中的 atom_id / relation_id / source_chunk_id 必须是你实际读取到的
"""

# ── 上下文 MCP 工具定义 ──────────────────────────────────────────────

_SEMANTIC_TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "get_context_overview",
            "description": "获取上下文快照的总览：入口、原子数、结构块目录。第一步必须调用。",
            "parameters": {
                "type": "object",
                "properties": {"context_id": {"type": "string"}},
                "required": ["context_id"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_structure_block",
            "description": "读取一个结构块的完整内容：源码、原子列表、跨块关系。主要阅读单位。",
            "parameters": {
                "type": "object",
                "properties": {
                    "context_id": {"type": "string"},
                    "block_id": {"type": "string"},
                },
                "required": ["context_id", "block_id"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_atom_detail",
            "description": "读取一个原子的详细信息：完整源码、入边、出边。",
            "parameters": {
                "type": "object",
                "properties": {
                    "context_id": {"type": "string"},
                    "symbol_key": {"type": "string"},
                },
                "required": ["context_id", "symbol_key"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "trace_structure_path",
            "description": "追踪从入口到目标原子的结构路径。",
            "parameters": {
                "type": "object",
                "properties": {
                    "context_id": {"type": "string"},
                    "entry_label": {"type": "string"},
                },
                "required": ["context_id", "entry_label"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "search_context_symbols",
            "description": "在当前快照内按名称搜索符号。",
            "parameters": {
                "type": "object",
                "properties": {
                    "context_id": {"type": "string"},
                    "query": {"type": "string"},
                },
                "required": ["context_id", "query"],
            },
        },
    },
]

class DeepSeekProvider:
    def __init__(
        self,
        *,
        api_key: str,
        base_url: str,
        model: str,
        connect_timeout_seconds: float,
        request_timeout_seconds: float | None = None,
        total_timeout_seconds: float | None = None,
        thinking: bool = False,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self._api_key = api_key
        self._base_url = base_url.rstrip("/")
        self._model = model
        self._thinking = thinking
        effective_timeout = request_timeout_seconds or total_timeout_seconds
        if effective_timeout is None:
            raise ValueError("A model request timeout is required")
        self._timeout = httpx.Timeout(
            effective_timeout,
            connect=connect_timeout_seconds,
        )
        self._transport = transport
        self._unit_planning_prompt = (
            files("app.prompts")
            .joinpath("project_unit_planning_v1.md")
            .read_text(encoding="utf-8")
        )


    # ── PROJECT_DISCOVERY（保留）───────────────────────────────────

    async def plan_project_units(
        self,
        project_index: dict[str, Any],
        *,
        previous_plan: dict[str, Any] | None = None,
        validation_errors: list[dict[str, str]] | None = None,
    ) -> UnitPlan:
        self._require_configuration()
        user_payload: dict[str, Any] = {
            "unitPlanSchema": UnitPlan.model_json_schema(),
            "projectIndex": project_index,
        }
        if previous_plan is not None:
            user_payload["repair"] = {
                "previousPlan": previous_plan,
                "validationErrors": validation_errors or [],
                "instruction": "只返回修正后的完整 UnitPlan JSON，不要解释。",
            }
        return cast(
            UnitPlan,
            await self._request_unit_plan(
                system_prompt=self._unit_planning_prompt,
                user_payload=user_payload,
            ),
        )

    async def _request_unit_plan(
        self,
        *,
        system_prompt: str,
        user_payload: dict[str, Any],
    ) -> UnitPlan:
        body: dict[str, Any] = {
            "model": self._model,
            "temperature": 0,
            "response_format": {"type": "json_object"},
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": json.dumps(user_payload, ensure_ascii=False, separators=(",", ":"))},
            ],
        }
        if self._thinking:
            body["thinking"] = {"type": "enabled"}
        try:
            async with httpx.AsyncClient(timeout=self._timeout, transport=self._transport) as client:
                response = await client.post(
                    f"{self._base_url}/chat/completions",
                    headers={"Authorization": f"Bearer {self._api_key}"},
                    json=body,
                )
        except httpx.TimeoutException as exc:
            raise ModelProviderError("MODEL_TIMEOUT", "Model request timed out") from exc
        except httpx.HTTPError as exc:
            raise ModelProviderError("MODEL_UNAVAILABLE", "Model request failed") from exc
        if response.status_code == 429:
            raise ModelProviderError("MODEL_RATE_LIMITED", "Model rate limit exceeded")
        if response.status_code >= 500:
            raise ModelProviderError("MODEL_UNAVAILABLE", "Model service is unavailable")
        if response.status_code >= 400:
            raise ModelProviderError("MODEL_CONFIGURATION_ERROR", "Model request was rejected")
        raw_plan: dict[str, Any] | None = None
        try:
            response_json = response.json()
            content = response_json["choices"][0]["message"]["content"]
            decoded = json.loads(content)
            if not isinstance(decoded, dict):
                raise TypeError("UnitPlan must be an object")
            raw_plan = decoded
            return UnitPlan.model_validate(raw_plan)
        except ValidationError as exc:
            validation_errors = [
                {"path": ".".join(str(p) for p in e["loc"]) or "$",
                 "code": f"MODEL_SCHEMA_{str(e['type']).upper()}",
                 "message": str(e["msg"])[:300]}
                for e in exc.errors(include_url=False)
            ]
            raise ModelProviderError("MODEL_INVALID_RESPONSE", "Model returned an invalid UnitPlan", raw_plan=raw_plan, validation_errors=validation_errors) from exc
        except (KeyError, IndexError, TypeError, json.JSONDecodeError) as exc:
            code = "MODEL_JSON_DECODE_ERROR" if isinstance(exc, json.JSONDecodeError) else "MODEL_RESPONSE_CONTENT_INVALID"
            raise ModelProviderError("MODEL_INVALID_RESPONSE", "Model response could not be parsed as UnitPlan", raw_plan=raw_plan, validation_errors=[{"path": "$", "code": code, "message": "Model response could not be parsed as UnitPlan"}]) from exc

    async def analyze_semantics(
        self,
        request: Any,
        tool_handler: Any,
    ) -> Any:
        """运行语义分析会话: 发送请求 → 处理工具调用 → 返回结果。

        tool_handler 接收 (tool_name, arguments) → 返回工具结果字典。
        自动处理工具调用循环，直到模型输出最终内容。
        """
        from app.schemas.semantic.analysis_result import SemanticAnalysisResult

        self._require_configuration()
        system_prompt = _SEMANTIC_SYSTEM_PROMPT
        messages: list[dict[str, Any]] = [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": request.model_dump_json()},
        ]
        tools = _SEMANTIC_TOOLS

        max_turns = 20
        for _turn in range(max_turns):
            body: dict[str, Any] = {
                "model": self._model,
                "temperature": 0,
                "messages": messages,
                "tools": tools,
            }
            if self._thinking:
                body["thinking"] = {"type": "enabled"}

            try:
                async with httpx.AsyncClient(
                    timeout=self._timeout,
                    transport=self._transport,
                ) as client:
                    response = await client.post(
                        f"{self._base_url}/chat/completions",
                        headers={"Authorization": f"Bearer {self._api_key}"},
                        json=body,
                    )
            except httpx.TimeoutException as exc:
                raise ModelProviderError("MODEL_TIMEOUT", "Semantic analysis timed out") from exc
            except httpx.HTTPError as exc:
                raise ModelProviderError("MODEL_UNAVAILABLE", "Semantic analysis request failed") from exc

            if response.status_code >= 400:
                raise ModelProviderError("MODEL_UNAVAILABLE", f"Model returned {response.status_code}")

            msg = response.json()
            choice = msg.get("choices", [{}])[0]
            message = choice.get("message", {})

            # 检查 tool_calls
            tool_calls = message.get("tool_calls") or []
            if tool_calls:
                messages.append(message)
                for tc in tool_calls:
                    func = tc.get("function", {})
                    tool_name = func.get("name", "")
                    try:
                        arguments = json.loads(func.get("arguments", "{}"))
                    except json.JSONDecodeError:
                        arguments = {}
                    result = tool_handler(tool_name, arguments)
                    if hasattr(result, '__await__'):
                        result = await result
                    messages.append({
                        "role": "tool",
                        "tool_call_id": tc.get("id", ""),
                        "content": json.dumps(result, ensure_ascii=False),
                    })
                continue

            # 最终输出
            content = message.get("content", "")
            if not content:
                raise ModelProviderError(
                    "MODEL_INVALID_RESPONSE",
                    "Empty model response",
                )
            try:
                return SemanticAnalysisResult.model_validate_json(content)
            except Exception as exc:
                raise ModelProviderError(
                    "MODEL_INVALID_RESPONSE",
                    f"Could not parse SemanticAnalysisResult: {exc}",
                ) from exc

        raise ModelProviderError(
            "MODEL_INVALID_RESPONSE",
            f"Exceeded {max_turns} tool-calling turns",
        )

    def _require_configuration(self) -> None:
        if not self._api_key or not self._base_url or not self._model:
            raise ModelProviderError(
                "MODEL_CONFIGURATION_ERROR",
                "DeepSeek provider is not configured",
            )
