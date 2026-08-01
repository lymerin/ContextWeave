from __future__ import annotations

from typing import Literal

from pydantic import Field, field_validator, model_validator

from app.schemas.plans import StrictModel


class SupportingCandidateSelection(StrictModel):
    candidateId: str = Field(min_length=8, max_length=100)
    reason: str = Field(min_length=1, max_length=1_000)
    confidence: float = Field(ge=0, le=1)


class DocumentBlockContent(StrictModel):
    blockKey: str = Field(min_length=1, max_length=100)
    status: Literal["CONTENT", "INSUFFICIENT_EVIDENCE"]
    content: str | None = Field(default=None, max_length=20_000)
    supportingSelections: list[SupportingCandidateSelection] = Field(
        default_factory=list,
        max_length=15,
    )

    @field_validator("content")
    @classmethod
    def content_is_body_only(cls, value: str | None) -> str | None:
        if value is None:
            return None
        normalized = value.strip()
        if normalized.startswith("#"):
            raise ValueError("content must not contain the program-owned Block heading")
        return normalized

    @model_validator(mode="after")
    def status_matches_content(self) -> DocumentBlockContent:
        if self.status == "CONTENT" and not self.content:
            raise ValueError("CONTENT requires final document prose")
        if self.status == "INSUFFICIENT_EVIDENCE" and (
            self.content is not None or self.supportingSelections
        ):
            raise ValueError("INSUFFICIENT_EVIDENCE cannot contain content or selections")
        candidate_ids = [item.candidateId for item in self.supportingSelections]
        if len(candidate_ids) != len(set(candidate_ids)):
            raise ValueError("supporting candidate selections must be unique")
        return self


class DocumentBlockContentPlan(StrictModel):
    blocks: list[DocumentBlockContent] = Field(min_length=1, max_length=50)

    @model_validator(mode="after")
    def block_keys_are_unique(self) -> DocumentBlockContentPlan:
        keys = [item.blockKey for item in self.blocks]
        if len(keys) != len(set(keys)):
            raise ValueError("blockKey must appear exactly once")
        return self
