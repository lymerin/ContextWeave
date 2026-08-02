from copy import deepcopy
from pathlib import Path
from typing import Any

import pytest
from conftest import FakeMcpClient, FakeModelProvider

from app.config import Settings
from app.graph.document_sync_workflow import DocumentSyncWorkflow
from app.planning.validator import AgentPlanValidator, PlanValidationError
from app.schemas.plans import AgentPlan

WORKSPACE = "11111111-1111-1111-1111-111111111111"
REPOSITORY = "22222222-2222-2222-2222-222222222222"
DOCUMENT = "33333333-3333-3333-3333-333333333333"
BLOCK = "44444444-4444-4444-4444-444444444444"
BINDING = "55555555-5555-5555-5555-555555555555"
AUTH_TS_SOURCE = Path("../web/src/api/auth.ts").read_text(encoding="utf-8")
AUTH_CONTROLLER_SOURCE = Path(
    "../knowledge-core/src/main/java/"
    "com/devcollab/knowledgecore/auth/api/AuthController.java"
).read_text(encoding="utf-8")


def code_context(
    *,
    paths: list[str] | None = None,
    contents: list[str] | None = None,
    bound_paths: list[str] | None = None,
    title: str = "应用模块说明",
) -> dict[str, Any]:
    selected_paths = paths or ["src/App.java"]
    code_contents = contents or ["class App { void run() {} }"]
    bindings = [
        {
            "bindingId": BINDING,
            "filePath": path,
            "documentId": DOCUMENT,
            "blockId": BLOCK,
        }
        for path in (bound_paths if bound_paths is not None else selected_paths)
    ]
    return {
        "workspace": {
            "workspaceId": WORKSPACE,
            "repositoryId": REPOSITORY,
            "repositoryName": "devcollab",
            "defaultBranch": "main",
        },
        "task": {"selectedPaths": selected_paths, "userInstruction": "同步正式工程文档"},
        "codeFiles": [
            {
                "filePath": path,
                "language": "TypeScript" if path.endswith(".ts") else "Java",
                "content": content,
                "truncated": False,
            }
            for path, content in zip(selected_paths, code_contents, strict=True)
        ],
        "existingBindings": bindings,
        "documents": [
            {
                "source": "BOUND",
                "documentId": DOCUMENT,
                "title": title,
                "documentType": "BACKEND",
                "reviewStatus": "DRAFT",
                "version": 2,
                "blocks": [
                    {
                        "blockId": BLOCK,
                        "type": "PARAGRAPH",
                        "sortOrder": 0,
                        "version": 3,
                        "plainText": "现有模块行为说明。",
                    }
                ],
            }
        ],
    }


def evidence(operation_id: str, file_path: str = "src/App.java") -> dict[str, Any]:
    return {
        "clientOperationId": operation_id,
        "repositoryId": REPOSITORY,
        "filePath": file_path,
        "startLine": 1,
        "endLine": 1,
        "description": "选中代码提供直接实现依据。",
    }


def create_plan(
    *,
    title: str = "应用模块说明",
    bodies: list[str] | None = None,
    path: str = "src/App.java",
) -> dict[str, Any]:
    final_bodies = bodies or [
        "模块职责\n\n应用模块负责执行 `run()` 入口并组织核心业务流程。",
        "维护要求\n\n修改 `App` 的公开行为时，需要同步更新本文档及正式 Binding。",
    ]
    operations: list[dict[str, Any]] = [
        {
            "clientOperationId": "create-1",
            "sequenceNumber": 1,
            "operationType": "CREATE_DOCUMENT",
            "proposedDocumentTitle": title,
            "proposedDocumentType": "BACKEND",
        }
    ]
    plan_evidence = [evidence("create-1", path)]
    for index, body in enumerate(final_bodies, start=1):
        operation_id = f"block-{index}"
        operations.append(
            {
                "clientOperationId": operation_id,
                "sequenceNumber": index + 1,
                "operationType": "ADD_BLOCK",
                "createdDocumentClientOperationId": "create-1",
                "proposedBlockType": "PARAGRAPH",
                "proposedPlainText": body,
                "proposedContentFormat": "MARKDOWN",
            }
        )
        plan_evidence.append(evidence(operation_id, path))
    operations_count = len(operations)
    return {
        "decision": "SUBMIT_REVIEW",
        "summary": "创建与实现职责相符的正式文档",
        "rationale": "选中代码尚无职责相符的工程文档。",
        "operations": operations,
        "bindingProposals": [
            {
                "clientBindingProposalId": "binding-1",
                "sequenceNumber": operations_count + 1,
                "action": "UPSERT_BINDING",
                "repositoryId": REPOSITORY,
                "filePath": path,
                "createdDocumentClientOperationId": "create-1",
                "reason": "建立代码与正式文档的长期关联。",
            }
        ],
        "evidence": plan_evidence,
    }


