"""PlanWriter 绑定提案截断测试——平台 max-bindings=50 约束。

回归：模块总览 batch 的 SUPPORTING 绑定超过 50 条时，
MCP 服务端会拒绝整条请求（McpClientError: bindingProposals must not
exceed 50 entries）。提交前必须按优先级截断而非让请求失败。
"""

import logging

from app.platform_mcp.plan_writer import _PLATFORM_MAX_BINDINGS, _truncate_proposals


def _proposal(seq: int, *, role: str = "SUPPORTING", created: bool = False,
              remove: bool = False) -> dict:
    p = {
        "clientBindingProposalId": f"binding-{seq}-abc",
        "sequenceNumber": seq,
        "action": "REMOVE_BINDING" if remove else "UPSERT_BINDING",
        "repositoryId": "22222222-2222-2222-2222-222222222222",
        "filePath": f"src/main/java/com/example/X{seq}.java",
        "startLine": seq,
        "endLine": seq + 5,
        "bindingRole": role,
    }
    if created:
        p["createdBlockClientOperationId"] = f"add_block_{seq}"
    return p


def test_under_limit_unchanged() -> None:
    proposals = [_proposal(i) for i in range(1, _PLATFORM_MAX_BINDINGS + 1)]
    assert _truncate_proposals(proposals) == proposals


def test_truncates_to_platform_max() -> None:
    proposals = [_proposal(i) for i in range(1, _PLATFORM_MAX_BINDINGS + 20)]
    kept = _truncate_proposals(proposals)
    assert len(kept) == _PLATFORM_MAX_BINDINGS


def test_primary_anchors_survive_truncation() -> None:
    # 60 条：1 个 PRIMARY 锚点 + 59 个 SUPPORTING（后 10 个先排入）
    proposals = [_proposal(1, role="PRIMARY", created=True)]
    proposals += [_proposal(i, created=True) for i in range(2, 61)]
    kept = _truncate_proposals(proposals)
    assert len(kept) == _PLATFORM_MAX_BINDINGS
    # PRIMARY 锚点必须保留
    assert any(p["bindingRole"] == "PRIMARY" for p in kept)
    # 截断后顺序稳定：sequenceNumber 严格递增
    seqs = [p["sequenceNumber"] for p in kept]
    assert seqs == sorted(seqs)
    assert max(seqs) < 61


def test_remove_binding_dropped_last() -> None:
    # 60 条：50 个 SUPPORTING + 10 个 REMOVE_BINDING → 清理类最后被裁
    proposals = [_proposal(i, created=True) for i in range(1, 51)]
    proposals += [_proposal(100 + i, remove=True) for i in range(10)]
    kept = _truncate_proposals(proposals)
    assert len(kept) == _PLATFORM_MAX_BINDINGS
    assert all(p["action"] != "REMOVE_BINDING" for p in kept)


def test_truncation_logs_warning(caplog) -> None:
    proposals = [_proposal(i) for i in range(1, _PLATFORM_MAX_BINDINGS + 10)]
    with caplog.at_level(logging.WARNING):
        _truncate_proposals(proposals)
    assert any(
        "binding proposals truncated" in r.message for r in caplog.records
    )
