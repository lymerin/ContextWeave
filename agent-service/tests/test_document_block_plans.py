from __future__ import annotations

from pathlib import Path
from uuid import UUID

import pytest

from app.planning.binding_candidates import (
    BindingCandidateBuilder,
    BindingPlanValidationError,
)
from app.planning.document_block_plans import (
    DocumentBlockPlanBuilder,
    complete_and_validate_binding_plan,
    validate_document_operations,
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
from app.schemas.plans import AgentPlan

REPOSITORY_ID = UUID("22222222-2222-2222-2222-222222222222")
REPOSITORY_ROOT = Path(__file__).resolve().parents[2]


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


def test_empty_router_path_uses_a_stable_root_path_title() -> None:
    context = {
        "workspace": {"repositoryId": str(REPOSITORY_ID), "revision": "abc123"},
        "task": {"taskId": "root-route"},
        "codeFiles": [
            {
                "filePath": "app/api/jobs.py",
                "language": "Python",
                "content": """
@router.post(\"\", response_model=JobResponse)
def create_job(request: JobRequest) -> JobResponse:
    return create_job_result(request)

class JobRequest(BaseModel):
    name: str

class JobResponse(BaseModel):
    id: str

def create_job_result(request):
    return JobResponse(id=request.name)
""".lstrip(),
            }
        ],
    }
    candidates = BindingCandidateBuilder().build_code(context)
    plans = DocumentBlockPlanBuilder().build(candidates)

    endpoint = next(
        item for item in plans if item.targetKind == BlockTargetKind.HTTP_ENDPOINT
    )
    assert endpoint.title == "接口职责：POST /"
    assert not endpoint.title.endswith(" ")


def test_real_review_service_fixture_does_not_mix_health_route_with_review_blocks() -> None:
    context = _real_review_service_context()
    candidates = BindingCandidateBuilder().build_code(context)
    plans = DocumentBlockPlanBuilder().build(candidates)
    by_id = {item.candidateId: item for item in candidates}

    assert [item.targetKind for item in plans] == [
        BlockTargetKind.HTTP_ENDPOINT,
        BlockTargetKind.SYMBOL,
        BlockTargetKind.DATA_CONVERSION,
        BlockTargetKind.BUSINESS_RULE,
        BlockTargetKind.RESPONSE_CONSTRUCTION,
    ]
    assert len(plans) == 5
    assert by_id[plans[0].primaryCandidateIds[0]].displayName == "review"
    assert "review_document" in {
        by_id[candidate_id].displayName
        for candidate_id in plans[0].supportingCandidateIds
    }
    assert by_id[plans[3].primaryCandidateIds[0]].displayName == "review_document"
    assert "health" not in {
        by_id[candidate_id].displayName
        for plan in plans
        for candidate_id in [
            *plan.primaryCandidateIds,
            *plan.supportingCandidateIds,
            *plan.requiredCandidateIds,
        ]
    }


def test_document_operations_preserve_block_identity_title_and_order() -> None:
    block_plans = (
        _block_plan("block_endpoint", "接口职责", 0),
        _block_plan("block_rule", "业务规则", 1),
    )
    repaired = _document_plan(
        [
            ("block_endpoint", "接口职责", 2),
            ("block_rule", "业务规则", 3),
        ]
    )

    validate_document_operations(repaired, block_plans)

    reordered = _document_plan(
        [
            ("block_rule", "业务规则", 2),
            ("block_endpoint", "接口职责", 3),
        ]
    )
    with pytest.raises(BindingPlanValidationError) as order_error:
        validate_document_operations(reordered, block_plans)
    assert {item["code"] for item in order_error.value.issues} == {
        "DOCUMENT_BLOCK_ORDER_MISMATCH"
    }

    renamed = _document_plan(
        [
            ("block_endpoint", "新的接口设计", 2),
            ("block_rule", "业务规则", 3),
        ]
    )
    with pytest.raises(BindingPlanValidationError) as heading_error:
        validate_document_operations(renamed, block_plans)
    assert {item["code"] for item in heading_error.value.issues} == {
        "DOCUMENT_BLOCK_HEADING_MISMATCH"
    }


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


def _real_review_service_context() -> dict[str, object]:
    relative_paths = (
        "agent-review-service/app/main.py",
        "agent-review-service/app/schemas.py",
        "agent-review-service/app/domain.py",
        "agent-review-service/app/rules.py",
    )
    return {
        "workspace": {
            "repositoryId": str(REPOSITORY_ID),
            "revision": "fixture-revision",
        },
        "task": {"taskId": "real-review-service-fixture"},
        "codeFiles": [
            {
                "filePath": path,
                "language": "Python",
                "content": (REPOSITORY_ROOT / path).read_text(encoding="utf-8"),
            }
            for path in relative_paths
        ],
    }


def _block_plan(block_key: str, title: str, sort_order: int) -> DocumentBlockPlan:
    return DocumentBlockPlan(
        blockKey=block_key,
        title=title,
        purpose=f"解释{title}的真实职责",
        targetKind=(
            BlockTargetKind.HTTP_ENDPOINT
            if sort_order == 0
            else BlockTargetKind.BUSINESS_RULE
        ),
        primaryCandidateIds=[f"candidate_{sort_order}"],
        supportingCandidateIds=[],
        requiredCandidateIds=[f"candidate_{sort_order}"],
        sortOrder=sort_order,
    )


def _document_plan(blocks: list[tuple[str, str, int]]) -> AgentPlan:
    operations: list[dict[str, object]] = [
        {
            "clientOperationId": "create_document",
            "sequenceNumber": 1,
            "operationType": "CREATE_DOCUMENT",
            "proposedDocumentTitle": "评审服务说明",
            "proposedDocumentType": "BACKEND",
        }
    ]
    operations.extend(
        {
            "clientOperationId": block_key,
            "sequenceNumber": sequence_number,
            "operationType": "ADD_BLOCK",
            "createdDocumentClientOperationId": "create_document",
            "proposedBlockType": "PARAGRAPH",
            "proposedPlainText": f"## {title}\n\n这里是来自真实代码证据的中文正式说明。",
            "proposedContentFormat": "MARKDOWN",
        }
        for block_key, title, sequence_number in blocks
    )
    return AgentPlan.model_validate(
        {
            "decision": "SUBMIT_REVIEW",
            "summary": "生成评审服务说明",
            "rationale": "根据程序生成的职责块编写正式文档",
            "operations": operations,
            "bindingProposals": [],
            "evidence": [],
        }
    )
