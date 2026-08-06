import json
import logging
from importlib.resources import files
from typing import Any, cast

import httpx
from pydantic import ValidationError

from app.providers.base import ModelProviderError
from app.schemas.unit_plans import UnitPlan

LOGGER = logging.getLogger("devcollab.agent.deepseek")
# ── 语义分析 System Prompt（基座——所有批次共享）─────────────────────

_SEMANTIC_SYSTEM_PROMPT = """\
你是 DevCollab 的模块讲解员。你面向刚接手该模块的新同事，
以及随手查代码的任何人。

解读遵循三个层次，先讲业务、再落代码：
1. 业务层：这段代码在系统里承担什么职责，谁调用它、它调用谁
2. 实现层：核心符号怎么协作完成这个职责
3. 边界层：它明确不做什么、依赖什么外部设施

功能声明规则（强制）：
- 每个解释的第一句是该符号的功能声明：一句正式的书面说明，
  讲清"这个类/函数的功能是什么"——做什么、输入是什么、输出是什么
- 功能声明不铺垫、不绕圈、不用口语
- 好的示例：创建订单——校验商品与库存后落库，返回订单号
- 坏的示例：本函数负责对传入的请求对象进行一系列校验操作并最终返回响应结果
  （无信息量，等于没说）
- 展开细节在功能声明之后

排版规则（强制）：
- 一个职责一个段落，段落间空行，禁止一大段连排
- 字段/参数/状态码用"名称：说明"列表（- 名称：说明），禁止用 | 表格符号
- 示例用代码块（不超过 15 行）
- 步骤/流程用有序列表（1. 2. 3.）
- 标题只使用给定的槽位标题，不自行发明层级

证据纪律（强制）：
- 正文中的符号名/路由/字段/状态码，必须来自你实际读取到的内容
- 没有代码证据的断言禁止写；确需提示时写"未在代码中体现"
- 没读全的地方明说"此部分未深入"，禁止推测
- 引用符号用工具返回里照抄的 symbol_key，禁止自行发明 ID、file_path、行号

排版示例（速查槽位的正文形态）：

## OrderService

OrderService：创建与取消订单的业务门面。

方法说明：
- create：校验商品与库存后落库，返回订单号
- cancel：取消未发货订单，恢复库存

**需要注意**：校验失败时抛 ValidationError（service.py L12-18）。

排版示例（主要流程槽位的正文形态）：

## 主要流程：POST /orders

create_order_route：创建订单的 HTTP 入口。

请求处理流程：
1. 接收 OrderRequest，校验商品列表非空、数量大于 0
2. 调用 create_order() 校验库存并落库
3. 返回订单号与金额

**需要注意**：库存校验失败返回 400 且不落库（service.py L12-18）。

输出契约（强制）：
- 最终输出必须是符合输出契约的 JSON 对象；所有 markdown 正文写入
  content_markdown 字段
- 禁止输出 JSON 以外的任何文本、markdown 文档或解释
"""

# ── 上下文 MCP 工具定义 ──────────────────────────────────────────────

_SEMANTIC_TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "get_context_overview",
            "description": (
                "模块全景：入口、原子数、结构块目录、裁剪报告。"
                "先回答：这个模块是干什么的？入口列表就是业务能力列表。第一步必须调用。"
            ),
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
            "description": (
                "读取一个结构块：一个入口的跨文件故事（源码、原子列表）。"
                "沿调用链看它做了什么。主要阅读单位。"
            ),
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
            "description": (
                "读取一个符号的详细信息：完整源码、入边、出边。"
                "深入关键链路或填写符号解释前必须调用。"
            ),
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
            "description": "追踪从入口到目标原子的结构路径。理清数据流用。",
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
            "description": "按名称在模块内搜索符号。",
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

    async def analyze_batch(
        self,
        request: Any,
        inline_sources: list[dict[str, Any]],
    ) -> Any:
        """批次静态生成：槽位源码内联进请求，一次调用直接输出 JSON。

        不启动工具循环——批次的目标符号是程序确定的（槽位清单），
        源码已在快照中；工具循环在快速模型下会陷入重复 get_atom_detail
        死循环（实测 20+ 次调用不收敛），内联后从机制上消除。
        """
        from app.schemas.semantic.analysis_result import SemanticAnalysisResult

        self._require_configuration()
        user_payload = {
            "instruction": request.instruction,
            "output_contract": request.output_contract,
            "inline_sources": inline_sources,
        }
        messages = [
            {"role": "system", "content": _SEMANTIC_SYSTEM_PROMPT},
            {"role": "user", "content": json.dumps(user_payload, ensure_ascii=False)},
        ]
        body: dict[str, Any] = {
            "model": self._model,
            "temperature": 0,
            "messages": messages,
            "response_format": {"type": "json_object"},
        }
        if self._thinking:
            body["thinking"] = {"type": "enabled"}
        try:
            async with httpx.AsyncClient(
                timeout=self._timeout, transport=self._transport
            ) as client:
                response = await client.post(
                    f"{self._base_url}/chat/completions",
                    headers={"Authorization": f"Bearer {self._api_key}"},
                    json=body,
                )
        except httpx.TimeoutException as exc:
            raise ModelProviderError("MODEL_TIMEOUT", "Batch analysis timed out") from exc
        except httpx.HTTPError as exc:
            raise ModelProviderError("MODEL_UNAVAILABLE", "Batch analysis request failed") from exc
        if response.status_code == 429:
            raise ModelProviderError("MODEL_RATE_LIMITED", "Model rate limit exceeded")
        if response.status_code >= 500:
            raise ModelProviderError("MODEL_UNAVAILABLE", "Model service is unavailable")
        if response.status_code >= 400:
            raise ModelProviderError("MODEL_CONFIGURATION_ERROR", "Model request was rejected")
        try:
            response_json = response.json()
            content = response_json["choices"][0]["message"]["content"]
            payload = _normalize_payload(_extract_json_object(content))
            if not isinstance(payload, dict):
                raise ValueError("batch response must be a JSON object")
            payload["analysis_id"] = request.analysis_id
            payload["context_id"] = request.context_id
            payload["revision"] = request.revision
            payload["snapshot_hash"] = request.snapshot_hash
            return SemanticAnalysisResult.model_validate(payload)
        except Exception as exc:
            LOGGER.error(
                "BATCH PARSE FAILED: len=%s err=%s", len(content), exc,
            )
            raise ModelProviderError(
                "MODEL_INVALID_RESPONSE",
                f"Could not parse batch SemanticAnalysisResult: {exc}",
            ) from exc

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
                # 强制 JSON 输出：模型常把"填写正文"理解为直接输出 markdown 文档，
                # json_object 模式保证最终输出是 JSON（正文放进 content_markdown 字段）
                "response_format": {"type": "json_object"},
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
                # 这些是程序掌握的可信上下文元数据，模型只负责语义字段。
                # 模型输出格式可能变化（camelCase、嵌套、多余字段），
                # 先结构归一化再校验，让程序消化格式而非逼模型精确。
                payload = _normalize_payload(_extract_json_object(content))
                if not isinstance(payload, dict):
                    raise ValueError("semantic response must be a JSON object")
                payload["analysis_id"] = request.analysis_id
                payload["context_id"] = request.context_id
                payload["revision"] = request.revision
                payload["snapshot_hash"] = request.snapshot_hash
                return SemanticAnalysisResult.model_validate(payload)
            except Exception as exc:
                LOGGER.error(
                    "SEMANTIC PARSE FAILED: len=%s head=%r tail=%r err=%s",
                    len(content),
                    content[:300] if isinstance(content, str) else content,
                    content[-300:] if isinstance(content, str) else content,
                    exc,
                )
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


