from typing import Any, cast

from langgraph.graph import END, START, StateGraph

from app.clients.mcp_client import ReadOnlyMcpClient
from app.config import Settings
from app.graph.state import AgentState
from app.tracing.trace_logger import traced


# ── 原 context/ 模块的内联（该目录已删除）──────────────────────────

class BudgetExceededError(RuntimeError):
    pass


class ToolCallLimitExceededError(BudgetExceededError):
    pass


def __reserve_tool_call(current: int, maximum: int) -> int:
    if current >= maximum:
        raise ToolCallLimitExceededError("MCP tool call limit exceeded")
    return current + 1


def __build_bundle(state: dict[str, Any]) -> dict[str, Any]:
    workspace_context = state["workspace_context"]
    repository_id = state["repository_id"]
    repository: dict[str, Any] = next(
        (i for i in workspace_context.get("repositories", [])
         if i.get("repositoryId") == repository_id), {})
    bound_ids = set(state.get("bound_document_ids", []))
    documents = [
        {"source": "BOUND" if s.get("documentId") in bound_ids else "CANDIDATE",
         "documentId": s["documentId"], "structure": s}
        for s in state.get("document_structures", [])
    ]
    return {
        "runId": state["run_id"],
        "workspace": {
            "workspaceId": state["workspace_id"],
            "repositoryId": repository_id,
            "repositoryName": repository.get("name"),
            "defaultBranch": repository.get("defaultBranch"),
            "revision": state.get("revision") or repository.get("lastSyncedCommit"),
        },
        "task": {
            "selectedPaths": state["selected_paths"],
            "userInstruction": state.get("user_instruction"),
        },
        "codeFiles": state.get("code_files", []),
        "existingBindings": state.get("bindings", []),
        "documents": documents,
        "budget": {
            "toolCallsUsed": state.get("tool_call_count", 0),
            "codeCharsUsed": state.get("code_chars_used", 0),
            "truncatedFiles": state.get("truncated_files", []),
            "skippedDocumentIds": state.get("skipped_document_ids", []),
        },
    }

WORKSPACE_TOOL = "devcollab.workspace.get_context"
CODE_TOOL = "devcollab.code.read"
BINDING_TOOL = "devcollab.binding.list"
CANDIDATE_TOOL = "devcollab.document.find_candidates"
STRUCTURE_TOOL = "devcollab.document.get_structure"