def update_plan(body: str = "应用模块通过 `run()` 执行经过验证的核心业务流程。") -> dict[str, Any]:
    return {
        "decision": "SUBMIT_REVIEW",
        "summary": "同步已变更的实现行为",
        "rationale": "现有 Block 未描述代码中的当前行为。",
        "operations": [
            {
                "clientOperationId": "update-1",
                "sequenceNumber": 1,
                "operationType": "UPDATE_BLOCK",
                "documentId": DOCUMENT,
                "blockId": BLOCK,
                "baseBlockVersion": 3,
                "proposedPlainText": body,
                "proposedContentFormat": "MARKDOWN",
            }
        ],
        "bindingProposals": [],
        "evidence": [evidence("update-1")],
    }


def validate(raw: dict[str, Any], context: dict[str, Any] | None = None) -> AgentPlan:
    return AgentPlanValidator().validate(
        AgentPlan.model_validate(raw),
        context or code_context(),
    )


def issue_codes(raw: dict[str, Any], context: dict[str, Any] | None = None) -> set[str]:
    with pytest.raises(PlanValidationError) as caught:
        validate(raw, context)
    return {issue.code for issue in caught.value.issues}


def test_create_document_contains_publishable_chinese_content_and_keeps_identifiers() -> None:
    plan = validate(create_plan())
    body = "\n".join(item.proposedPlainText or "" for item in plan.operations)
    assert plan.operations[0].proposedDocumentTitle == "应用模块说明"
    assert "应用模块负责" in body
    assert "`run()`" in body


def test_update_block_is_complete_final_chinese_content() -> None:
    plan = validate(update_plan())
    assert plan.operations[0].proposedPlainText == (
        "应用模块通过 `run()` 执行经过验证的核心业务流程。"
    )


def test_review_explanation_is_not_mapped_into_document_body() -> None:
    plan = validate(create_plan())
    payload = plan.mcp_payload(WORKSPACE, "request-1")
    serialized_operations = str(payload["operations"])
    assert plan.summary not in serialized_operations
    assert plan.rationale not in serialized_operations
    assert payload["summary"] == plan.summary
    assert payload["rationale"] == plan.rationale
    assert payload["evidence"] != payload["operations"]


@pytest.mark.parametrize(
    "body",
    [
        "建议新增一个接口章节，说明登录和注册能力。",
        "应补充认证模块的主要职责。",
        "可以描述登录、注册和退出登录。",
        "Add a section describing login.",
        "Consider documenting the authentication endpoints.",
        "This section should explain how login works.",
    ],
)
def test_instructional_document_body_is_rejected(body: str) -> None:
    assert "INSTRUCTIONAL_DOCUMENT_CONTENT" in issue_codes(update_plan(body))


def test_formal_engineering_recommendation_is_not_false_positive() -> None:
    plan = validate(update_plan("生产环境建议通过环境变量配置密钥，避免密钥进入源码。"))
    assert plan.operations[0].proposedPlainText


def test_create_document_with_title_only_is_rejected() -> None:
    raw = create_plan()
    raw["operations"] = raw["operations"][:1]
    raw["bindingProposals"][0]["sequenceNumber"] = 2
    raw["evidence"] = raw["evidence"][:1]
    assert "CREATE_DOCUMENT_BODY_REQUIRED" in issue_codes(raw)


