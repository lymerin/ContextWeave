from __future__ import annotations

from uuid import UUID

import pytest

from app.planning.binding_candidates import (
    BindingCandidateBuilder,
    BindingPlanValidationError,
)
from app.planning.document_block_plans import (
    DocumentBlockPlanBuilder,
    complete_and_validate_binding_plan,
)
from app.schemas.binding_plans import (
    BindingPlan,
    BindingRole,
    BindingSelection,
    BlockTargetKind,
    CodeAnchorKind,
    CodeCandidate,
    DocumentBlockPlan,
)

REPOSITORY_ID = UUID("22222222-2222-2222-2222-222222222222")


def test_fastapi_atoms_produce_five_responsibility_plans() -> None:
    context = {
        "workspace": {"repositoryId": str(REPOSITORY_ID), "revision": "abc123"},
        "task": {"taskId": "review-unit"},
        "codeFiles": [
            {
                "filePath": "app/main.py",
                "language": "Python",
                "content": """
@app.post("/reviews", response_model=ReviewResponse)
def review(request: ReviewRequest) -> ReviewResponse:
    domain = request.to_domain()
    result = review_document(domain)
    return ReviewResponse.from_domain(result)
""".lstrip(),
            },
            {
                "filePath": "app/schemas.py",
                "language": "Python",
                "content": """
class ReviewRequest(BaseModel):
    def to_domain(self):
        return ReviewContext()

class ReviewBlockRequest(BaseModel):
    text: str

class ReviewResponse(BaseModel):
    @classmethod
    def from_domain(cls, result):
        return cls()
""".lstrip(),
            },
            {
                "filePath": "app/rules.py",
                "language": "Python",
                "content": """
def review_document(context):
    return _meaningful_text(context)

def _meaningful_text(context):
    return True
""".lstrip(),
            },
        ],
    }
    candidates = BindingCandidateBuilder().build_code(context)
    plans = DocumentBlockPlanBuilder().build(candidates)

    assert [item.targetKind for item in plans] == [
        BlockTargetKind.HTTP_ENDPOINT,
        BlockTargetKind.SYMBOL,
        BlockTargetKind.DATA_CONVERSION,
        BlockTargetKind.BUSINESS_RULE,
        BlockTargetKind.RESPONSE_CONSTRUCTION,
    ]
    assert all(item.primaryCandidateIds for item in plans)
    assert len({item.blockKey for item in plans}) == 5


@pytest.mark.parametrize(
    ("selections", "expected_code"),
    [
        ([("missing", "candidate_primary", "PRIMARY", 1)], "UNKNOWN_CANDIDATE_ID"),
        (
            [
                ("block", "candidate_primary", "PRIMARY", 1),
                ("block", "candidate_primary", "SUPPORTING", 2),
            ],
            "DUPLICATE_BINDING",
        ),
        ([], "MISSING_PRIMARY_BINDING"),
        (
            [("block", "candidate_support", "PRIMARY", 1)],
            "PRIMARY_BINDING_LEVEL_MISMATCH",
        ),
        (
            [("block", "candidate_primary", "PRIMARY", 1)],
            "BINDING_COVERAGE_INCOMPLETE",
        ),
    ],
)
def test_block_plan_validator_returns_stable_rejection_codes(
    selections: list[tuple[str, str, str, int]],
    expected_code: str,
) -> None:
    candidates = (
        _candidate("candidate_primary", 1),
        _candidate("candidate_primary_2", 2),
        _candidate("candidate_support", 3),
    )
    plan = DocumentBlockPlan(
        blockKey="block",
        title="接口职责",
        purpose="解释真实入口",
        targetKind=BlockTargetKind.HTTP_ENDPOINT,
        primaryCandidateIds=["candidate_primary", "candidate_primary_2"],
        supportingCandidateIds=["candidate_support"],
        requiredCandidateIds=["candidate_primary", "candidate_support"],
        sortOrder=0,
    )
    binding_plan = BindingPlan(
        selections=[
            BindingSelection(
                blockKey=block_key,
                codeCandidateId=candidate_id,
                role=BindingRole(role),
                ordinal=ordinal,
                reason="真实职责关系",
                confidence=0.9,
            )
            for block_key, candidate_id, role, ordinal in selections
        ]
    )

    with pytest.raises(BindingPlanValidationError) as error:
        complete_and_validate_binding_plan(binding_plan, candidates, (plan,))
    assert expected_code in {item["code"] for item in error.value.issues}


def _candidate(candidate_id: str, line: int) -> CodeCandidate:
    return CodeCandidate(
        candidateId=candidate_id,
        repositoryId=REPOSITORY_ID,
        revision="abc123",
        filePath="app/main.py",
        anchorKind=CodeAnchorKind.SYMBOL,
        symbolKey=f"PYTHON:app/main.py:{candidate_id}:FUNCTION",
        startLine=line,
        endLine=line,
        displayName=candidate_id,
    )
