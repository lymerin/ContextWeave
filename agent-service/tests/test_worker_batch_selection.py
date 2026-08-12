import sys

sys.path.insert(0, "agent-service")

from app.schemas.document_planner.skeleton import SkeletonSlot, SlotType
from app.worker import _selected_paths_for_semantic_batch


def test_flow_batch_uses_skeleton_file_paths_not_only_slot_files() -> None:
    context = {
        "files": [
            {"filePath": "src/entry.py"},
            {"filePath": "src/helper.py"},
        ]
    }
    slot_plan = {
        "batchType": "FLOW",
        "filePaths": ["src/entry.py", "src/helper.py"],
    }
    slots = (
        SkeletonSlot(
            slot_id="module:overview",
            slot_type=SlotType.OVERVIEW,
            title="Overview",
            primary_symbol_key=None,
        ),
        SkeletonSlot(
            slot_id="flow:entry",
            slot_type=SlotType.FLOW,
            title="Flow",
            primary_symbol_key="PYTHON:src/entry.py:run:FUNCTION",
            file_path="src/entry.py",
        ),
    )

    selected = _selected_paths_for_semantic_batch(context, slots, slot_plan)

    assert selected == ["src/entry.py", "src/helper.py"]


def test_symbol_batch_stays_narrow_to_slot_files() -> None:
    context = {
        "files": [
            {"filePath": "src/entry.py"},
            {"filePath": "src/helper.py"},
        ]
    }
    slot_plan = {
        "batchType": "SYMBOL",
        "filePaths": ["src/entry.py", "src/helper.py"],
    }
    slots = (
        SkeletonSlot(
            slot_id="symbol:helper",
            slot_type=SlotType.SYMBOL,
            title="helper",
            primary_symbol_key="PYTHON:src/helper.py:helper:FUNCTION",
            file_path="src/helper.py",
        ),
    )

    selected = _selected_paths_for_semantic_batch(context, slots, slot_plan)

    assert selected == ["src/helper.py"]