def test_multiple_indexed_symbols_cannot_collapse_into_one_giant_block() -> None:
    context = code_context()
    context["codeFiles"][0]["symbols"] = [
        {
            "symbolKey": "src/App.java#run()",
            "qualifiedName": "App#run",
            "startLine": 1,
            "endLine": 1,
        },
        {
            "symbolKey": "src/App.java#stop()",
            "qualifiedName": "App#stop",
            "startLine": 2,
            "endLine": 2,
        },
    ]
    assert "GIANT_DOCUMENT_BLOCK" in issue_codes(
        create_plan(bodies=["## 应用模块\n\n这个 Block 把两个主要符号混在一起说明。"]),
        context,
    )


def test_multi_symbol_beginner_document_rejects_shallow_field_lists() -> None:
    context = code_context()
    context["codeFiles"][0]["symbols"] = [
        {
            "symbolKey": "src/App.java#Mode",
            "qualifiedName": "Mode",
            "startLine": 1,
            "endLine": 4,
        },
        {
            "symbolKey": "src/App.java#Context",
            "qualifiedName": "Context",
            "startLine": 6,
            "endLine": 12,
        },
    ]
    shallow = create_plan(
        bodies=[
            "## 模块概览\n\n本模块定义两个类型，用于统一保存数据。",
            (
                "## Mode\n\nMode 是一个枚举。它包含 A 和 B 两个值，"
                "字段用于表示当前模式。"
            ),
        ]
    )

    codes = issue_codes(shallow, context)

    assert "BEGINNER_OVERVIEW_TOO_SHALLOW" in codes
    assert "BEGINNER_SYMBOL_BLOCK_TOO_SHALLOW" in codes


def test_document_prompt_requires_beginner_oriented_symbol_explanations() -> None:
    prompt = Path("app/prompts/document_sync_v1.md").read_text(encoding="utf-8")
    assert "代码初学者讲解要求" in prompt
    assert "`@dataclass(frozen=True)`" in prompt
    assert "`tuple[T, ...]`" in prompt
    assert "不得补全想象中的 Controller" in prompt
    assert "不得只生成一个正文 Block" in prompt
    assert "字段叫 `id` 不等于代码已经保证唯一" in prompt
    assert "数据库、前端、规则引擎、报告生成器" in prompt


def test_document_rejects_external_relations_absent_from_selected_code() -> None:
    raw = create_plan(
        bodies=[
            (
                "## 模块概览\n\n"
                + "为了避免数据概念散落，本模块统一表达输入、输出和数据流。"
                + "调用方传入对象，流程随后输出结果。"
                + "这里解释真实职责、语法、协作关系和维护边界。" * 12
            ),
            (
                "## Mode\n\n"
                + "这个枚举解决任意字符串容易拼错的问题，并解释枚举语法。"
                + "它参与输入和输出的数据流。修改时需要检查真实引用。"
                + "数据库和前端都必须同步更新。" * 8
            ),
        ]
    )

    assert "UNSUPPORTED_EXTERNAL_RELATION" in issue_codes(raw)


def test_document_rejects_constraints_inferred_only_from_names() -> None:
    raw = create_plan(
        bodies=[
            (
                "## 模块概览\n\n"
                + "为了避免数据概念散落，本模块统一表达输入、输出和数据流。"
                + "调用方传入对象，流程随后输出结果。"
                + "这里解释真实职责、语法、协作关系和维护边界。" * 12
            ),
            (
                "## Mode\n\n"
                + "这个枚举解决任意字符串容易拼错的问题，并解释枚举语法。"
                + "它参与输入和输出的数据流。修改时需要检查真实引用。"
                + "BLOCKER 表示必须修复才能通过。" * 8
            ),
        ]
    )

    assert "UNSUPPORTED_INFERRED_SEMANTICS" in issue_codes(raw)


def test_empty_block_is_rejected() -> None:
    raw = create_plan(bodies=["   "])
    assert "BLOCK_CONTENT_REQUIRED" in issue_codes(raw)


def test_duplicate_block_content_is_rejected() -> None:
    repeated = "模块职责\n\n应用模块负责执行经过代码验证的核心业务流程。"
    assert "DUPLICATE_BLOCK_CONTENT" in issue_codes(
        create_plan(bodies=[repeated, repeated])
    )


def test_same_block_cannot_be_updated_twice() -> None:
    raw = update_plan()
    duplicate = deepcopy(raw["operations"][0])
    duplicate["clientOperationId"] = "update-2"
    duplicate["sequenceNumber"] = 2
    raw["operations"].append(duplicate)
    raw["evidence"].append(evidence("update-2"))
    assert "DUPLICATE_BLOCK_UPDATE" in issue_codes(raw)


