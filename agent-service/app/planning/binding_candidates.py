from __future__ import annotations

import hashlib
from dataclasses import dataclass
from typing import Any
from uuid import UUID

from app.code_atom import PythonCodeAtomExtractor, java_symbol_to_atom
from app.schemas.binding_plans import (
    BindingPlan,
    BlockTargetKind,
    CodeAnchorKind,
    CodeCandidate,
    DocumentAnchorCandidate,
    DocumentBlockPlan,
)
from app.schemas.plans import (
    AgentPlan,
    BindingAction,
    BindingProposal,
    Decision,
    OperationType,
    PlanEvidence,
)

MAX_CODE_CANDIDATES = 40
MAX_DOCUMENT_CANDIDATES = 40
MAX_PREVIEW_CHARACTERS = 600


class BindingPlanValidationError(ValueError):
    def __init__(self, issues: list[dict[str, str]]) -> None:
        super().__init__("Binding plan failed validation")
        self.issues = issues


@dataclass(frozen=True)
class BindingCandidateSet:
    code: tuple[CodeCandidate, ...]
    documents: tuple[DocumentAnchorCandidate, ...]
    block_plans: tuple[DocumentBlockPlan, ...] = ()

    def model_payload(self) -> dict[str, Any]:
        return {
            "codeCandidates": [
                item.model_dump(mode="json", exclude_none=True) for item in self.code
            ],
            "documentAnchorCandidates": [
                item.model_dump(mode="json", exclude_none=True) for item in self.documents
            ],
            "documentBlockPlans": [
                item.model_dump(mode="json", exclude_none=True) for item in self.block_plans
            ],
        }


