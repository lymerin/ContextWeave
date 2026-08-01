from collections.abc import Awaitable, Callable
from contextlib import nullcontext
from functools import partial
from typing import Any, cast

from langgraph.graph import END, START, StateGraph

from app.clients.mcp_client import ReviewMcpClient
from app.config import Settings
from app.graph.state import AgentState
from app.graph.workflow import ContextWorkflow
from app.planning.binding_candidates import (
    BindingCandidateBuilder,
    BindingPlanExpander,
    BindingPlanValidationError,
)
from app.planning.context_serializer import build_model_context
from app.planning.document_block_plans import (
    DocumentBlockPlanBuilder,
    complete_and_validate_binding_plan,
    validate_document_operations,
)
from app.planning.validator import AgentPlanValidator, PlanValidationError
from app.profiling import ProfileStage, RuntimeMemoryProfiler
from app.providers.base import ModelProvider, ModelProviderError
from app.schemas.binding_plans import DocumentBlockPlan
from app.schemas.plans import AgentPlan, Decision
from app.tracing.trace_logger import traced

StatusCallback = Callable[[str, str, dict[str, Any]], Awaitable[None]]


class ReviewSubmissionError(RuntimeError):
    code = "REVIEW_SUBMISSION_FAILED"


class DocumentSyncWorkflow:
    def __init__(
        self,
        client: ReviewMcpClient,
        provider: ModelProvider,
        settings: Settings,
        on_status: StatusCallback,
        *,
        memory_profiler: RuntimeMemoryProfiler | None = None,
        profile_context: dict[str, str] | None = None,
    ) -> None:
        self._client = client
        self._provider = provider
        self._settings = settings
        self._on_status = on_status
        self._memory_profiler = memory_profiler
        self._profile_context = profile_context or {}
        self._validator = AgentPlanValidator(
            settings.agent_review_max_operations,
            settings.agent_review_max_evidence,
        )
        self._binding_candidates = BindingCandidateBuilder()
        self._binding_expander = BindingPlanExpander()
        self._block_plans = DocumentBlockPlanBuilder()
        context = ContextWorkflow(client, settings)
        graph = StateGraph(AgentState)
        graph.add_node("load_workspace_context", context.load_workspace_context)
        graph.add_node("read_selected_code", context.read_selected_code)
        graph.add_node("list_existing_bindings", context.list_existing_bindings)
        graph.add_node("resolve_documents", context.resolve_documents)
        graph.add_node("read_document_structures", context.read_document_structures)
        graph.add_node("build_context_bundle", context.build_context_bundle)
        graph.add_node("plan_changes", self.plan_changes)
        graph.add_node("validate_plan", self.validate_plan)
        graph.add_node("repair_plan", self.repair_plan)
        graph.add_node("validate_repaired_plan", self.validate_repaired_plan)
        graph.add_node("plan_bindings", self.plan_bindings)
        graph.add_node("finish_no_change", self.finish_no_change)
        graph.add_node("submit_review", self.submit_review)
        graph.add_node("fail_run", self.fail_run)
        graph.add_edge(START, "load_workspace_context")
        graph.add_edge("load_workspace_context", "read_selected_code")
        graph.add_edge("read_selected_code", "list_existing_bindings")
        graph.add_edge("list_existing_bindings", "resolve_documents")
        graph.add_edge("resolve_documents", "read_document_structures")
        graph.add_edge("read_document_structures", "build_context_bundle")
        graph.add_edge("build_context_bundle", "plan_changes")
        graph.add_edge("plan_changes", "validate_plan")
        graph.add_conditional_edges(
            "validate_plan",
            self._route,
            {
                "NO_CHANGE": "finish_no_change",
                "SUBMIT_REVIEW": "submit_review",
                "REPAIR": "repair_plan",
                "BINDING": "plan_bindings",
            },
        )
        graph.add_edge("repair_plan", "validate_repaired_plan")
        graph.add_conditional_edges(
            "validate_repaired_plan",
            self._route,
            {
                "NO_CHANGE": "finish_no_change",
                "SUBMIT_REVIEW": "submit_review",
                "INVALID": "fail_run",
                "BINDING": "plan_bindings",
            },
        )
        graph.add_conditional_edges(
            "plan_bindings",
            self._route,
            {
                "NO_CHANGE": "finish_no_change",
                "SUBMIT_REVIEW": "submit_review",
            },
        )
        graph.add_edge("finish_no_change", END)
        graph.add_edge("submit_review", END)
        graph.add_edge("fail_run", END)
        self.graph = graph.compile()

    async def execute_context_bundle(self, state: AgentState) -> dict[str, Any]:
        mutable = cast(dict[str, Any], state)
        mutable.update(await self.plan_changes(state))
        mutable.update(await self.validate_plan(state))
        route = self._route(state)
        if route == "REPAIR":
            mutable.update(await self.repair_plan(state))
            mutable.update(await self.validate_repaired_plan(state))
            route = self._route(state)
        if route == "BINDING":
            mutable.update(await self.plan_bindings(state))
            route = self._route(state)
        if route == "NO_CHANGE":
            mutable.update(await self.finish_no_change(state))
            return mutable
        if route == "SUBMIT_REVIEW":
            mutable.update(await self.submit_review(state))
            return mutable
        await self.fail_run(state)
        raise AssertionError("unreachable")

    async def plan_changes(self, state: AgentState) -> dict[str, Any]:
        await self._on_status("PLANNING", "plan_changes", {})
        model_context = build_model_context(state["context_bundle"])
        code_candidates = self._binding_candidates.build_code(model_context)
        block_plans = self._block_plans.build(code_candidates)
        if block_plans:
            model_context["documentBlockPlans"] = [
                item.model_dump(mode="json", exclude_none=True) for item in block_plans
            ]
        input_characters = len(str(model_context))
        if input_characters > self._settings.agent_model_max_input_characters:
            raise ValueError("Model context exceeds configured input limit")
        try:
            with self._profile_stage("DOCUMENT_PROPOSAL") as profile_stage:
                profile_stage.attribute("promptCharacters", input_characters)
                plan = await traced(
                    cast(dict[str, Any], state),
                    "plan_changes",
                    None,
                    lambda: self._provider.plan_document_sync(model_context),
                    input_characters,
                )
                profile_stage.attribute("documentOperationCount", len(plan.operations))
            return {
                "model_context": model_context,
                "plan": plan,
                "validation_attempt": 0,
                "trace_events": state["trace_events"],
            }
        except ModelProviderError as exc:
            if exc.code != "MODEL_INVALID_RESPONSE":
                raise
            return {
                "model_context": model_context,
                "previous_plan": exc.raw_plan or {},
                "validation_errors": [
                    {
                        "path": "$",
                        "code": "MODEL_INVALID_RESPONSE",
                        "message": "Return a complete AgentPlan matching the schema",
                    }
                ],
                "validation_attempt": 0,
                "trace_events": state["trace_events"],
            }

    async def validate_plan(self, state: AgentState) -> dict[str, Any]:
        await self._on_status("VALIDATING", "validate_plan", {})
        plan = state.get("plan")
        if not isinstance(plan, AgentPlan):
            return {"plan_outcome": "REPAIR"}
        if plan.bindingProposals:
            return {
                "previous_plan": plan.model_dump(mode="json", exclude_none=True),
                "validation_errors": [
                    {
                        "path": "bindingProposals",
                        "code": "BINDING_PASS_REQUIRED",
                        "message": "Document planning must leave bindingProposals empty",
                    }
                ],
                "plan_outcome": "REPAIR",
            }
        try:
            block_plans = tuple(
                DocumentBlockPlan.model_validate(item)
                for item in state["model_context"].get("documentBlockPlans", [])
            )
            validate_document_operations(plan, block_plans)
            valid = self._validator.validate(plan, state["model_context"])
        except (PlanValidationError, BindingPlanValidationError) as exc:
            return {
                "previous_plan": plan.model_dump(mode="json", exclude_none=True),
                "validation_errors": (
                    exc.safe_details() if isinstance(exc, PlanValidationError) else exc.issues
                ),
                "plan_outcome": "REPAIR",
            }
        return {
            "plan": valid,
            "decision": valid.decision.value,
            "summary": valid.summary,
            "plan_outcome": "BINDING",
        }

    async def repair_plan(self, state: AgentState) -> dict[str, Any]:
        await self._on_status("REPAIRING_PLAN", "repair_plan", {})
        try:
            plan = await traced(
                cast(dict[str, Any], state),
                "repair_plan",
                None,
                lambda: self._provider.plan_document_sync(
                    state["model_context"],
                    previous_plan=state.get("previous_plan", {}),
                    validation_errors=state.get("validation_errors", []),
                ),
                len(str(state.get("validation_errors", []))),
            )
            return {
                "plan": plan,
                "validation_attempt": 1,
                "trace_events": state["trace_events"],
            }
        except ModelProviderError as exc:
            if exc.code != "MODEL_INVALID_RESPONSE":
                raise
            return {
                "plan": None,
                "validation_attempt": 1,
                "validation_errors": [
                    {
                        "path": "$",
                        "code": "MODEL_INVALID_RESPONSE",
                        "message": "Repaired output still does not match AgentPlan",
                    }
                ],
                "trace_events": state["trace_events"],
            }

    async def validate_repaired_plan(self, state: AgentState) -> dict[str, Any]:
        await self._on_status("VALIDATING", "validate_repaired_plan", {})
        plan = state.get("plan")
        if not isinstance(plan, AgentPlan):
            return {"plan_outcome": "INVALID"}
        if plan.bindingProposals:
            return {
                "validation_errors": [
                    {
                        "path": "bindingProposals",
                        "code": "BINDING_PASS_REQUIRED",
                        "message": "Document planning must leave bindingProposals empty",
                    }
                ],
                "plan_outcome": "INVALID",
            }
        try:
            block_plans = tuple(
                DocumentBlockPlan.model_validate(item)
                for item in state["model_context"].get("documentBlockPlans", [])
            )
            validate_document_operations(plan, block_plans)
            valid = self._validator.validate(plan, state["model_context"])
        except (PlanValidationError, BindingPlanValidationError) as exc:
            return {
                "validation_errors": (
                    exc.safe_details() if isinstance(exc, PlanValidationError) else exc.issues
                ),
                "plan_outcome": "INVALID",
            }
        return {
            "plan": valid,
            "decision": valid.decision.value,
            "summary": valid.summary,
            "plan_outcome": "BINDING",
        }

    async def plan_bindings(self, state: AgentState) -> dict[str, Any]:
        await self._on_status("PLANNING_BINDINGS", "plan_bindings", {})
        document_plan = cast(AgentPlan, state["plan"])
        candidates = self._binding_candidates.build(state["model_context"], document_plan)
        await self._on_status(
            "PLANNING_BINDINGS",
            "binding_candidates",
            {
                "codeCandidateCount": len(candidates.code),
                "documentCandidateCount": len(candidates.documents),
            },
        )
        if not candidates.code or not candidates.documents:
            return {
                "plan": document_plan,
                "decision": document_plan.decision.value,
                "summary": document_plan.summary,
                "plan_outcome": document_plan.decision.value,
            }

        payload = candidates.model_payload()
        previous: dict[str, Any] | None = None
        errors: list[dict[str, str]] | None = None
        for attempt in range(2):
            try:
                with self._profile_stage("BINDING_PROPOSAL") as profile_stage:
                    profile_stage.attribute(
                        "candidateCount",
                        len(candidates.code) + len(candidates.documents),
                    )
                    binding_plan = await traced(
                        cast(dict[str, Any], state),
                        "plan_bindings" if attempt == 0 else "repair_bindings",
                        None,
                        partial(
                            self._provider.plan_block_bindings,
                            payload,
                            previous_plan=previous,
                            validation_errors=errors,
                        ),
                        len(str(payload if attempt == 0 else errors)),
                    )
                    binding_plan = complete_and_validate_binding_plan(
                        binding_plan,
                        candidates.code,
                        candidates.block_plans,
                    )
                    profile_stage.attribute("bindingCount", len(binding_plan.selections))
                expanded = self._binding_expander.expand(document_plan, binding_plan, candidates)
                valid = self._validator.validate(expanded, state["model_context"])
                return {
                    "plan": valid,
                    "decision": valid.decision.value,
                    "summary": valid.summary,
                    "plan_outcome": valid.decision.value,
                    "trace_events": state["trace_events"],
                }
            except ModelProviderError as exc:
                if exc.code != "MODEL_INVALID_RESPONSE" or attempt == 1:
                    raise
                previous = exc.raw_plan or {}
                errors = [
                    {
                        "path": "$",
                        "code": "MODEL_INVALID_RESPONSE",
                        "message": "Return a complete BindingPlan matching the schema",
                    }
                ]
            except BindingPlanValidationError as exc:
                if attempt == 1:
                    raise
                previous = binding_plan.model_dump(mode="json")
                errors = exc.issues
        raise AssertionError("binding repair loop must return or raise")

    async def finish_no_change(self, state: AgentState) -> dict[str, Any]:
        plan = cast(AgentPlan, state["plan"])
        await self._on_status(
            "NO_CHANGE",
            "finish_no_change",
            {"decision": Decision.NO_CHANGE.value, "summary": plan.summary},
        )
        return {
            "decision": Decision.NO_CHANGE.value,
            "summary": plan.summary,
        }

    async def submit_review(self, state: AgentState) -> dict[str, Any]:
        await self._on_status(
            "SUBMITTING_REVIEW",
            "submit_review",
            {
                "decision": Decision.SUBMIT_REVIEW.value,
                "summary": state.get("summary"),
            },
        )
        plan = cast(AgentPlan, state["plan"])
        try:
            with self._profile_stage("REVIEW_BUILD") as profile_stage:
                profile_stage.attribute("reviewOperationCount", len(plan.operations))
                profile_stage.attribute("bindingCount", len(plan.bindingProposals))
                result = await traced(
                    cast(dict[str, Any], state),
                    "submit_review",
                    "devcollab.review.submit_document_change",
                    lambda: self._client.submit_document_change(
                        plan,
                        workspace_id=state["workspace_id"],
                        run_id=state["run_id"],
                        authorization=state["authorization"],
                    ),
                    len(str(plan.model_dump(exclude_none=True))),
                )
        except Exception as exc:
            if hasattr(exc, "code"):
                raise
            raise ReviewSubmissionError("Could not submit review request") from exc
        change_request_id = result.get("changeRequestId")
        if result.get("status") != "PENDING" or not change_request_id:
            raise ReviewSubmissionError("MCP returned an invalid review result")
        await self._on_status(
            "REVIEW_SUBMITTED",
            "submit_review",
            {
                "decision": Decision.SUBMIT_REVIEW.value,
                "summary": plan.summary,
                "changeRequestId": str(change_request_id),
            },
        )
        return {
            "decision": Decision.SUBMIT_REVIEW.value,
            "summary": plan.summary,
            "change_request_id": str(change_request_id),
        }

    def _profile_stage(self, name: str) -> Any:
        if self._memory_profiler is None:
            return nullcontext(ProfileStage.noop())
        return self._memory_profiler.stage(name, **self._profile_context)

    async def fail_run(self, state: AgentState) -> dict[str, Any]:
        raise PlanValidationError(
            [self._issue_from_dict(item) for item in state.get("validation_errors", [])]
        )

    @staticmethod
    def _issue_from_dict(item: dict[str, str]) -> Any:
        from app.schemas.plans import PlanValidationIssue

        return PlanValidationIssue.model_validate(item)

    @staticmethod
    def _route(state: AgentState) -> str:
        return state["plan_outcome"]