class ContextWorkflow:
    def __init__(self, client: ReadOnlyMcpClient, settings: Settings) -> None:
        self._client = client
        self._settings = settings
        graph = StateGraph(AgentState)
        graph.add_node("load_workspace_context", self.load_workspace_context)
        graph.add_node("read_selected_code", self.read_selected_code)
        graph.add_node("list_existing_bindings", self.list_existing_bindings)
        graph.add_node("resolve_documents", self.resolve_documents)
        graph.add_node("read_document_structures", self.read_document_structures)
        graph.add_node("build_context_bundle", self.build_context_bundle)
        graph.add_edge(START, "load_workspace_context")
        graph.add_edge("load_workspace_context", "read_selected_code")
        graph.add_edge("read_selected_code", "list_existing_bindings")
        graph.add_edge("list_existing_bindings", "resolve_documents")
        graph.add_edge("resolve_documents", "read_document_structures")
        graph.add_edge("read_document_structures", "build_context_bundle")
        graph.add_edge("build_context_bundle", END)
        self.graph = graph.compile()

    async def _call(
        self,
        state: AgentState,
        node: str,
        tool: str,
        arguments: dict[str, Any],
    ) -> dict[str, Any]:
        state["tool_call_count"] = _reserve_tool_call(
            state.get("tool_call_count", 0),
            self._settings.agent_max_tool_calls,
        )
        return cast(
            dict[str, Any],
            await traced(
                cast(dict[str, Any], state),
                node,
                tool,
                lambda: self._client.call_tool(tool, arguments, state["authorization"]),
                len(str(arguments)),
            ),
        )

    async def load_workspace_context(self, state: AgentState) -> dict[str, Any]:
        result = await self._call(
            state,
            "load_workspace_context",
            WORKSPACE_TOOL,
            {"workspaceId": state["workspace_id"]},
        )
        repositories = result.get("repositories", [])
        if not any(item.get("repositoryId") == state["repository_id"] for item in repositories):
            raise ValueError("repositoryId is not registered in the workspace")
        return {
            "workspace_context": result,
            "tool_call_count": state["tool_call_count"],
            "trace_events": state["trace_events"],
        }

    async def read_selected_code(self, state: AgentState) -> dict[str, Any]:
        remaining = self._settings.agent_max_code_chars
        files: list[dict[str, Any]] = []
        truncated_files: list[str] = []
        for path in state["selected_paths"]:
            result = await self._call(
                state,
                "read_selected_code",
                CODE_TOOL,
                {
                    "workspaceId": state["workspace_id"],
                    "repositoryId": state["repository_id"],
                    "path": path,
                },
            )
            content = str(result.get("content", ""))
            truncated = bool(result.get("truncated", False))
            if len(content) > remaining:
                content = content[:remaining]
                truncated = True
            if truncated:
                truncated_files.append(path)
            files.append(
                {
                    "filePath": result.get("path", path),
                    "language": result.get("language"),
                    "content": content,
                    "truncated": truncated,
                }
            )
            remaining -= len(content)
        return {
            "code_files": files,
            "code_chars_used": self._settings.agent_max_code_chars - remaining,
            "truncated_files": truncated_files,
            "tool_call_count": state["tool_call_count"],
            "trace_events": state["trace_events"],
        }

    async def list_existing_bindings(self, state: AgentState) -> dict[str, Any]:
        all_bindings: list[dict[str, Any]] = []
        bound_ids: list[str] = list(state.get("preferred_document_ids", []))
        unbound_paths: list[str] = []
        for path in state["selected_paths"]:
            result = await self._call(
                state,
                "list_existing_bindings",
                BINDING_TOOL,
                {
                    "workspaceId": state["workspace_id"],
                    "repositoryId": state["repository_id"],
                    "filePath": path,
                },
            )
            bindings = list(result.get("bindings", []))
            if not bindings:
                unbound_paths.append(path)
            for binding in bindings:
                enriched = {"filePath": path, **binding}
                all_bindings.append(enriched)
                document_id = binding.get("documentId")
                if document_id and document_id not in bound_ids:
                    bound_ids.append(document_id)
        return {
            "bindings": all_bindings,
            "bound_document_ids": bound_ids[: self._settings.agent_max_bound_documents],
            "candidate_documents": [{"unboundPath": path} for path in unbound_paths],
            "tool_call_count": state["tool_call_count"],
            "trace_events": state["trace_events"],
        }

    async def resolve_documents(self, state: AgentState) -> dict[str, Any]:
        candidates: list[dict[str, Any]] = []
        seen = set(state.get("bound_document_ids", []))
        unbound_paths = [
            item["unboundPath"]
            for item in state.get("candidate_documents", [])
            if "unboundPath" in item
        ]
        for path in unbound_paths:
            result = await self._call(
                state,
                "resolve_documents",
                CANDIDATE_TOOL,
                {
                    "workspaceId": state["workspace_id"],
                    "repositoryId": state["repository_id"],
                    "filePath": path,
                    "limit": self._settings.agent_max_candidate_documents,
                },
            )
            for candidate in result.get("candidates", []):
                document_id = candidate.get("documentId")
                if document_id and document_id not in seen:
                    seen.add(document_id)
                    candidates.append(candidate)
                    if len(candidates) >= self._settings.agent_max_candidate_documents:
                        break
            if len(candidates) >= self._settings.agent_max_candidate_documents:
                break
        return {
            "candidate_documents": candidates,
            "tool_call_count": state["tool_call_count"],
            "trace_events": state["trace_events"],
        }

    async def read_document_structures(self, state: AgentState) -> dict[str, Any]:
        ids = list(state.get("bound_document_ids", []))
        ids.extend(
            item["documentId"]
            for item in state.get("candidate_documents", [])
            if item.get("documentId") not in ids
        )
        selected = ids[: self._settings.agent_max_document_structures]
        structures: list[dict[str, Any]] = []
        for document_id in selected:
            structures.append(
                await self._call(
                    state,
                    "read_document_structures",
                    STRUCTURE_TOOL,
                    {
                        "workspaceId": state["workspace_id"],
                        "documentId": document_id,
                        "includeBlockContent": True,
                    },
                )
            )
        return {
            "document_structures": structures,
            "skipped_document_ids": ids[len(selected) :],
            "tool_call_count": state["tool_call_count"],
            "trace_events": state["trace_events"],
        }

    async def build_context_bundle(self, state: AgentState) -> dict[str, Any]:
        return {"context_bundle": _build_bundle(cast(dict[str, Any], state))}
