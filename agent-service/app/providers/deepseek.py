import json
import logging
from importlib.resources import files
from typing import Any, cast

import httpx
from pydantic import ValidationError

from app.providers.base import ModelProviderError
from app.schemas.binding_plans import BindingPlan
from app.schemas.document_block_content import (
    DocumentBlockContent,
    DocumentBlockContentPlan,
)
from app.schemas.plans import AgentPlan
from app.schemas.unit_plans import UnitPlan

LOGGER = logging.getLogger("devcollab.agent.deepseek")


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
        self._system_prompt = (
            files("app.prompts").joinpath("document_sync_v1.md").read_text(encoding="utf-8")
        )
        self._unit_planning_prompt = (
            files("app.prompts")
            .joinpath("project_unit_planning_v1.md")
            .read_text(encoding="utf-8")
        )
        self._block_binding_prompt = (
            files("app.prompts").joinpath("block_binding_v1.md").read_text(encoding="utf-8")
        )
        self._document_block_content_prompt = (
            files("app.prompts")
            .joinpath("document_block_content_v1.md")
            .read_text(encoding="utf-8")
        )

    async def generate_document_blocks(
        self,
        context: dict[str, Any],
    ) -> DocumentBlockContentPlan:
        self._require_configuration()
        return cast(
            DocumentBlockContentPlan,
            await self._request_structured(
                system_prompt=self._document_block_content_prompt,
                user_payload={
                    "documentBlockContentPlanSchema": (
                        DocumentBlockContentPlan.model_json_schema()
                    ),
                    "context": context,
                },
                schema=DocumentBlockContentPlan,
                response_name="DocumentBlockContentPlan",
            ),
        )

    async def repair_document_block(
        self,
        context: dict[str, Any],
        *,
        previous_block: dict[str, Any],
        validation_errors: list[dict[str, str]],
    ) -> DocumentBlockContent:
        self._require_configuration()
        return cast(
            DocumentBlockContent,
            await self._request_structured(
                system_prompt=self._document_block_content_prompt,
                user_payload={
                    "documentBlockContentSchema": DocumentBlockContent.model_json_schema(),
                    "context": context,
                    "repair": {
                        "previousBlock": previous_block,
                        "validationErrors": validation_errors,
                        "instruction": (
                            "只重写当前 blockKey 的正文和合法 SUPPORTING 选择一次。"
                            "不得返回其他 Block，不得修改程序拥有的结构或 PRIMARY。"
                        ),
                    },
                },
                schema=DocumentBlockContent,
                response_name="DocumentBlockContent",
            ),
        )

    async def plan_document_sync(
        self,
        context_bundle: dict[str, Any],
        *,
        previous_plan: dict[str, Any] | None = None,
        validation_errors: list[dict[str, str]] | None = None,
    ) -> AgentPlan:
        self._require_configuration()
        user_payload: dict[str, Any] = {
            "agentPlanSchema": AgentPlan.model_json_schema(),
            "context": context_bundle,
        }
        if previous_plan is not None:
            block_constraints = [
                {
                    "blockKey": item.get("blockKey"),
                    "targetKind": item.get("targetKind"),
                    "sortOrder": item.get("sortOrder"),
                    "allowedPrimaryCandidateIds": item.get("primaryCandidateIds", []),
                    "allowedSupportingCandidateIds": item.get(
                        "supportingCandidateIds", []
                    ),
                    "requiredCandidateIds": item.get("requiredCandidateIds", []),
                    "allowedClaims": item.get("allowedClaims", []),
                    "forbiddenClaims": item.get("forbiddenClaims", []),
                }
                for item in context_bundle.get("documentBlockPlans", [])
            ]
            user_payload["repair"] = {
                "previousPlan": previous_plan,
                "validationErrors": validation_errors or [],
                "documentBlockConstraints": block_constraints,
                "invalidFieldPaths": sorted(
                    {
                        item.get("path", "$")
                        for item in validation_errors or []
                    }
                ),
                "instruction": (
                    "这是受约束修复，不是重新规划。保留未被 validationErrors 指出的有效字段，"
                    "只修正错误字段及其直接依赖字段。documentBlockConstraints 中每个 blockKey "
                    "必须原样且恰好出现一次，不得新增、删除、合并、改名或重排 Block；"
                    "targetKind、候选集合和 requiredCandidateIds 均不可修改。"
                    "正文只能陈述所选源码能够直接证明的职责；遇到 UNSUPPORTED_EXTERNAL_RELATION 或 "
                    "UNSUPPORTED_INFERRED_SEMANTICS 时，只重写对应 operation，删除无法由源码"
                    "直接证明的外部组件、业务含义、约束和保证。严格遵守每个 Block 的 "
                    "allowedClaims 与 forbiddenClaims。直接返回修正后的完整 AgentPlan JSON；"
                    "bindingProposals 必须为空。正式标题和正文必须"
                    "是可发布的简体中文最终内容，不要解释错误原因，不要输出建议、计划或占位文字。"
                ),
            }
        return cast(
            AgentPlan,
            await self._request_structured(
            system_prompt=self._system_prompt,
            user_payload=user_payload,
            schema=AgentPlan,
            response_name="AgentPlan",
            ),
        )

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
            await self._request_structured(
            system_prompt=self._unit_planning_prompt,
            user_payload=user_payload,
            schema=UnitPlan,
            response_name="UnitPlan",
            ),
        )

    async def plan_block_bindings(
        self,
        candidates: dict[str, Any],
        *,
        previous_plan: dict[str, Any] | None = None,
        validation_errors: list[dict[str, str]] | None = None,
    ) -> BindingPlan:
        self._require_configuration()
        user_payload: dict[str, Any] = {
            "bindingPlanSchema": BindingPlan.model_json_schema(),
            **candidates,
        }
        if previous_plan is not None:
            user_payload["repair"] = {
                "previousPlan": previous_plan,
                "validationErrors": validation_errors or [],
                "instruction": (
                    "只使用输入中现有候选 ID，返回修正后的完整 BindingPlan JSON。"
                    "不得新增路径、UUID、symbol 或行号。"
                ),
            }
        attempt = 2 if previous_plan is not None else 1
        code_count = len(candidates.get("codeCandidates", []))
        document_count = len(candidates.get("documentAnchorCandidates", []))
        LOGGER.info(
            "provider=deepseek model=%s operation=block_binding attempt=%s "
            "codeCandidates=%s documentCandidates=%s",
            self._model,
            attempt,
            code_count,
            document_count,
        )
        result = cast(
            BindingPlan,
            await self._request_structured(
                system_prompt=self._block_binding_prompt,
                user_payload=user_payload,
                schema=BindingPlan,
                response_name="BindingPlan",
            ),
        )
        LOGGER.info(
            "provider=deepseek model=%s operation=block_binding attempt=%s "
            "validation=success selections=%s",
            self._model,
            attempt,
            len(result.selections),
        )
        return result

    async def _request_structured(
        self,
        *,
        system_prompt: str,
        user_payload: dict[str, Any],
        schema: (
            type[AgentPlan]
            | type[UnitPlan]
            | type[BindingPlan]
            | type[DocumentBlockContentPlan]
            | type[DocumentBlockContent]
        ),
        response_name: str,
    ) -> (
        AgentPlan
        | UnitPlan
        | BindingPlan
        | DocumentBlockContentPlan
        | DocumentBlockContent
    ):
        body: dict[str, Any] = {
            "model": self._model,
            "temperature": 0,
            "response_format": {"type": "json_object"},
            "messages": [
                {"role": "system", "content": system_prompt},
                {
                    "role": "user",
                    "content": json.dumps(user_payload, ensure_ascii=False, separators=(",", ":")),
                },
            ],
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
            usage = response_json.get("usage")
            if isinstance(usage, dict):
                LOGGER.info(
                    "provider=deepseek model=%s response=%s "
                    "promptTokens=%s completionTokens=%s totalTokens=%s",
                    self._model,
                    response_name,
                    usage.get("prompt_tokens"),
                    usage.get("completion_tokens"),
                    usage.get("total_tokens"),
                )
            content = response_json["choices"][0]["message"]["content"]
            decoded = json.loads(content)
            if not isinstance(decoded, dict):
                raise TypeError("AgentPlan must be an object")
            raw_plan = decoded
            return schema.model_validate(raw_plan)
        except ValidationError as exc:
            validation_errors = [
                {
                    "path": ".".join(str(part) for part in item["loc"]) or "$",
                    "code": f"MODEL_SCHEMA_{str(item['type']).upper()}",
                    "message": str(item["msg"])[:300],
                }
                for item in exc.errors(include_url=False)
            ]
            raise ModelProviderError(
                "MODEL_INVALID_RESPONSE",
                f"Model returned an invalid {response_name}",
                raw_plan=raw_plan,
                validation_errors=validation_errors,
            ) from exc
        except (KeyError, IndexError, TypeError, json.JSONDecodeError) as exc:
            error_code = (
                "MODEL_JSON_DECODE_ERROR"
                if isinstance(exc, json.JSONDecodeError)
                else "MODEL_RESPONSE_CONTENT_INVALID"
            )
            raise ModelProviderError(
                "MODEL_INVALID_RESPONSE",
                f"Model returned an invalid {response_name}",
                raw_plan=raw_plan,
                validation_errors=[
                    {
                        "path": "$",
                        "code": error_code,
                        "message": f"Model response could not be parsed as {response_name}",
                    }
                ],
            ) from exc

    def _require_configuration(self) -> None:
        if not self._api_key or not self._base_url or not self._model:
            raise ModelProviderError(
                "MODEL_CONFIGURATION_ERROR",
                "DeepSeek provider is not configured",
            )
