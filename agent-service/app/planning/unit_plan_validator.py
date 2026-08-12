from __future__ import annotations

from dataclasses import dataclass
from typing import Any
from uuid import UUID

from app.schemas.unit_plans import UnitPlan


@dataclass(frozen=True)
class UnitPlanValidationIssue:
    path: str
    code: str
    message: str


class UnitPlanValidationError(ValueError):
    def __init__(self, issues: list[UnitPlanValidationIssue]) -> None:
        summary = ", ".join(f"{item.code}@{item.path}" for item in issues[:8])
        super().__init__(f"DeepSeek UnitPlan failed boundary validation: {summary}")
        self.issues = issues

    def safe_details(self) -> list[dict[str, str]]:
        return [
            {"path": item.path, "code": item.code, "message": item.message}
            for item in self.issues
        ]


class UnitPlanValidator:
    def __init__(self, *, max_files_per_unit: int, max_units: int) -> None:
        self._max_files_per_unit = max_files_per_unit
        self._max_units = max_units

    @property
    def max_files_per_unit(self) -> int:
        return self._max_files_per_unit

    @property
    def max_units(self) -> int:
        return self._max_units

    def validate(self, plan: UnitPlan, project_index: dict[str, Any]) -> UnitPlan:
        available_paths = {
            str(item["filePath"])
            for item in project_index.get("files", [])
            if item.get("eligible", True)
        }
        available_documents = {
            UUID(str(item["documentId"]))
            for item in project_index.get("documents", [])
            if item.get("documentId")
        }
        issues: list[UnitPlanValidationIssue] = []
        if len(plan.units) > self._max_units:
            issues.append(
                UnitPlanValidationIssue(
                    "units", "UNIT_LIMIT_EXCEEDED", "Unit count exceeds the safety limit"
                )
            )

        identities: set[tuple[str, ...]] = set()
        single_file_units = 0
        generic_units = 0
        covered_paths: set[str] = set()
        for index, unit in enumerate(plan.units):
            path = f"units[{index}]"
            all_files = unit.primaryFiles + unit.supportingFiles
            covered_paths.update(all_files)
            if len(all_files) > self._max_files_per_unit:
                issues.append(
                    UnitPlanValidationIssue(
                        f"{path}.primaryFiles",
                        "UNIT_FILE_LIMIT_EXCEEDED",
                        "Unit file count exceeds the context budget",
                    )
                )
            for file_path in all_files:
                if file_path not in available_paths:
                    issues.append(
                        UnitPlanValidationIssue(
                            f"{path}.files",
                            "UNKNOWN_FILE",
                            "Unit references a file outside the current ProjectIndex",
                        )
                    )
            for document_id in unit.relatedDocumentIds:
                if document_id not in available_documents:
                    issues.append(
                        UnitPlanValidationIssue(
                            f"{path}.relatedDocumentIds",
                            "UNKNOWN_DOCUMENT",
                            "Unit references a document outside the current ProjectIndex",
                        )
                    )
            identity = tuple(sorted(all_files))
            if identity in identities:
                issues.append(
                    UnitPlanValidationIssue(
                        path, "DUPLICATE_UNIT", "Unit duplicates an existing unit"
                    )
                )
            identities.add(identity)
            single_file_units += len(all_files) == 1
            generic_units += unit.kind == "GENERIC_MODULE"

        if len(plan.units) >= 4 and single_file_units == len(plan.units):
            issues.append(
                UnitPlanValidationIssue(
                    "units",
                    "SINGLE_FILE_DEGENERATION",
                    "The plan degenerates into one-file units",
                )
            )
        if len(plan.units) >= 3 and generic_units == len(plan.units):
            issues.append(
                UnitPlanValidationIssue(
                    "units",
                    "GENERIC_DEGENERATION",
                    "The plan degenerates into generic modules",
                )
            )
        missing_paths = available_paths - covered_paths
        if missing_paths:
            issues.append(
                UnitPlanValidationIssue(
                    "units",
                    "MISSING_ELIGIBLE_FILES",
                    "Unit plan omits eligible files: "
                    + ", ".join(sorted(missing_paths)[:8]),
                )
            )
        if issues:
            raise UnitPlanValidationError(issues)
        return plan