def test_new_english_title_is_rejected() -> None:
    assert "CHINESE_TITLE_REQUIRED" in issue_codes(
        create_plan(title="Authentication API Client")
    )


def test_new_english_body_is_rejected() -> None:
    assert "CHINESE_CONTENT_REQUIRED" in issue_codes(
        create_plan(bodies=["The client calls the login endpoint and returns a user token."])
    )


def test_obviously_mixed_new_document_is_rejected() -> None:
    body = (
        "模块职责用于说明认证调用。 This client provides authentication request handling "
        "and returns normalized user responses for every supported endpoint in the application."
    )
    assert "MIXED_DOCUMENT_LANGUAGE" in issue_codes(create_plan(bodies=[body]))


def test_no_change_is_valid_when_document_and_binding_are_complete() -> None:
    raw = {
        "decision": "NO_CHANGE",
        "summary": "文档和关联均已同步",
        "rationale": "现有内容与实现一致，且选中文件已有正式 Binding。",
        "operations": [],
        "bindingProposals": [],
        "evidence": [],
    }
    assert validate(raw).decision == "NO_CHANGE"


def test_document_plan_allows_binding_only_decision_in_independent_pass() -> None:
    raw = {
        "decision": "NO_CHANGE",
        "summary": "无需修改",
        "rationale": "文档内容与实现一致。",
        "operations": [],
        "bindingProposals": [],
        "evidence": [],
    }
    context = code_context(bound_paths=[])
    assert validate(raw, context).decision == "NO_CHANGE"


def test_document_plan_leaves_partial_binding_gap_to_independent_pass() -> None:
    raw = {
        "decision": "NO_CHANGE",
        "summary": "无需修改",
        "rationale": "文档内容与实现一致。",
        "operations": [],
        "bindingProposals": [],
        "evidence": [],
    }
    context = code_context(
        paths=["src/App.java", "src/Helper.java"],
        contents=["class App {}", "class Helper {}"],
        bound_paths=["src/App.java"],
    )
    assert validate(raw, context).decision == "NO_CHANGE"


def test_binding_only_review_is_valid() -> None:
    raw = {
        "decision": "SUBMIT_REVIEW",
        "summary": "补充缺失的正式关联",
        "rationale": "文档内容正确，但选中文件尚未建立 Binding。",
        "operations": [],
        "bindingProposals": [
            {
                "clientBindingProposalId": "binding-1",
                "sequenceNumber": 1,
                "action": "UPSERT_BINDING",
                "repositoryId": REPOSITORY,
                "filePath": "src/App.java",
                "documentId": DOCUMENT,
                "reason": "将实现文件关联到职责匹配的现有文档。",
            }
        ],
        "evidence": [
            {
                "repositoryId": REPOSITORY,
                "filePath": "src/App.java",
                "description": "文件实现与现有文档职责一致。",
            }
        ],
    }
    plan = validate(raw, code_context(bound_paths=[]))
    assert not plan.operations
    assert len(plan.bindingProposals) == 1


def test_outdated_document_and_missing_binding_submit_both_changes() -> None:
    raw = update_plan()
    raw["bindingProposals"] = [
        {
            "clientBindingProposalId": "binding-1",
            "sequenceNumber": 2,
            "action": "UPSERT_BINDING",
            "repositoryId": REPOSITORY,
            "filePath": "src/App.java",
            "documentId": DOCUMENT,
            "reason": "更新正文的同时补充正式关联。",
        }
    ]
    plan = validate(raw, code_context(bound_paths=[]))
    assert plan.operations and plan.bindingProposals