class BindingCandidateBuilder:
    def __init__(
        self,
        *,
        max_code_candidates: int = MAX_CODE_CANDIDATES,
        max_document_candidates: int = MAX_DOCUMENT_CANDIDATES,
        max_preview_characters: int = MAX_PREVIEW_CHARACTERS,
    ) -> None:
        self._max_code_candidates = min(max_code_candidates, MAX_CODE_CANDIDATES)
        self._max_document_candidates = min(max_document_candidates, MAX_DOCUMENT_CANDIDATES)
        self._max_preview_characters = min(max_preview_characters, MAX_PREVIEW_CHARACTERS)

    def build(
        self,
        context: dict[str, Any],
        plan: AgentPlan,
    ) -> BindingCandidateSet:
        ordered_code = self.build_code(context)
        block_plans = tuple(
            DocumentBlockPlan.model_validate(item) for item in context.get("documentBlockPlans", [])
        )
        document_candidates = self._document_candidates(context, plan)
        if not block_plans:
            block_plans = _existing_block_plans(
                ordered_code,
                tuple(document_candidates[: self._max_document_candidates]),
            )
        return BindingCandidateSet(
            ordered_code,
            tuple(document_candidates[: self._max_document_candidates]),
            block_plans,
        )

    def build_code(self, context: dict[str, Any]) -> tuple[CodeCandidate, ...]:
        workspace = context.get("workspace", {})
        repository_id = UUID(str(workspace["repositoryId"]))
        revision = str(workspace.get("revision") or "").strip()
        if not revision:
            raise BindingPlanValidationError(
                [
                    {
                        "path": "workspace.revision",
                        "code": "REVISION_REQUIRED",
                        "message": "A fixed repository revision is required",
                    }
                ]
            )
        task = context.get("task", {})
        task_id = str(
            task.get("taskId") or task.get("unitId") or task.get("semanticUnitId") or "binding-task"
        )
        code_candidates: list[CodeCandidate] = []
        code_files = sorted(
            context.get("codeFiles", []),
            key=lambda item: str(item.get("filePath") or ""),
        )
        for item in code_files:
            if len(code_candidates) >= self._max_code_candidates:
                break
            code_candidates.extend(
                self._code_candidates(
                    repository_id,
                    revision,
                    task_id,
                    item,
                    self._max_code_candidates - len(code_candidates),
                )
            )
        ordered_code = sorted(code_candidates, key=_candidate_source_key)
        return tuple(ordered_code[: self._max_code_candidates])

    def _code_candidates(
        self,
        repository_id: UUID,
        revision: str,
        task_id: str,
        item: dict[str, Any],
        remaining: int,
    ) -> list[CodeCandidate]:
        file_path = str(item.get("filePath") or "")
        content = str(item.get("content") or "")
        language = str(item.get("language") or "")
        result = [
            self._code_candidate(
                repository_id,
                revision,
                task_id,
                file_path,
                CodeAnchorKind.FILE,
                language,
                file_path,
                content,
            )
        ]
        normalized = language.lower()
        if normalized == "python" or file_path.lower().endswith(".py"):
            atoms = PythonCodeAtomExtractor().extract(
                content,
                file_path=file_path,
                repository_id=str(repository_id),
                revision=revision,
            )
            result.extend(
                self._atom_candidate(task_id, atom, content)
                for atom in atoms
                if atom.kind.value != "MODULE"
            )
        elif normalized == "java" or file_path.lower().endswith(".java"):
            for symbol in item.get("symbols", []):
                atom = java_symbol_to_atom(
                    symbol,
                    repository_id=str(repository_id),
                    revision=revision,
                    file_path=file_path,
                )
                if atom is None:
                    continue
                result.append(self._atom_candidate(task_id, atom, content))
        file_candidate, *symbol_candidates = result
        return [
            file_candidate,
            *sorted(symbol_candidates, key=_candidate_source_key),
        ][:remaining]

    def _atom_candidate(self, task_id: str, atom: Any, content: str) -> CodeCandidate:
        preview = _line_preview(content, atom.start_line, atom.end_line)
        bounded = preview[: self._max_preview_characters]
        metadata = dict(atom.metadata)
        return CodeCandidate(
            candidateId=_candidate_id(
                task_id, atom.repository_id, atom.revision, atom.atom_id, "SYMBOL"
            ),
            repositoryId=UUID(atom.repository_id),
            revision=atom.revision,
            filePath=atom.file_path,
            anchorKind=CodeAnchorKind.SYMBOL,
            symbolKey=atom.symbol_key,
            startLine=atom.start_line,
            endLine=atom.end_line,
            language=atom.language,
            displayName=atom.display_name,
            contentPreview=bounded,
            contentHash=hashlib.sha256(bounded.encode("utf-8")).hexdigest(),
            atomId=atom.atom_id,
            atomKind=atom.kind.value,
            qualifiedName=atom.qualified_name,
            signature=atom.signature,
            parentAtomId=atom.parent_atom_id,
            routeMethod=atom.route_method,
            routePath=atom.route_path,
            responseModel=atom.response_model,
            directCalls=_metadata_list(metadata.get("directCalls")),
            annotations=_metadata_list(metadata.get("annotations")),
            schemaModel=metadata.get("isPydanticModel") == "true",
        )

    def _code_candidate(
        self,
        repository_id: UUID,
        revision: str,
        task_id: str,
        file_path: str,
        anchor_kind: CodeAnchorKind,
        language: str,
        display_name: str,
        preview: str,
        *,
        symbol_key: str | None = None,
        start_line: int | None = None,
        end_line: int | None = None,
    ) -> CodeCandidate:
        bounded = preview[: self._max_preview_characters]
        return CodeCandidate(
            candidateId=_candidate_id(
                task_id,
                str(repository_id),
                revision,
                f"{file_path}:{symbol_key or anchor_kind.value}",
                anchor_kind.value,
            ),
            repositoryId=repository_id,
            revision=revision,
            filePath=file_path,
            anchorKind=anchor_kind,
            symbolKey=symbol_key,
            startLine=start_line,
            endLine=end_line,
            language=language or None,
            displayName=display_name,
            contentPreview=bounded,
            contentHash=hashlib.sha256(bounded.encode("utf-8")).hexdigest(),
        )

    def _document_candidates(
        self,
        context: dict[str, Any],
        plan: AgentPlan,
    ) -> list[DocumentAnchorCandidate]:
        result: list[DocumentAnchorCandidate] = []
        for document in context.get("documents", []):
            document_id = UUID(str(document["documentId"]))
            title = str(document.get("title") or document_id)
            result.append(
                DocumentAnchorCandidate(
                    candidateId=_opaque_id("doc"),
                    documentId=document_id,
                    documentTitle=title,
                    blockLabel="整篇文档",
                )
            )
            for block in document.get("blocks", []):
                if len(result) >= self._max_document_candidates:
                    return result
                result.append(
                    DocumentAnchorCandidate(
                        candidateId=_opaque_id("doc"),
                        documentId=document_id,
                        blockId=UUID(str(block["blockId"])),
                        documentTitle=title,
                        blockLabel=_block_label(block),
                        contentPreview=_block_preview(block)[: self._max_preview_characters],
                        contentSchemaVersion=_positive_int(
                            block.get("content", {}).get("schemaVersion")
                            if isinstance(block.get("content"), dict)
                            else None
                        ),
                        sortOrder=_non_negative_int(block.get("sortOrder")),
                    )
                )

        create_titles = {
            item.clientOperationId: item.proposedDocumentTitle or "新建文档"
            for item in plan.operations
            if item.operationType == OperationType.CREATE_DOCUMENT
        }
        for operation in plan.operations:
            if operation.operationType != OperationType.CREATE_DOCUMENT:
                continue
            result.append(
                DocumentAnchorCandidate(
                    candidateId=_opaque_id("doc"),
                    createdDocumentClientOperationId=operation.clientOperationId,
                    documentTitle=operation.proposedDocumentTitle or "新建文档",
                    blockLabel="整篇文档",
                )
            )
        for operation in plan.operations:
            if operation.operationType != OperationType.ADD_BLOCK:
                continue
            add_block_document_id = operation.documentId
            created_document = operation.createdDocumentClientOperationId
            title = (
                _existing_title(context, str(add_block_document_id))
                if add_block_document_id is not None
                else create_titles.get(created_document or "", "新建文档")
            )
            result.append(
                DocumentAnchorCandidate(
                    candidateId=_opaque_id("doc"),
                    documentId=add_block_document_id,
                    createdDocumentClientOperationId=created_document,
                    createdBlockClientOperationId=operation.clientOperationId,
                    documentTitle=title,
                    blockLabel=operation.proposedBlockType or "新建 Block",
                    contentPreview=_operation_preview(operation)[: self._max_preview_characters],
                    contentSchemaVersion=(
                        operation.proposedContent.schemaVersion
                        if operation.proposedContent is not None
                        else None
                    ),
                    sortOrder=operation.sequenceNumber,
                )
            )
        return result


