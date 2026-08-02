"""语义结果容错测试——验证模型输出格式变化能被吸收，不因格式失败。

三层容错：
  1. schema 去 strict/extra_forbid（extra=ignore）
  2. _normalize_payload 做 camelCase→snake_case 归一化
  3. 校验器宽容（只查存在性）
"""

import sys

sys.path.insert(0, "agent-service")

from app.providers.deepseek import _normalize_payload
from app.schemas.semantic.analysis_result import (
    SemanticAnalysisResult,
)


def test_normalize_camel_case_keys() -> None:
    raw = {
        "overall_responsibility": "职责",
        "execution_flow": [
            {
                "stepOrder": 0,           # camelCase
                "atomId": "sym_a",
                "description": "步骤",
                "evidenceRefs": [{"atomId": "sym_b", "relationId": "r1"}],
            }
        ],
        "semantic_groups": [
            {
                "group_id": "g1",
                "order": 1,
                "title": "模型",
                "primaryAtomIds": ["sym_a"],   # camelCase
                "informedByAtomIds": ["sym_a", "sym_b"],
            }
        ],
        "unresolvedFindings": ["发现1"],       # camelCase
    }
    norm = _normalize_payload(raw)
    assert "execution_flow" in norm
    assert "step_order" in norm["execution_flow"][0]
    assert "atom_id" in norm["execution_flow"][0]
    assert "evidence_refs" in norm["execution_flow"][0]
    assert "primary_atom_ids" in norm["semantic_groups"][0]
    assert "unresolved_findings" in norm


def test_model_validate_absorbs_camel_case_and_extra() -> None:
    payload = {
        "analysis_id": "a1",
        "context_id": "ctx-1",
        "revision": "rev-1",
        "snapshot_hash": "hash",
        "overall_responsibility": "职责",
        "execution_flow": [
            {
                "stepOrder": 0,
                "atomId": "sym_a",
                "description": "步骤",
                "extraUnexpectedField": "忽略我",   # 多余字段
                "evidenceRefs": [{"atomId": "sym_b", "sourceChunkId": "c1"}],
            }
        ],
        "semantic_groups": [
            {
                "groupId": "g1",        # camelCase
                "order": 1,
                "title": "模型",
                "primaryAtomIds": ["sym_a"],
                "informedByAtomIds": ["sym_a"],
            }
        ],
        "memberInterpretations": [
            {"atomId": "sym_a", "responsibility": "r", "role": "CLASS"}
        ],
    }
    norm = _normalize_payload(payload)
    result = SemanticAnalysisResult.model_validate(norm)

    assert result.overall_responsibility == "职责"
    assert len(result.execution_flow) == 1
    assert result.execution_flow[0].atom_id == "sym_a"
    assert result.execution_flow[0].step_order == 0
    assert result.semantic_groups[0].primary_atom_ids == ["sym_a"]
    assert result.member_interpretations[0].atom_id == "sym_a"


def test_missing_fields_get_defaults() -> None:
    # 模型可能省略大量字段，应得到默认值而非失败
    payload = {"analysis_id": "a1", "context_id": "c", "revision": "r", "snapshot_hash": "h"}
    result = SemanticAnalysisResult.model_validate(payload)
    assert result.overall_responsibility == ""
    assert result.execution_flow == []
    assert result.semantic_groups == []
    assert result.unresolved_findings == []


def test_full_roundtrip_rules_style() -> None:
    # 模拟 rules.py 那次失败的结构：execution_flow 带 camelCase + extra
    payload = {
        "analysis_id": "a1",
        "context_id": "c",
        "revision": "r",
        "snapshot_hash": "h",
        "overall_responsibility": "执行评审规则",
        "execution_flow": [
            {
                "step_order": 0,
                "atom_id": "PYTHON:app/rules.py:review_document:FUNCTION",
                "description": "读取 blocks",
                "evidence_refs": [
                    {"atom_id": "PYTHON:app/rules.py:combined_text:FUNCTION"}
                ],
            }
        ],
    }
    result = SemanticAnalysisResult.model_validate(_normalize_payload(payload))
    assert len(result.execution_flow) == 1
    assert result.execution_flow[0].description == "读取 blocks"


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