def test_incorrect_binding_can_be_removed_for_review() -> None:
    raw = {
        "decision": "SUBMIT_REVIEW",
        "summary": "移除职责不匹配的关联",
        "rationale": "现有 Binding 将代码关联到了错误文档。",
        "operations": [],
        "bindingProposals": [
            {
                "clientBindingProposalId": "remove-1",
                "sequenceNumber": 1,
                "action": "REMOVE_BINDING",
                "repositoryId": REPOSITORY,
                "filePath": "src/App.java",
                "documentId": DOCUMENT,
                "bindingId": BINDING,
                "reason": "代码与目标文档职责不一致。",
            }
        ],
        "evidence": [
            {
                "repositoryId": REPOSITORY,
                "filePath": "src/App.java",
                "description": "选中代码显示当前 Binding 职责不匹配。",
            }
        ],
    }
    assert validate(raw).bindingProposals[0].action == "REMOVE_BINDING"


def frontend_auth_context() -> dict[str, Any]:
    return code_context(
        paths=["web/src/api/auth.ts"],
        contents=[AUTH_TS_SOURCE],
        bound_paths=[],
        title="前端认证 API 客户端",
    )


def frontend_auth_plan() -> dict[str, Any]:
    return create_plan(
        title="前端认证 API 客户端",
        path="web/src/api/auth.ts",
        bodies=[
            (
                "模块职责\n\n该客户端基于共享 `http` 实例封装认证请求，并导出 "
                "`AuthUser`、`AuthResponse`、`LoginPayload`、`RegisterPayload` "
                "和 `ApiErrorResponse` 类型。"
            ),
            (
                "请求方法\n\n`login()` 调用 `POST /auth/login`，`register()` 调用 "
                "`POST /auth/register`，`getCurrentUser()` 调用 `GET /auth/me`，"
                "`logout()` 调用 `POST /auth/logout`。各方法向调用方返回类型化结果。"
            ),
            (
                "请求约束\n\n所有请求复用共享 HTTP Client；`logout()` 通过 "
                "`csrfHeader()` 携带项目现有的 CSRF Header。"
            ),
        ],
    )


def test_auth_ts_generates_scoped_chinese_frontend_client_document() -> None:
    plan = validate(frontend_auth_plan(), frontend_auth_context())
    text = "\n".join(item.proposedPlainText or "" for item in plan.operations)
    assert plan.operations[0].proposedDocumentTitle == "前端认证 API 客户端"
    for required in (
        "login()",
        "register()",
        "getCurrentUser()",
        "logout()",
        "HTTP Client",
        "CSRF Header",
    ):
        assert required in text
    assert "/refresh" not in text
    assert "AuthController" not in text


@pytest.mark.parametrize(
    "pollution",
    [
        "前端调用 `/refresh`，由服务端创建 Refresh Token Cookie。",
        "请求由 `AuthController` 转发给 `AuthService` 完成认证。",
        "安全链通过 `SecurityFilterChain` 和 `JwtAuthenticationFilter` 校验令牌。",
        "注册成功固定返回 HTTP 201，退出登录固定返回 HTTP 204。",
    ],
)
def test_auth_ts_rejects_backend_only_details(pollution: str) -> None:
    raw = frontend_auth_plan()
    raw["operations"][1]["proposedPlainText"] = pollution
    codes = issue_codes(raw, frontend_auth_context())
    assert {
        "FRONTEND_BACKEND_SCOPE_POLLUTION",
        "UNSUPPORTED_FRONTEND_ENDPOINT",
    } & codes


def backend_auth_context(title: str = "后端认证 REST API") -> dict[str, Any]:
    return code_context(
        paths=[
            "knowledge-core/src/main/java/"
            "com/devcollab/knowledgecore/auth/api/AuthController.java"
        ],
        contents=[AUTH_CONTROLLER_SOURCE],
        bound_paths=[],
        title=title,
    )


def test_auth_controller_generates_chinese_rest_api_document() -> None:
    path = (
        "knowledge-core/src/main/java/"
        "com/devcollab/knowledgecore/auth/api/AuthController.java"
    )
    raw = create_plan(
        title="后端认证 REST API",
        path=path,
        bodies=[
            (
                "模块职责\n\n`AuthController` 暴露注册、登录、刷新、退出和当前用户查询接口，"
                "并将认证业务委托给 `AuthenticationApplicationService`。"
            ),
            (
                "接口说明\n\n接口包括 `POST /register`、`POST /login`、"
                "`POST /refresh`、`POST /logout` 和 `GET /me`。"
            ),
        ],
    )
    plan = validate(raw, backend_auth_context())
    assert plan.operations[0].proposedDocumentTitle == "后端认证 REST API"