def _extract_json_object(content: str) -> dict[str, Any]:
    """从模型输出中稳健提取 JSON 对象。

    模型可能返回:
      - 纯 JSON:            {"a": 1}
      - markdown 代码块:    ```json\n{"a": 1}\n```
      - 前置说明 + JSON:    分析如下...\n```json\n{...}\n```
      - 带 JSON 后缀文本:    {...}\n以上是分析

    策略（按可靠性排序）:
      1. 直接整体解析
      2. 用平衡括号扫描定位最外层 {...} 区间（正确处理字符串内的大括号，
         不依赖 markdown 围栏——模型可能嵌套反引号或在文本里放 { }）
      3. 尝试剥离 markdown 代码块围栏再整体解析
    """
    candidate = content.strip()
    try:
        parsed = json.loads(candidate)
        if isinstance(parsed, dict):
            return parsed
    except (json.JSONDecodeError, TypeError):
        pass

    # 平衡括号扫描：在字符串感知下匹配最外层 {...}。逐个 { 位置尝试，
    # 优先选择能解析成 dict 的候选。
    start = candidate.find("{")
    while start != -1:
        depth = 0
        in_string = False
        escape = False
        for i in range(start, len(candidate)):
            ch = candidate[i]
            if in_string:
                if escape:
                    escape = False
                elif ch == "\\":
                    escape = True
                elif ch == '"':
                    in_string = False
                continue
            if ch == '"':
                in_string = True
            elif ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    try:
                        parsed = json.loads(candidate[start:i + 1])
                        if isinstance(parsed, dict):
                            return parsed
                    except (json.JSONDecodeError, TypeError):
                        break  # 该候选无效，尝试下一个 { 位置
        start = candidate.find("{", start + 1)

    # 兜底：剥离 markdown 代码块围栏（支持 ```json / ``` 等）
    import re as _re

    fenced = _re.search(r"```(?:json)?\s*(.*?)\s*```", candidate, _re.DOTALL)
    if fenced:
        try:
            parsed = json.loads(fenced.group(1))
            if isinstance(parsed, dict):
                return parsed
        except (json.JSONDecodeError, TypeError):
            pass

    raise ValueError("model output does not contain a valid JSON object")


def _normalize_payload(payload: dict[str, Any]) -> dict[str, Any]:
    """结构归一化：把模型输出转成 schema 期望的 snake_case 结构。

    大模型解读任意代码，输出键名可能是 camelCase（stepOrder/atomId）
    或 snake_case，还可能嵌套多余层级。这里递归地把键名统一为
    snake_case，使下游 Pydantic 校验能吸收模型的格式变化。
    """
    import re as _re

    def to_snake(name: str) -> str:
        s = _re.sub(r"(?<!^)(?=[A-Z])", "_", name).lower()
        return s

    def walk(value: Any) -> Any:
        if isinstance(value, dict):
            return {
                to_snake(k): walk(v) for k, v in value.items()
            }
        if isinstance(value, list):
            return [walk(v) for v in value]
        return value

    return walk(payload)