class BindingPlanExpander:
    def expand(
        self,
        plan: AgentPlan,
        binding_plan: BindingPlan,
        candidates: BindingCandidateSet,
    ) -> AgentPlan:
        code_by_id = {item.candidateId: item for item in candidates.code}
        operation_by_key = {item.clientOperationId: item for item in plan.operations}
        block_plan_by_key = {item.blockKey: item for item in candidates.block_plans}
        issues: list[dict[str, str]] = []
        for index, selection in enumerate(binding_plan.selections):
            if selection.codeCandidateId not in code_by_id:
                issues.append(
                    _issue(
                        f"selections[{index}].codeCandidateId",
                        "UNKNOWN_CANDIDATE_ID",
                        "Select only a supplied codeCandidateId",
                    )
                )
            if selection.blockKey not in block_plan_by_key:
                issues.append(
                    _issue(
                        f"selections[{index}].blockKey",
                        "UNKNOWN_CANDIDATE_ID",
                        "Select only a supplied blockKey",
                    )
                )
        if issues:
            raise BindingPlanValidationError(issues)

        proposals: list[BindingProposal] = []
        evidence = list(plan.evidence)
        evidence_keys = {
            (str(item.repositoryId), item.filePath, item.startLine, item.endLine)
            for item in evidence
        }
        sequence = len(plan.operations)
        ordered_selections = sorted(
            binding_plan.selections,
            key=lambda selection: (
                block_plan_by_key[selection.blockKey].sortOrder,
                selection.ordinal,
                _candidate_source_key(code_by_id[selection.codeCandidateId]),
            ),
        )
        for index, selection in enumerate(ordered_selections, start=1):
            code = code_by_id[selection.codeCandidateId]
            operation = operation_by_key.get(selection.blockKey)
            document = _document_candidate_for_block_key(
                selection.blockKey,
                operation,
                candidates.documents,
            )
            if document is None:
                raise BindingPlanValidationError(
                    [
                        _issue(
                            selection.blockKey,
                            "DOCUMENT_BLOCK_PLAN_MISMATCH",
                            "Planned block has no real document target",
                        )
                    ]
                )
            sequence += 1
            proposals.append(
                BindingProposal(
                    clientBindingProposalId=f"binding-{index}-{code.candidateId[-8:]}",
                    sequenceNumber=sequence,
                    action=BindingAction.UPSERT_BINDING,
                    repositoryId=code.repositoryId,
                    revision=code.revision,
                    filePath=code.filePath,
                    anchorKind=code.anchorKind.value,
                    symbolKey=code.symbolKey,
                    startLine=code.startLine,
                    endLine=code.endLine,
                    documentId=document.documentId,
                    createdDocumentClientOperationId=(document.createdDocumentClientOperationId),
                    blockId=document.blockId,
                    createdBlockClientOperationId=(document.createdBlockClientOperationId),
                    candidateId=code.candidateId,
                    documentAnchorCandidateId=document.candidateId,
                    reason=selection.reason,
                    confidence=selection.confidence,
                    bindingRole=selection.role.value,
                    bindingOrdinal=selection.ordinal,
                )
            )
            evidence_key = (
                str(code.repositoryId),
                code.filePath,
                code.startLine,
                code.endLine,
            )
            if evidence_key not in evidence_keys:
                evidence.append(
                    PlanEvidence(
                        repositoryId=code.repositoryId,
                        filePath=code.filePath,
                        startLine=code.startLine,
                        endLine=code.endLine,
                        description=selection.reason,
                    )
                )
                evidence_keys.add(evidence_key)
        decision = Decision.SUBMIT_REVIEW if plan.operations or proposals else Decision.NO_CHANGE
        return plan.model_copy(
            update={
                "decision": decision,
                "bindingProposals": proposals,
                "evidence": evidence,
            }
        )