def test_auth_controller_cannot_update_frontend_client_document() -> None:
    raw = update_plan("后端控制器负责接收认证请求并返回经过验证的响应。")
    context = backend_auth_context(title="前端认证 API 客户端")
    assert "DOCUMENT_RESPONSIBILITY_MISMATCH" in issue_codes(raw, context)


def test_many_tiny_blocks_are_rejected_as_fragmented_document() -> None:
    bodies = [f"职责{i}：说明行为。" for i in range(12)]
    assert "FRAGMENTED_DOCUMENT" in issue_codes(create_plan(bodies=bodies))


def test_repair_prompt_requires_final_chinese_content() -> None:
    prompt = Path("app/providers/deepseek.py").read_text(encoding="utf-8")
    assert "直接返回修正后的完整 AgentPlan JSON" in prompt
    assert "简体中文最终内容" in prompt
    assert "不要输出建议" in prompt


async def no_status(status: str, node: str, updates: dict[str, Any]) -> None:
    return None


def workflow_state(path: str) -> dict[str, Any]:
    return {
        "run_id": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
        "workspace_id": WORKSPACE,
        "repository_id": REPOSITORY,
        "selected_paths": [path],
        "user_instruction": "生成职责匹配的简体中文正式工程文档",
        "authorization": "Bearer transient",
        "tool_call_count": 0,
        "code_chars_used": 0,
        "trace_events": [],
        "errors": [],
    }


@pytest.mark.asyncio
async def test_auth_ts_uses_program_owned_file_plan_instead_of_legacy_model_operations(
    settings: Settings,
) -> None:
    path = "web/src/api/auth.ts"
    mcp = FakeMcpClient(bound=False, code_content=AUTH_TS_SOURCE)
    provider = FakeModelProvider([AgentPlan.model_validate(frontend_auth_plan())])
    full_context_settings = settings.model_copy(update={"agent_max_code_chars": 20_000})

    result = await DocumentSyncWorkflow(
        mcp, provider, full_context_settings, no_status
    ).graph.ainvoke(workflow_state(path))

    assert result["decision"] == "SUBMIT_REVIEW"
    assert len(mcp.submissions) == 1
    submitted = mcp.submissions[0][0]
    assert [item.operationType.value for item in submitted.operations] == ["ADD_BLOCK"]
    assert str(submitted.operations[0].documentId) == (
        "55555555-5555-5555-5555-555555555555"
    )
    assert submitted.bindingProposals[0].filePath == path
    assert provider.calls == []
    assert len(provider.block_content_calls) == 1


@pytest.mark.asyncio
async def test_java_controller_uses_program_owned_file_plan_instead_of_legacy_plan(
    settings: Settings,
) -> None:
    path = (
        "knowledge-core/src/main/java/"
        "com/devcollab/knowledgecore/auth/api/AuthController.java"
    )
    plan = create_plan(
        title="后端认证 REST API",
        path=path,
        bodies=[
            (
                "模块职责\n\n`AuthController` 接收认证 HTTP 请求，并将业务处理委托给 "
                "`AuthenticationApplicationService`。"
            ),
            (
                "接口说明\n\n接口包括 `POST /register`、`POST /login`、"
                "`POST /refresh`、`POST /logout` 和 `GET /me`。"
            ),
        ],
    )
    mcp = FakeMcpClient(bound=False, code_content=AUTH_CONTROLLER_SOURCE)
    provider = FakeModelProvider([AgentPlan.model_validate(plan)])
    full_context_settings = settings.model_copy(update={"agent_max_code_chars": 20_000})

    result = await DocumentSyncWorkflow(
        mcp, provider, full_context_settings, no_status
    ).graph.ainvoke(workflow_state(path))

    assert result["decision"] == "SUBMIT_REVIEW"
    assert len(mcp.submissions) == 1
    submitted = mcp.submissions[0][0]
    assert all(
        item.operationType.value == "ADD_BLOCK"
        for item in submitted.operations
    )
    assert {
        str(item.documentId) for item in submitted.operations
    } == {"55555555-5555-5555-5555-555555555555"}
    assert {item.filePath for item in submitted.bindingProposals} == {path}
    assert provider.calls == []
    assert len(provider.block_content_calls) == 1
