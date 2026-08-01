from enum import StrEnum
from typing import Any, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, model_validator


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)


class Decision(StrEnum):
    NO_CHANGE = "NO_CHANGE"
    SUBMIT_REVIEW = "SUBMIT_REVIEW"


class OperationType(StrEnum):
    CREATE_DOCUMENT = "CREATE_DOCUMENT"
    ADD_BLOCK = "ADD_BLOCK"
    UPDATE_BLOCK = "UPDATE_BLOCK"
    DELETE_BLOCK = "DELETE_BLOCK"


class BindingAction(StrEnum):
    UPSERT_BINDING = "UPSERT_BINDING"
    REMOVE_BINDING = "REMOVE_BINDING"


class ContentFormat(StrEnum):
    MARKDOWN = "MARKDOWN"
    TIPTAP_JSON = "TIPTAP_JSON"
    PLAIN_TEXT = "PLAIN_TEXT"


DocumentType = Literal[
    "REQUIREMENT",
    "API",
    "ARCHITECTURE",
    "DATABASE",
    "FRONTEND",
    "BACKEND",
    "TEST",
    "DEPLOYMENT",
    "ADR",
]
BlockType = Literal["PARAGRAPH", "HEADING", "CODE", "TODO"]


class BlockContent(StrictModel):
    schemaVersion: int | None = Field(default=None, ge=1)
    document: dict[str, Any]


class DocumentOperation(StrictModel):
    clientOperationId: str = Field(min_length=1, max_length=100)
    sequenceNumber: int = Field(ge=1)
    operationType: OperationType
    documentId: UUID | None = None
    createdDocumentClientOperationId: str | None = Field(default=None, max_length=100)
    blockId: UUID | None = None
    baseBlockVersion: int | None = Field(default=None, ge=0)
    proposedDocumentTitle: str | None = Field(default=None, max_length=200)
    proposedDocumentType: DocumentType | None = None
    proposedParentDocumentId: UUID | None = None
    proposedBlockType: BlockType | None = None
    proposedPlainText: str | None = Field(default=None, max_length=20_000)
    proposedContentFormat: ContentFormat | None = None
    proposedContent: BlockContent | None = None

    @model_validator(mode="after")
    def explicit_content_format(self) -> "DocumentOperation":
        has_text = self.proposedPlainText is not None
        has_document = self.proposedContent is not None
        if has_text and has_document:
            raise ValueError("operation cannot contain plain text and Tiptap JSON together")
        if has_text and self.proposedContentFormat not in {
            ContentFormat.MARKDOWN,
            ContentFormat.PLAIN_TEXT,
        }:
            raise ValueError("plain text content requires MARKDOWN or PLAIN_TEXT")
        if has_document and self.proposedContentFormat != ContentFormat.TIPTAP_JSON:
            raise ValueError("structured content requires TIPTAP_JSON")
        if not has_text and not has_document and self.proposedContentFormat is not None:
            raise ValueError("content format requires proposed content")
        return self


class BindingProposal(StrictModel):
    clientBindingProposalId: str = Field(min_length=1, max_length=100)
    sequenceNumber: int = Field(ge=1)
    action: BindingAction
    repositoryId: UUID
    revision: str | None = Field(default=None, max_length=255)
    filePath: str = Field(min_length=1, max_length=1_000)
    anchorKind: Literal["FILE", "RANGE", "SYMBOL"] = "FILE"
    symbolKey: str | None = Field(default=None, max_length=1_000)
    startLine: int | None = Field(default=None, ge=1)
    endLine: int | None = Field(default=None, ge=1)
    documentId: UUID | None = None
    createdDocumentClientOperationId: str | None = Field(default=None, max_length=100)
    blockId: UUID | None = None
    createdBlockClientOperationId: str | None = Field(default=None, max_length=100)
    bindingId: UUID | None = None
    candidateId: str | None = Field(default=None, max_length=100)
    documentAnchorCandidateId: str | None = Field(default=None, max_length=100)
    reason: str = Field(min_length=1, max_length=1_000)
    confidence: float | None = Field(default=None, ge=0, le=1)
    bindingRole: Literal["PRIMARY", "SUPPORTING"] = "PRIMARY"
    bindingOrdinal: int = Field(default=1, ge=1, le=16)

    @model_validator(mode="after")
    def valid_anchor_and_targets(self) -> "BindingProposal":
        if (self.startLine is None) != (self.endLine is None):
            raise ValueError("startLine and endLine must appear together")
        if self.startLine is not None and self.endLine is not None:
            if self.endLine < self.startLine:
                raise ValueError("endLine must not be before startLine")
        if self.anchorKind == "FILE":
            if self.symbolKey is not None or self.startLine is not None:
                raise ValueError("FILE binding cannot contain symbol or range")
        elif self.anchorKind == "RANGE":
            if not self.revision or self.startLine is None or self.symbolKey is not None:
                raise ValueError("RANGE binding requires revision and range")
        elif not self.revision or not self.symbolKey:
            raise ValueError("SYMBOL binding requires revision and symbolKey")
        if (self.documentId is None) == (self.createdDocumentClientOperationId is None):
            raise ValueError("binding must target one existing or created document")
        if self.blockId is not None and self.createdBlockClientOperationId is not None:
            raise ValueError("binding cannot target existing and created block")
        if self.bindingRole == "PRIMARY" and self.bindingOrdinal != 1:
            raise ValueError("PRIMARY binding ordinal must be 1")
        if self.bindingRole == "SUPPORTING" and self.bindingOrdinal < 2:
            raise ValueError("SUPPORTING binding ordinal must be at least 2")
        return self


class PlanEvidence(StrictModel):
    clientOperationId: str | None = Field(default=None, max_length=100)
    repositoryId: UUID
    filePath: str = Field(min_length=1, max_length=1_000)
    startLine: int | None = Field(default=None, ge=1)
    endLine: int | None = Field(default=None, ge=1)
    description: str = Field(min_length=1, max_length=1_000)

    @model_validator(mode="after")
    def complete_line_range(self) -> "PlanEvidence":
        if (self.startLine is None) != (self.endLine is None):
            raise ValueError("startLine and endLine must appear together")
        if self.startLine is not None and self.endLine is not None:
            if self.endLine < self.startLine:
                raise ValueError("endLine must not be before startLine")
        return self


class AgentPlan(StrictModel):
    decision: Decision
    summary: str = Field(min_length=1, max_length=300)
    rationale: str = Field(min_length=1, max_length=10_000)
    operations: list[DocumentOperation] = Field(default_factory=list, max_length=50)
    bindingProposals: list[BindingProposal] = Field(default_factory=list, max_length=50)
    evidence: list[PlanEvidence] = Field(default_factory=list, max_length=50)

    @model_validator(mode="after")
    def decision_matches_changes(self) -> "AgentPlan":
        has_changes = bool(self.operations or self.bindingProposals)
        if self.decision == Decision.NO_CHANGE and has_changes:
            raise ValueError("NO_CHANGE cannot contain operations or binding proposals")
        if self.decision == Decision.SUBMIT_REVIEW and not has_changes:
            raise ValueError("SUBMIT_REVIEW requires an operation or binding proposal")
        return self

    def mcp_payload(self, workspace_id: str, client_request_id: str) -> dict[str, Any]:
        payload = self.model_dump(mode="json", exclude={"decision"}, exclude_none=True)
        return {
            "workspaceId": workspace_id,
            "clientRequestId": client_request_id,
            **payload,
        }


class PlanValidationIssue(StrictModel):
    path: str
    code: str
    message: str
