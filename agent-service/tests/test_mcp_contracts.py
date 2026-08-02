"""MCP 契约测试——校验 agent 构造的 payload 符合共享 JSON Schema。

共享 schema 是单一权威源（contracts/mcp/）。任何一侧改动契约，
此处测试立即失败，杜绝运行期才暴露的契约漂移。
"""

import sys

sys.path.insert(0, "agent-service")

from app.contracts.validator import (
    validate_payload,
    validate_response,
)

WS = "11111111-1111-1111-1111-111111111111"
REPO = "22222222-2222-2222-2222-222222222222"
DOC = "33333333-3333-3333-3333-333333333333"


def _assert_valid(tool: str, payload: dict) -> None:
    errs = validate_payload(tool, payload)
    assert not errs, f"{tool} payload 不符合契约: {errs}"


# ── 各工具合法 payload（与 reader/writer 实际构造一致）─────────────────


def test_workspace_get_context_payload() -> None:
    _assert_valid("devcollab.workspace.get_context", {"workspaceId": WS})


def test_code_read_payload() -> None:
    _assert_valid(
        "devcollab.code.read",
        {"workspaceId": WS, "repositoryId": REPO, "path": "app/schemas.py"},
    )


def test_binding_list_payload() -> None:
    _assert_valid(
        "devcollab.binding.list",
        {"workspaceId": WS, "repositoryId": REPO, "filePath": "app/schemas.py"},
    )


def test_binding_list_batch_payload() -> None:
    _assert_valid(
        "devcollab.binding.list_batch",
        {
            "workspaceId": WS,
            "repositoryId": REPO,
            "filePaths": ["app/schemas.py", "app/domain.py"],
        },
    )


def test_document_get_structure_payload() -> None:
    _assert_valid(
        "devcollab.document.get_structure",
        {
            "workspaceId": WS,
            "documentId": DOC,
            "includeBlockContent": True,
        },
    )


def test_document_find_candidates_payload() -> None:
    _assert_valid(
        "devcollab.document.find_candidates",
        {
            "workspaceId": WS,
            "repositoryId": REPO,
            "filePath": "app/schemas.py",
            "limit": 5,
        },
    )


def test_repository_list_files_payload() -> None:
    _assert_valid(
        "devcollab.repository.list_files",
        {"workspaceId": WS, "repositoryId": REPO, "recursive": True},
    )


def test_submit_document_change_payload() -> None:
    # 模拟 plan_writer 构造的完整 payload
    _assert_valid(
        "devcollab.review.submit_document_change",
        {
            "workspaceId": WS,
            "clientRequestId": "agent-abc",
            "summary": "审阅请求模型组",
            "rationale": "基于语义分析",
            "operations": [
                {
                    "clientOperationId": "create_doc_abc",
                    "sequenceNumber": 1,
                    "operationType": "CREATE_DOCUMENT",
                    "proposedDocumentTitle": "审阅请求模型组",
                    "proposedPlainText": "审阅请求模型组",
                },
                {
                    "clientOperationId": "add_block_0",
                    "sequenceNumber": 2,
                    "operationType": "ADD_BLOCK",
                    "createdDocumentClientOperationId": "create_doc_abc",
                    "proposedBlockType": "PARAGRAPH",
                    "proposedPlainText": "内容",
                    "proposedContentFormat": "MARKDOWN",
                },
            ],
            "bindingProposals": [
                {
                    "clientBindingProposalId": "binding-1-abc",
                    "sequenceNumber": 3,
                    "action": "UPSERT_BINDING",
                    "repositoryId": REPO,
                    "revision": "rev-1",
                    "filePath": "app/schemas.py",
                    "anchorKind": "SYMBOL",
                    "symbolKey": "PYTHON:app/schemas.py:A:CLASS",
                    "startLine": 1,
                    "endLine": 10,
                    "bindingRole": "PRIMARY",
                    "bindingOrdinal": 1,
                    "reason": "语义分析绑定",
                    "confidence": 1.0,
                    "createdDocumentClientOperationId": "create_doc_abc",
                }
            ],
            "evidence": [
                {
                    "clientOperationId": "create_doc_abc",
                    "repositoryId": REPO,
                    "filePath": "app/schemas.py",
                    "startLine": 1,
                    "endLine": 10,
                    "description": "语义分析证据",
                }
            ],
        },
    )


# ── 关键非法场景（曾导致运行期失败的契约漂移）────────────────────


def test_submit_rejects_extra_sourceType() -> None:
    # 之前 plan_writer 顶层带 sourceType 导致 mcp 拒绝
    errs = validate_payload(
        "devcollab.review.submit_document_change",
        {
            "workspaceId": WS,
            "clientRequestId": "a",
            "summary": "s",
            "rationale": "r",
            "sourceType": "MCP",
            "operations": [],
        },
    )
    assert errs, "应拒绝 sourceType 额外字段"
    assert any("sourceType" in e for e in errs)


def test_submit_rejects_top_level_repositoryId() -> None:
    # 之前 plan_writer 顶层带 repositoryId 导致 mcp 拒绝
    errs = validate_payload(
        "devcollab.review.submit_document_change",
        {
            "workspaceId": WS,
            "clientRequestId": "a",
            "summary": "s",
            "rationale": "r",
            "repositoryId": REPO,
            "operations": [],
        },
    )
    assert errs
    assert any("repositoryId" in e for e in errs)


def test_binding_missing_required_rejected() -> None:
    errs = validate_payload(
        "devcollab.binding.list",
        {"workspaceId": WS},
    )
    assert errs


def test_document_structure_output_matches() -> None:
    # 校验 mcp 返回的 get_structure 结构符合契约（含 blockType/content）
    errs = validate_response(
        "devcollab.document.get_structure",
        {
            "documentId": DOC,
            "workspaceId": WS,
            "title": "文档",
            "documentType": "REQUIREMENT",
            "reviewStatus": "DRAFT",
            "updatedAt": "2026-08-03T00:00:00Z",
            "blocks": [
                {
                    "blockId": DOC,
                    "blockType": "PARAGRAPH",
                    "sortOrder": 0,
                    "version": 1,
                    "plainText": "内容",
                    "content": '{"type":"doc","content":[]}',
                }
            ],
            "truncated": False,
            "omittedBlockCount": 0,
            "omittedCharacterCount": 0,
        },
    )
    assert not errs, f"get_structure 输出不符合契约: {errs}"


if __name__ == "__main__":
    import inspect

    fns = [
        obj for _, obj in sorted(globals().items())
        if callable(obj) and obj.__module__ == __name__ and obj.__name__.startswith("test_")
    ]
    for fn in fns:
        fn()
        print(f"PASS: {fn.__name__}")
    print(f"ALL {len(fns)} TESTS PASSED")
