from typing import Any, TypedDict


class AgentState(TypedDict, total=False):
    run_id: str
    workspace_id: str
    repository_id: str
    revision: str
    selected_paths: list[str]
    preferred_document_ids: list[str]
    user_instruction: str | None
    authorization: str
    workspace_context: dict[str, Any]
    code_files: list[dict[str, Any]]
    bindings: list[dict[str, Any]]
    bound_document_ids: list[str]
    candidate_documents: list[dict[str, Any]]
    document_structures: list[dict[str, Any]]
    tool_call_count: int
    code_chars_used: int
    truncated_files: list[str]
    skipped_document_ids: list[str]
    trace_events: list[dict[str, Any]]
    errors: list[dict[str, Any]]
    context_bundle: dict[str, Any]
    model_context: dict[str, Any]
    block_content_context: dict[str, Any]
    block_content_plan: Any
    program_binding_plan: Any
    invalid_block_keys: list[str]
    repaired_block_keys: list[str]
    plan: Any
    previous_plan: dict[str, Any]
    validation_errors: list[dict[str, str]]
    validation_attempt: int
    plan_outcome: str
    decision: str
    summary: str
    change_request_id: str
