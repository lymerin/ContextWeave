from __future__ import annotations

import hashlib
from dataclasses import dataclass
from typing import Any

from app.planning.binding_candidates import BindingPlanValidationError
from app.schemas.binding_plans import (
    BindingPlan,
    BindingRole,
    BindingSelection,
    CodeCandidate,
    DocumentBlockPlan,
)
from app.schemas.document_block_content import (
    DocumentBlockContent,
    DocumentBlockContentPlan,
)
from app.schemas.plans import AgentPlan, Decision


@dataclass(frozen=True)
class ProgramDocumentPlan:
    agent_plan: AgentPlan
    binding_plan: BindingPlan
    block_content_plan: DocumentBlockContentPlan


class ProgramDocumentPlanAssembler:
    """Own document structure and Binding identity; the model supplies prose only."""

    def assemble(
        self,
        model_context: dict[str, Any],
        code_candidates: tuple[CodeCandidate, ...],
        block_plans: tuple[DocumentBlockPlan, ...],
        content_plan: DocumentBlockContentPlan,
    ) -> ProgramDocumentPlan:
        ordered_plans = tuple(
            sorted(block_plans, key=lambda item: (item.sortOrder, item.blockKey))
        )
        ordered_content = tuple(content_plan.blocks)
        expected_keys = [item.blockKey for item in ordered_plans]
        actual_keys = [item.blockKey for item in ordered_content]
        if actual_keys != expected_keys:
            raise BindingPlanValidationError(
                [
                    _issue(
                        "blocks",
                        "DOCUMENT_BLOCK_CONTENT_PLAN_MISMATCH",
                        "Model block content must match the program Block order exactly",
                    )
                ]
            )
        if any(item.status == "INSUFFICIENT_EVIDENCE" for item in ordered_content):
            raise BindingPlanValidationError(
                [
                    _issue(
                        f"blocks.{item.blockKey}",
                        "BLOCK_INSUFFICIENT_EVIDENCE",
                        "Required document Block has insufficient code evidence",
                    )
                    for item in ordered_content
                    if item.status == "INSUFFICIENT_EVIDENCE"
                ]
            )

        code_by_id = {item.candidateId: item for item in code_candidates}
        document_operation_id = _document_operation_id(ordered_plans)
        operations: list[dict[str, Any]] = [
            {
                "clientOperationId": document_operation_id,
                "sequenceNumber": 1,
                "operationType": "CREATE_DOCUMENT",
                "proposedDocumentTitle": _document_title(ordered_plans),
                "proposedDocumentType": "BACKEND",
            }
        ]
        evidence: list[dict[str, Any]] = []
        selections: list[BindingSelection] = []

        first_primary = self._single_primary(ordered_plans[0], code_by_id)
        evidence.append(
            _evidence(document_operation_id, first_primary, "程序根据主要代码职责创建文档。")
        )

        for sequence_number, (block_plan, generated) in enumerate(
            zip(ordered_plans, ordered_content, strict=True),
            start=2,
        ):
            primary = self._single_primary(block_plan, code_by_id)
            operations.append(
                {
                    "clientOperationId": block_plan.blockKey,
                    "sequenceNumber": sequence_number,
                    "operationType": "ADD_BLOCK",
                    "createdDocumentClientOperationId": document_operation_id,
                    "proposedBlockType": "PARAGRAPH",
                    "proposedPlainText": f"## {block_plan.title}\n\n{generated.content}",
                    "proposedContentFormat": "MARKDOWN",
                }
            )
            evidence.append(
                _evidence(
                    block_plan.blockKey,
                    primary,
                    f"{block_plan.targetKind.value} 的主要代码证据。",
                )
            )
            selections.extend(
                self._binding_selections(block_plan, generated, primary, code_by_id)
            )

        return ProgramDocumentPlan(
            agent_plan=AgentPlan.model_validate(
                {
                    "decision": Decision.SUBMIT_REVIEW.value,
                    "summary": "程序按固定代码职责生成文档变更",
                    "rationale": "文档结构和代码关联由程序确定，模型只生成受证据约束的正文",
                    "operations": operations,
                    "bindingProposals": [],
                    "evidence": evidence,
                }
            ),
            binding_plan=BindingPlan(selections=selections),
            block_content_plan=content_plan,
        )

    @staticmethod
    def replace_block(
        content_plan: DocumentBlockContentPlan,
        repaired: DocumentBlockContent,
    ) -> DocumentBlockContentPlan:
        if repaired.blockKey not in {item.blockKey for item in content_plan.blocks}:
            raise BindingPlanValidationError(
                [
                    _issue(
                        "blockKey",
                        "DOCUMENT_BLOCK_CONTENT_PLAN_MISMATCH",
                        "Repair returned an unknown blockKey",
                    )
                ]
            )
        return DocumentBlockContentPlan(
            blocks=[
                repaired if item.blockKey == repaired.blockKey else item
                for item in content_plan.blocks
            ]
        )

    @staticmethod
    def _single_primary(
        block_plan: DocumentBlockPlan,
        code_by_id: dict[str, CodeCandidate],
    ) -> CodeCandidate:
        if len(block_plan.primaryCandidateIds) != 1:
            raise BindingPlanValidationError(
                [
                    _issue(
                        block_plan.blockKey,
                        "AMBIGUOUS_PRIMARY_CANDIDATE",
                        "Program-owned Block requires exactly one PRIMARY candidate",
                    )
                ]
            )
        candidate = code_by_id.get(block_plan.primaryCandidateIds[0])
        if candidate is None:
            raise BindingPlanValidationError(
                [
                    _issue(
                        block_plan.blockKey,
                        "UNKNOWN_CANDIDATE_ID",
                        "Program-owned PRIMARY candidate is missing",
                    )
                ]
            )
        return candidate

    @staticmethod
    def _binding_selections(
        block_plan: DocumentBlockPlan,
        generated: DocumentBlockContent,
        primary: CodeCandidate,
        code_by_id: dict[str, CodeCandidate],
    ) -> list[BindingSelection]:
        details = {item.candidateId: item for item in generated.supportingSelections}
        allowed = list(block_plan.supportingCandidateIds)
        selected_by_model = list(details)
        unknown = [item for item in selected_by_model if item not in allowed]
        if unknown:
            raise BindingPlanValidationError(
                [
                    _issue(
                        f"blocks.{block_plan.blockKey}.supportingSelections",
                        "UNKNOWN_CANDIDATE_ID",
                        "SUPPORTING selection is outside the program candidate set",
                    )
                ]
            )
        required_supporting = [
            item
            for item in block_plan.requiredCandidateIds
            if item != primary.candidateId
        ]
        ordered_supporting = [
            item
            for item in allowed
            if item in {*required_supporting, *selected_by_model}
        ]
        if any(item not in code_by_id for item in ordered_supporting):
            raise BindingPlanValidationError(
                [
                    _issue(
                        block_plan.blockKey,
                        "UNKNOWN_CANDIDATE_ID",
                        "Program-owned SUPPORTING candidate is missing",
                    )
                ]
            )
        selections = [
            BindingSelection(
                blockKey=block_plan.blockKey,
                codeCandidateId=primary.candidateId,
                role=BindingRole.PRIMARY,
                ordinal=1,
                reason="程序根据唯一主要候选确定该代码承担文档块的核心职责。",
                confidence=1,
            )
        ]
        selections.extend(
            BindingSelection(
                blockKey=block_plan.blockKey,
                codeCandidateId=candidate_id,
                role=BindingRole.SUPPORTING,
                ordinal=ordinal,
                reason=(
                    details[candidate_id].reason
                    if candidate_id in details
                    else "程序根据 requiredCandidateIds 保留必要的协作代码证据。"
                ),
                confidence=(
                    details[candidate_id].confidence if candidate_id in details else 1
                ),
            )
            for ordinal, candidate_id in enumerate(ordered_supporting, start=2)
        )
        return selections