def _candidate_id(
    task_id: str, repository_id: str, revision: str, atom_id: str, anchor_kind: str
) -> str:
    raw = "\0".join((task_id, repository_id, revision, atom_id, anchor_kind))
    return "candidate_" + hashlib.sha256(raw.encode("utf-8")).hexdigest()[:24]


def _opaque_id(prefix: str) -> str:
    import secrets

    return f"{prefix}_{secrets.token_urlsafe(12)}"


def _metadata_list(value: str | None) -> list[str]:
    return [item for item in (value or "").split(",") if item]


def _candidate_source_key(candidate: CodeCandidate) -> tuple[Any, ...]:
    precise_rank = 0 if candidate.startLine is not None else 1
    return (
        candidate.filePath,
        precise_rank,
        candidate.startLine if candidate.startLine is not None else 2**31 - 1,
        candidate.endLine if candidate.endLine is not None else 2**31 - 1,
        candidate.symbolKey or "",
        candidate.candidateId,
    )


def _document_candidate_for_block_key(
    block_key: str,
    operation: Any,
    candidates: tuple[DocumentAnchorCandidate, ...],
) -> DocumentAnchorCandidate | None:
    direct = next((item for item in candidates if item.candidateId == block_key), None)
    if direct is not None:
        return direct
    if operation is None:
        return None
    if operation.operationType == OperationType.ADD_BLOCK:
        return next(
            (
                item
                for item in candidates
                if item.createdBlockClientOperationId == operation.clientOperationId
            ),
            None,
        )
    if operation.operationType == OperationType.UPDATE_BLOCK:
        return next(
            (item for item in candidates if item.blockId == operation.blockId),
            None,
        )
    return None


def _existing_block_plans(
    code_candidates: tuple[CodeCandidate, ...],
    document_candidates: tuple[DocumentAnchorCandidate, ...],
) -> tuple[DocumentBlockPlan, ...]:
    """Expose existing Blocks through the same constrained selection contract."""
    if not code_candidates:
        return ()
    precise = [item for item in code_candidates if item.anchorKind != CodeAnchorKind.FILE]
    allowed = (precise or list(code_candidates))[:16]
    result: list[DocumentBlockPlan] = []
    for document in document_candidates:
        if document.blockId is None:
            continue
        result.append(
            DocumentBlockPlan(
                blockKey=document.candidateId,
                title=document.blockLabel or "Existing document Block",
                purpose="Select the real code anchor described by this existing Block.",
                targetKind=BlockTargetKind.SYMBOL,
                primaryCandidateIds=[item.candidateId for item in allowed],
                supportingCandidateIds=[],
                requiredCandidateIds=[],
                allowedClaims=[],
                forbiddenClaims=[],
                sortOrder=document.sortOrder or 0,
            )
        )
    return tuple(result)


def _positive_int(value: Any) -> int | None:
    return int(value) if isinstance(value, int) and value >= 1 else None


def _non_negative_int(value: Any) -> int | None:
    return int(value) if isinstance(value, int) and value >= 0 else None


def _line_preview(content: str, start: int | None, end: int | None) -> str:
    if start is None or end is None:
        return content
    return "\n".join(content.splitlines()[start - 1 : end])


def _block_preview(block: dict[str, Any]) -> str:
    if block.get("plainText") is not None:
        return str(block["plainText"])
    return str(block.get("content") or "")


def _block_label(block: dict[str, Any]) -> str:
    preview = _block_preview(block).strip().splitlines()
    return preview[0][:80] if preview else str(block.get("type") or "Block")


def _operation_preview(operation: Any) -> str:
    if operation.proposedPlainText:
        return str(operation.proposedPlainText)
    if operation.proposedContent is not None:
        return str(operation.proposedContent.document)
    return ""


def _existing_title(context: dict[str, Any], document_id: str) -> str:
    for item in context.get("documents", []):
        if str(item.get("documentId")) == document_id:
            return str(item.get("title") or document_id)
    return document_id


def _issue(path: str, code: str, message: str) -> dict[str, str]:
    return {"path": path, "code": code, "message": message}
