from __future__ import annotations

import hashlib
from collections import defaultdict
from typing import Any

from app.planning.binding_candidates import BindingPlanValidationError
from app.schemas.binding_plans import (
    BindingPlan,
    BindingRole,
    BindingSelection,
    BlockTargetKind,
    CodeCandidate,
    DocumentBlockPlan,
)
from app.schemas.plans import AgentPlan, OperationType


class DocumentBlockPlanBuilder:
    """Build the small, evidence-backed block plan used by the model."""

    def build(self, candidates: tuple[CodeCandidate, ...]) -> tuple[DocumentBlockPlan, ...]:
        symbols = [item for item in candidates if item.anchorKind.value == "SYMBOL"]
        by_name: dict[str, list[CodeCandidate]] = defaultdict(list)
        for item in symbols:
            for name in {item.displayName, item.qualifiedName or ""}:
                if name:
                    by_name[name].append(item)

        plans: list[DocumentBlockPlan] = []
        routes = [item for item in symbols if item.atomKind == "HTTP_ROUTE"]
        for route in routes:
            request = _first_named(by_name, route.annotations)
            response = _first_named(by_name, [route.responseModel or ""])
            request_conversion_names = (
                {
                    f"{request.qualifiedName}.to_domain",
                    f"{request.qualifiedName}.from_request",
                }
                if request is not None
                else set()
            )
            request_conversions = _matching(
                symbols,
                lambda item, names=request_conversion_names: item.qualifiedName in names,
            )
            request_conversion_ids = {item.candidateId for item in request_conversions}
            nested_conversions = _matching(
                symbols,
                lambda item, candidate_ids=request_conversion_ids: (
                    item.displayName in {"to_domain", "from_blocks"}
                    and item.candidateId not in candidate_ids
                ),
            )
            route_calls = set(route.directCalls)
            business = _matching(
                symbols,
                lambda item, calls=route_calls: (
                    item.displayName in calls
                    and item.displayName not in {"to_domain", "from_domain", "from_blocks"}
                    and not item.schemaModel
                ),
            )
            response_conversions = _matching(
                symbols,
                lambda item: item.displayName in {"from_domain", "from_response"},
            )
            related = [item for item in [request, response] if item is not None]
            related += [*request_conversions, *nested_conversions, *business, *response_conversions]
            # A bare health endpoint has no useful multi-part documentation evidence.
            if not related:
                continue

            route_label = f"{route.routeMethod} {route.routePath}"
            plans.append(
                _plan(
                    route,
                    BlockTargetKind.HTTP_ENDPOINT,
                    f"接口职责：{route_label}",
                    "解释该 FastAPI 路由的入口职责以及由当前代码能够确认的请求到响应衔接。",
                    [route],
                    related,
                    [route, *related],
                    len(plans),
                )
            )
            if request is not None:
                request_candidate_id = request.candidateId
                nested_request = _matching(
                    symbols,
                    lambda item, candidate_id=request_candidate_id: (
                        item.schemaModel
                        and item.displayName.endswith("Request")
                        and item.candidateId != candidate_id
                    ),
                )
                plans.append(
                    _plan(
                        route,
                        BlockTargetKind.SYMBOL,
                        f"请求模型：{request.displayName}",
                        "解释请求 Schema 的字段边界以及它在接口输入阶段承担的职责。",
                        [request],
                        nested_request,
                        [request],
                        len(plans),
                    )
                )
            conversions = [*request_conversions, *nested_conversions]
            if conversions:
                primary = request_conversions[:1] or conversions[:1]
                plans.append(
                    _plan(
                        route,
                        BlockTargetKind.DATA_CONVERSION,
                        "领域转换",
                        "解释请求对象如何被转换成领域对象，只描述真实转换方法。",
                        primary,
                        [item for item in conversions if item not in primary],
                        conversions,
                        len(plans),
                    )
                )
            if business:
                primary = business[:1]
                primary_file_path = primary[0].filePath
                primary_candidate_id = primary[0].candidateId
                helpers = _matching(
                    symbols,
                    lambda item, file_path=primary_file_path, candidate_id=primary_candidate_id: (
                        item.filePath == file_path
                        and item.displayName.startswith("_")
                        and item.candidateId != candidate_id
                    ),
                )
                plans.append(
                    _plan(
                        route,
                        BlockTargetKind.BUSINESS_RULE,
                        f"业务规则：{primary[0].displayName}",
                        "解释规则入口执行的真实校验、分支和结果组织。",
                        primary,
                        helpers,
                        primary,
                        len(plans),
                    )
                )
            if response is not None or response_conversions:
                primary = response_conversions[:1] or ([response] if response else [])
                supporting = [
                    item for item in [response, route] if item is not None and item not in primary
                ]
                plans.append(
                    _plan(
                        route,
                        BlockTargetKind.RESPONSE_CONSTRUCTION,
                        "响应构造",
                        "解释响应对象或响应转换方法如何根据真实结果组装返回数据。",
                        primary,
                        supporting,
                        primary,
                        len(plans),
                    )
                )
        return tuple(plans)