def build_block_content_context(
    model_context: dict[str, Any],
    code_candidates: tuple[CodeCandidate, ...],
    block_plans: tuple[DocumentBlockPlan, ...],
) -> dict[str, Any]:
    candidates = {item.candidateId: item for item in code_candidates}
    code_by_path = {
        str(item.get("filePath")): str(item.get("content") or "")
        for item in model_context.get("codeFiles", [])
    }
    return {
        "workspace": model_context.get("workspace", {}),
        "task": model_context.get("task", {}),
        "blocks": [
            {
                **block_plan.model_dump(mode="json", exclude_none=True),
                "codeEvidence": [
                    _candidate_evidence(candidates[candidate_id], code_by_path)
                    for candidate_id in [
                        *block_plan.primaryCandidateIds,
                        *block_plan.supportingCandidateIds,
                    ]
                    if candidate_id in candidates
                ],
            }
            for block_plan in sorted(
                block_plans,
                key=lambda item: (item.sortOrder, item.blockKey),
            )
        ],
    }


def _candidate_evidence(
    candidate: CodeCandidate,
    code_by_path: dict[str, str],
) -> dict[str, Any]:
    source = code_by_path.get(candidate.filePath, "")
    lines = source.splitlines()
    if candidate.startLine is not None and candidate.endLine is not None:
        source = "\n".join(lines[candidate.startLine - 1 : candidate.endLine])
    return {
        "candidateId": candidate.candidateId,
        "displayName": candidate.displayName,
        "qualifiedName": candidate.qualifiedName,
        "anchorKind": candidate.anchorKind.value,
        "filePath": candidate.filePath,
        "startLine": candidate.startLine,
        "endLine": candidate.endLine,
        "source": source,
    }


def _document_operation_id(block_plans: tuple[DocumentBlockPlan, ...]) -> str:
    raw = "\0".join(item.blockKey for item in block_plans)
    return "create_document_" + hashlib.sha256(raw.encode("utf-8")).hexdigest()[:16]


def _document_title(block_plans: tuple[DocumentBlockPlan, ...]) -> str:
    first = block_plans[0].title.strip()
    if "：" in first:
        first = first.split("：", 1)[1]
    return f"{first} 代码职责说明"[:200]


def _evidence(
    operation_id: str,
    candidate: CodeCandidate,
    description: str,
) -> dict[str, Any]:
    return {
        "clientOperationId": operation_id,
        "repositoryId": str(candidate.repositoryId),
        "filePath": candidate.filePath,
        "startLine": candidate.startLine,
        "endLine": candidate.endLine,
        "description": description,
    }


def _issue(path: str, code: str, message: str) -> dict[str, str]:
    return {"path": path, "code": code, "message": message}
