from enum import StrEnum
from typing import Literal
from uuid import UUID

from pydantic import Field, model_validator

from app.schemas.plans import StrictModel


class CodeAnchorKind(StrEnum):
    FILE = "FILE"
    RANGE = "RANGE"
    SYMBOL = "SYMBOL"


class BindingRole(StrEnum):
    PRIMARY = "PRIMARY"
    SUPPORTING = "SUPPORTING"


class BlockTargetKind(StrEnum):
    MODULE_OVERVIEW = "MODULE_OVERVIEW"
    HTTP_ENDPOINT = "HTTP_ENDPOINT"
    SYMBOL = "SYMBOL"
    DATA_CONVERSION = "DATA_CONVERSION"
    BUSINESS_RULE = "BUSINESS_RULE"
    RESPONSE_CONSTRUCTION = "RESPONSE_CONSTRUCTION"


class CodeCandidate(StrictModel):
    candidateId: str = Field(min_length=8, max_length=100)
    repositoryId: UUID
    revision: str = Field(min_length=1, max_length=255)
    filePath: str = Field(min_length=1, max_length=1_000)
    anchorKind: CodeAnchorKind
    symbolKey: str | None = Field(default=None, max_length=1_000)
    startLine: int | None = Field(default=None, ge=1)
    endLine: int | None = Field(default=None, ge=1)
    language: str | None = Field(default=None, max_length=100)
    displayName: str = Field(min_length=1, max_length=300)
    contentPreview: str = Field(default="", max_length=600)
    contentHash: str | None = Field(default=None, min_length=64, max_length=64)
    atomId: str | None = Field(default=None, max_length=100)
    atomKind: str | None = Field(default=None, max_length=50)
    qualifiedName: str | None = Field(default=None, max_length=500)
    signature: str | None = Field(default=None, max_length=1_000)
    parentAtomId: str | None = Field(default=None, max_length=100)
    routeMethod: str | None = Field(default=None, max_length=20)
    routePath: str | None = Field(default=None, max_length=1_000)
    responseModel: str | None = Field(default=None, max_length=500)
    directCalls: list[str] = Field(default_factory=list, max_length=100)
    annotations: list[str] = Field(default_factory=list, max_length=100)
    schemaModel: bool = False

    @model_validator(mode="after")
    def valid_anchor(self) -> "CodeCandidate":
        if (self.startLine is None) != (self.endLine is None):
            raise ValueError("startLine and endLine must appear together")
        if self.startLine is not None and self.endLine is not None:
            if self.endLine < self.startLine:
                raise ValueError("endLine must not be before startLine")
        if self.anchorKind == CodeAnchorKind.FILE:
            if self.symbolKey is not None or self.startLine is not None:
                raise ValueError("FILE candidate cannot contain symbol or range")
        elif self.anchorKind == CodeAnchorKind.RANGE:
            if self.symbolKey is not None or self.startLine is None:
                raise ValueError("RANGE candidate requires only a complete range")
        elif not self.symbolKey:
            raise ValueError("SYMBOL candidate requires symbolKey")
        return self


class DocumentAnchorCandidate(StrictModel):
    candidateId: str = Field(min_length=8, max_length=100)
    documentId: UUID | None = None
    createdDocumentClientOperationId: str | None = Field(default=None, max_length=100)
    blockId: UUID | None = None
    createdBlockClientOperationId: str | None = Field(default=None, max_length=100)
    documentTitle: str = Field(min_length=1, max_length=200)
    blockLabel: str | None = Field(default=None, max_length=200)
    contentPreview: str = Field(default="", max_length=600)
    contentSchemaVersion: int | None = Field(default=None, ge=1)
    sortOrder: int | None = Field(default=None, ge=0)

    @model_validator(mode="after")
    def valid_target(self) -> "DocumentAnchorCandidate":
        if (self.documentId is None) == (self.createdDocumentClientOperationId is None):
            raise ValueError("candidate must target one existing or created document")
        if self.blockId is not None and self.createdBlockClientOperationId is not None:
            raise ValueError("candidate cannot target existing and created block")
        return self


class BindingSelection(StrictModel):
    blockKey: str = Field(min_length=1, max_length=100)
    codeCandidateId: str = Field(min_length=8, max_length=100)
    role: BindingRole
    ordinal: int = Field(ge=1, le=16)
    reason: str = Field(min_length=1, max_length=1_000)
    confidence: float = Field(ge=0, le=1)


class BindingPlan(StrictModel):
    selections: list[BindingSelection] = Field(default_factory=list, max_length=50)


class DocumentBlockPlan(StrictModel):
    blockKey: str = Field(min_length=1, max_length=100)
    title: str = Field(min_length=1, max_length=200)
    purpose: str = Field(min_length=1, max_length=1_000)
    targetKind: BlockTargetKind
    primaryCandidateIds: list[str] = Field(min_length=1, max_length=16)
    supportingCandidateIds: list[str] = Field(default_factory=list, max_length=15)
    requiredCandidateIds: list[str] = Field(default_factory=list, max_length=16)
    allowedClaims: list[str] = Field(default_factory=list, max_length=20)
    forbiddenClaims: list[str] = Field(default_factory=list, max_length=20)
    sortOrder: int = Field(ge=0)

    @model_validator(mode="after")
    def valid_candidates(self) -> "DocumentBlockPlan":
        candidates = [*self.primaryCandidateIds, *self.supportingCandidateIds]
        if len(candidates) != len(set(candidates)):
            raise ValueError("block plan candidates must be unique")
        if len(candidates) > 16:
            raise ValueError("each block plan can expose at most 16 candidates")
        if not set(self.requiredCandidateIds).issubset(candidates):
            raise ValueError("required candidates must be part of the block plan")
        return self


BindingCandidateKind = Literal["EXISTING_BLOCK", "CREATED_BLOCK", "DOCUMENT"]