def validate_document_operations(
    plan: AgentPlan, block_plans: tuple[DocumentBlockPlan, ...]
) -> None:
    if not block_plans:
        return
    expected = {item.blockKey: item for item in block_plans}
    content_operations = [
        item
        for item in plan.operations
        if item.operationType in {OperationType.ADD_BLOCK, OperationType.UPDATE_BLOCK}
    ]
    actual = [item.clientOperationId for item in content_operations]
    issues: list[dict[str, str]] = []
    if len(actual) != len(set(actual)) or set(actual) != set(expected):
        issues.append(
            _issue(
                "operations",
                "DOCUMENT_BLOCK_PLAN_MISMATCH",
                "Document operations must match the supplied blockKey set exactly",
            )
        )
    for operation in content_operations:
        block_plan = expected.get(operation.clientOperationId)
        if block_plan is None:
            continue
        if operation.proposedPlainText is None or not operation.proposedPlainText.strip():
            issues.append(
                _issue(
                    f"operations.{operation.clientOperationId}",
                    "DOCUMENT_BLOCK_CONTENT_REQUIRED",
                    "Each planned block requires final Chinese Markdown content",
                )
            )
        elif not operation.proposedPlainText.lstrip().startswith("##"):
            issues.append(
                _issue(
                    f"operations.{operation.clientOperationId}.proposedPlainText",
                    "DOCUMENT_BLOCK_HEADING_REQUIRED",
                    "Each planned block must start with a level-two Markdown heading",
                )
            )
    if issues:
        raise BindingPlanValidationError(issues)


