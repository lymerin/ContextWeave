from conftest import FakeMcpClient

from app.config import Settings
from app.graph.workflow import ContextWorkflow


def initial_state(paths: list[str]) -> dict[str, object]:
    return {
        "run_id": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
        "workspace_id": "11111111-1111-1111-1111-111111111111",
        "repository_id": "22222222-2222-2222-2222-222222222222",
        "selected_paths": paths,
        "user_instruction": None,
        "authorization": "Bearer transient",
        "tool_call_count": 0,
        "code_chars_used": 0,
        "trace_events": [],
        "errors": [],
    }


async def test_fixed_graph_runs_from_start_to_context_bundle(
    settings: Settings,
) -> None:
    fake = FakeMcpClient()
    result = await ContextWorkflow(fake, settings).graph.ainvoke(
        initial_state(["src/Example.java"])
    )
    assert result["context_bundle"]["runId"].startswith("aaaaaaaa")
    assert result["tool_call_count"] == 4
    assert [call[0] for call in fake.calls] == [
        "devcollab.workspace.get_context",
        "devcollab.code.read",
        "devcollab.binding.list",
        "devcollab.document.get_structure",
    ]


async def test_bound_files_do_not_trigger_candidate_search(
    settings: Settings,
) -> None:
    fake = FakeMcpClient(bound=True)
    await ContextWorkflow(fake, settings).graph.ainvoke(initial_state(["src/Example.java"]))
    assert "devcollab.document.find_candidates" not in [call[0] for call in fake.calls]


async def test_unbound_file_triggers_candidate_search(
    settings: Settings,
) -> None:
    fake = FakeMcpClient(bound=False)
    result = await ContextWorkflow(fake, settings).graph.ainvoke(
        initial_state(["src/Example.java"])
    )
    assert "devcollab.document.find_candidates" in [call[0] for call in fake.calls]
    assert result["context_bundle"]["documents"][0]["source"] == "CANDIDATE"


async def test_all_selected_files_are_read(settings: Settings) -> None:
    fake = FakeMcpClient(bound=True)
    await ContextWorkflow(fake, settings).graph.ainvoke(initial_state(["a.java", "b.java"]))
    code_calls = [call for call in fake.calls if call[0] == "devcollab.code.read"]
    assert [call[1]["path"] for call in code_calls] == ["a.java", "b.java"]


async def test_default_budget_covers_maximum_unbound_selected_files(
    settings: Settings,
) -> None:
    fake = FakeMcpClient(bound=False)
    paths = [f"src/Example{index}.java" for index in range(6)]

    result = await ContextWorkflow(fake, settings).graph.ainvoke(initial_state(paths))

    assert result["tool_call_count"] == 20
    assert result["tool_call_count"] <= settings.agent_max_tool_calls


async def test_duplicate_document_ids_are_read_once(settings: Settings) -> None:
    fake = FakeMcpClient(bound=True)
    await ContextWorkflow(fake, settings).graph.ainvoke(initial_state(["a.java", "b.java"]))
    structure_calls = [call for call in fake.calls if call[0] == "devcollab.document.get_structure"]
    assert len(structure_calls) == 1


async def test_code_budget_truncates_explicitly(settings: Settings) -> None:
    fake = FakeMcpClient(code_content="x" * 80)
    result = await ContextWorkflow(fake, settings).graph.ainvoke(
        initial_state(["large.java", "also-selected.java"])
    )
    bundle = result["context_bundle"]
    assert len(bundle["codeFiles"][0]["content"]) == 40
    assert bundle["codeFiles"][0]["truncated"] is True
    assert bundle["codeFiles"][1]["content"] == ""
    assert bundle["codeFiles"][1]["truncated"] is True
    assert bundle["budget"]["truncatedFiles"] == [
        "large.java",
        "also-selected.java",
    ]
    code_calls = [call for call in fake.calls if call[0] == "devcollab.code.read"]
    assert [call[1]["path"] for call in code_calls] == [
        "large.java",
        "also-selected.java",
    ]


async def test_write_tool_is_never_called(settings: Settings) -> None:
    fake = FakeMcpClient()
    await ContextWorkflow(fake, settings).graph.ainvoke(initial_state(["src/Example.java"]))
    assert "devcollab.review.submit_document_change" not in [call[0] for call in fake.calls]


async def test_deepseek_is_not_part_of_workflow(settings: Settings) -> None:
    fake = FakeMcpClient()
    await ContextWorkflow(fake, settings).graph.ainvoke(initial_state(["src/Example.java"]))
    assert all("deepseek" not in str(call).lower() for call in fake.calls)
