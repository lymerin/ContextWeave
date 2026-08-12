import sys

import pytest

sys.path.insert(0, "agent-service")

from app.planning.unit_plan_validator import UnitPlanValidationError, UnitPlanValidator
from app.schemas.unit_plans import UnitPlan, UnitPlanItem


def _index(*paths: str) -> dict:
    return {
        "files": [{"filePath": path, "eligible": True} for path in paths],
        "documents": [],
    }


def _unit(
    name: str,
    kind: str,
    primary: list[str],
    supporting: list[str] | None = None,
) -> UnitPlanItem:
    return UnitPlanItem(
        name=name,
        kind=kind,
        summary=name,
        primaryFiles=primary,
        supportingFiles=supporting or [],
        groupingEvidence=["same feature"],
    )


def test_unit_plan_rejects_missing_eligible_files() -> None:
    validator = UnitPlanValidator(max_files_per_unit=10, max_units=10)
    plan = UnitPlan(units=[
        _unit("orders", "BUSINESS_SERVICE", ["src/orders.py"]),
    ])

    with pytest.raises(UnitPlanValidationError) as exc:
        validator.validate(plan, _index("src/orders.py", "src/payments.py"))

    assert any(
        issue.code == "MISSING_ELIGIBLE_FILES" and "src/payments.py" in issue.message
        for issue in exc.value.issues
    )


def test_unit_plan_rejects_duplicate_file_sets_even_with_different_kind() -> None:
    validator = UnitPlanValidator(max_files_per_unit=10, max_units=10)
    plan = UnitPlan(units=[
        _unit("orders service", "BUSINESS_SERVICE", ["src/orders.py"], ["src/repo.py"]),
        _unit("orders integration", "INTEGRATION", ["src/repo.py"], ["src/orders.py"]),
    ])

    with pytest.raises(UnitPlanValidationError) as exc:
        validator.validate(plan, _index("src/orders.py", "src/repo.py"))

    assert any(issue.code == "DUPLICATE_UNIT" for issue in exc.value.issues)