def complete_and_validate_binding_plan(
    binding_plan: BindingPlan,
    candidates: tuple[CodeCandidate, ...],
    block_plans: tuple[DocumentBlockPlan, ...],
) -> BindingPlan:
    code_by_id = {item.candidateId: item for item in candidates}
    plan_by_key = {item.blockKey: item for item in block_plans}
    selections = list(binding_plan.selections)
    selected_blocks = {item.blockKey for item in selections if item.role == BindingRole.PRIMARY}
    for block_plan in block_plans:
        if (
            len(block_plan.primaryCandidateIds) == 1
            and block_plan.primaryCandidateIds[0] in block_plan.requiredCandidateIds
            and block_plan.blockKey not in selected_blocks
        ):
            selections.append(
                BindingSelection(
                    blockKey=block_plan.blockKey,
                    codeCandidateId=block_plan.primaryCandidateIds[0],
                    role=BindingRole.PRIMARY,
                    ordinal=1,
                    reason="程序根据唯一主要候选确定。",
                    confidence=1,
                )
            )
    normalized = BindingPlan(selections=selections)
    issues: list[dict[str, str]] = []
    grouped: dict[str, list[BindingSelection]] = defaultdict(list)
    seen: set[tuple[str, str]] = set()
    for selection in normalized.selections:
        grouped[selection.blockKey].append(selection)
        selected_block_plan = plan_by_key.get(selection.blockKey)
        if selected_block_plan is None or selection.codeCandidateId not in code_by_id:
            issues.append(
                _issue("selections", "UNKNOWN_CANDIDATE_ID", "Unknown blockKey or candidateId")
            )
            continue
        pair = (selection.blockKey, selection.codeCandidateId)
        if pair in seen:
            issues.append(
                _issue(
                    "selections",
                    "DUPLICATE_BINDING",
                    "A block cannot select the same candidate twice",
                )
            )
        seen.add(pair)
        allowed = {
            *selected_block_plan.primaryCandidateIds,
            *selected_block_plan.supportingCandidateIds,
        }
        if selection.codeCandidateId not in allowed:
            issues.append(
                _issue("selections", "UNKNOWN_CANDIDATE_ID", "Candidate is outside the block plan")
            )

    for block_plan in block_plans:
        items = grouped.get(block_plan.blockKey, [])
        if not items and not block_plan.requiredCandidateIds:
            continue
        primary = [item for item in items if item.role == BindingRole.PRIMARY]
        if len(primary) != 1 or (primary and primary[0].ordinal != 1):
            issues.append(
                _issue(
                    block_plan.blockKey,
                    "MISSING_PRIMARY_BINDING",
                    "Each block requires exactly one PRIMARY at ordinal 1",
                )
            )
            continue
        if primary[0].codeCandidateId not in block_plan.primaryCandidateIds:
            issues.append(
                _issue(
                    block_plan.blockKey,
                    "PRIMARY_BINDING_LEVEL_MISMATCH",
                    "PRIMARY does not match the block responsibility",
                )
            )
        supporting = sorted(item.ordinal for item in items if item.role == BindingRole.SUPPORTING)
        if supporting != list(range(2, len(supporting) + 2)):
            issues.append(
                _issue(
                    block_plan.blockKey,
                    "BINDING_COVERAGE_INCOMPLETE",
                    "SUPPORTING ordinals must be continuous from 2",
                )
            )
        covered = {item.codeCandidateId for item in items}
        if not set(block_plan.requiredCandidateIds).issubset(covered):
            issues.append(
                _issue(
                    block_plan.blockKey,
                    "BINDING_COVERAGE_INCOMPLETE",
                    "Required candidates are missing",
                )
            )
    if issues:
        raise BindingPlanValidationError(issues)
    return normalized


def _plan(
    route: CodeCandidate,
    target: BlockTargetKind,
    title: str,
    purpose: str,
    primary: list[CodeCandidate],
    supporting: list[CodeCandidate],
    required: list[CodeCandidate],
    sort_order: int,
) -> DocumentBlockPlan:
    supporting = _unique(supporting)[: max(0, 16 - len(primary))]
    all_ids = {item.candidateId for item in [*primary, *supporting]}
    required_ids = [item.candidateId for item in _unique(required) if item.candidateId in all_ids]
    raw_key = f"{route.candidateId}:{target.value}"
    return DocumentBlockPlan(
        blockKey="block_" + hashlib.sha256(raw_key.encode()).hexdigest()[:20],
        title=title,
        purpose=purpose,
        targetKind=target,
        primaryCandidateIds=[item.candidateId for item in primary],
        supportingCandidateIds=[item.candidateId for item in supporting],
        requiredCandidateIds=required_ids,
        allowedClaims=[purpose],
        forbiddenClaims=[
            "认证授权",
            "API网关",
            "Cookie",
            "数据库事务",
            "部署行为",
            "性能结论",
            "未显式声明的状态码",
        ],
        sortOrder=sort_order,
    )


def _first_named(by_name: dict[str, list[CodeCandidate]], names: list[str]) -> CodeCandidate | None:
    for name in names:
        simple = name.removeprefix("typing.").split("[")[0]
        matches = by_name.get(simple, [])
        if matches:
            return sorted(matches, key=lambda item: (item.filePath, item.startLine or 0))[0]
    return None


def _matching(candidates: list[CodeCandidate], predicate: Any) -> list[CodeCandidate]:
    return sorted(
        (item for item in candidates if predicate(item)),
        key=lambda item: (item.filePath, item.startLine or 0, item.candidateId),
    )


def _unique(items: list[CodeCandidate]) -> list[CodeCandidate]:
    seen: set[str] = set()
    result: list[CodeCandidate] = []
    for item in items:
        if item.candidateId not in seen:
            seen.add(item.candidateId)
            result.append(item)
    return result


def _issue(path: str, code: str, message: str) -> dict[str, str]:
    return {"path": path, "code": code, "message": message}
