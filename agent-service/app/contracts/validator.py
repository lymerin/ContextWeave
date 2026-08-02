"""MCP 契约校验器——读取共享 JSON Schema 并对 payload/response 校验。

共享 schema 位于仓库根 contracts/mcp/（唯一权威源）。本模块提供:
  - list_contracts()     列出可用契约
  - validate_payload(tool, payload)   校验工具输入
  - validate_response(tool, response) 校验工具输出

用法（测试环境）:
    from app.contracts.validator import validate_payload
    validate_payload("devcollab.binding.list", {...})

契约文件命名约定: <tool_short>_input.json / <tool_short>_output.json。
tool 名与文件的映射见 _CONTRACT_FILES。
"""

from __future__ import annotations

import json
from functools import lru_cache
from pathlib import Path
from typing import Any

import jsonschema

# 共享 schema 目录（仓库根 contracts/mcp/）
_CONTRACTS_DIR = Path(__file__).resolve().parents[3] / "contracts" / "mcp"

# tool 名 → (input 文件名, output 文件名)
_CONTRACT_FILES: dict[str, tuple[str, str]] = {
    "devcollab.workspace.get_context": (
        "workspace_get_context_input.json",
        "workspace_get_context_output.json",
    ),
    "devcollab.code.read": (
        "code_read_input.json",
        "code_read_output.json",
    ),
    "devcollab.binding.list": (
        "binding_list_input.json",
        "binding_list_output.json",
    ),
    "devcollab.binding.list_batch": (
        "binding_list_batch_input.json",
        "binding_list_batch_output.json",
    ),
    "devcollab.document.get_structure": (
        "document_get_structure_input.json",
        "document_get_structure_output.json",
    ),
    "devcollab.document.find_candidates": (
        "document_find_candidates_input.json",
        "document_find_candidates_output.json",
    ),
    "devcollab.repository.list_files": (
        "repository_list_files_input.json",
        "repository_list_files_output.json",
    ),
    "devcollab.review.submit_document_change": (
        "submit_document_change_input.json",
        "submit_document_change_output.json",
    ),
}


@lru_cache(maxsize=None)
def _load_schema(filename: str) -> dict[str, Any]:
    path = _CONTRACTS_DIR / filename
    if not path.exists():
        raise FileNotFoundError(f"契约 schema 不存在: {path}")
    return json.loads(path.read_text(encoding="utf-8"))


def list_contracts() -> tuple[str, ...]:
    """返回所有有契约的工具名。"""
    return tuple(sorted(_CONTRACT_FILES.keys()))


def validate_payload(tool: str, payload: dict[str, Any]) -> list[str]:
    """校验工具输入的 payload。返回校验错误列表（空=通过）。"""
    if tool not in _CONTRACT_FILES:
        return [f"无 {tool} 的契约 schema"]
    schema = _load_schema(_CONTRACT_FILES[tool][0])
    return _validate(schema, payload)


def validate_response(tool: str, response: dict[str, Any]) -> list[str]:
    """校验工具输出。返回校验错误列表（空=通过）。"""
    if tool not in _CONTRACT_FILES:
        return [f"无 {tool} 的契约 schema"]
    schema = _load_schema(_CONTRACT_FILES[tool][1])
    return _validate(schema, response)


def _validate(schema: dict[str, Any], instance: dict[str, Any]) -> list[str]:
    try:
        jsonschema.validate(instance, schema)
        return []
    except jsonschema.ValidationError as exc:
        return [f"{exc.path or '$'}: {exc.message}"]
