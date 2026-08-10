from uuid import UUID

import pytest

from app.platform_mcp.document_reader import DocumentReader


WORKSPACE_ID = UUID("11111111-1111-1111-1111-111111111111")
REPOSITORY_ID = UUID("22222222-2222-2222-2222-222222222222")
DOC_A = UUID("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
DOC_B = UUID("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
BLOCK_A = UUID("33333333-3333-3333-3333-333333333333")


class FakeMcpClient:
    def __init__(self, responses):
        self.responses = responses
        self.calls = []

    async def call_tool(self, name, arguments, authorization):
        self.calls.append((name, arguments, authorization))
        response = self.responses[name]
        return response(arguments) if callable(response) else response


@pytest.mark.asyncio
async def test_batch_find_candidates_matches_individual_result_shape():
    client = FakeMcpClient({
        "devcollab.document.find_candidates_batch": {
            "candidatesByPath": {
                "src/A.java": [candidate(DOC_A, "Doc A")],
                "src/B.java": [candidate(DOC_A, "Doc A"), candidate(DOC_B, "Doc B")],
            }
        }
    })
    reader = DocumentReader(client, batch_tools_enabled=True)

    result = await reader.locate_documents(
        WORKSPACE_ID, REPOSITORY_ID, ["src/A.java", "src/B.java"]
    )

    assert [item.document_id for item in result] == [DOC_A, DOC_B]
    assert [name for name, _, _ in client.calls] == [
        "devcollab.document.find_candidates_batch"
    ]


@pytest.mark.asyncio
async def test_batch_read_structures_matches_individual_result_shape():
    client = FakeMcpClient({
        "devcollab.document.get_structure_batch": {
            "structures": [
                structure(DOC_A, "Doc A"),
                structure(DOC_B, "Doc B"),
            ]
        }
    })
    reader = DocumentReader(client, batch_tools_enabled=True)

    result = await reader.read_structures(WORKSPACE_ID, [DOC_A, DOC_B])

    assert [item.document_id for item in result] == [DOC_A, DOC_B]
    assert result[0].blocks[0].version == 0
    assert [name for name, _, _ in client.calls] == [
        "devcollab.document.get_structure_batch"
    ]


@pytest.mark.asyncio
async def test_batch_document_reader_empty_inputs_do_not_call_mcp():
    client = FakeMcpClient({})
    reader = DocumentReader(client, batch_tools_enabled=True)

    assert await reader.locate_documents(WORKSPACE_ID, REPOSITORY_ID, []) == []
    assert await reader.read_structures(WORKSPACE_ID, []) == []
    assert client.calls == []


@pytest.mark.asyncio
async def test_batch_tools_disabled_keeps_individual_calls():
    client = FakeMcpClient({
        "devcollab.document.find_candidates": {
            "candidates": [candidate(DOC_A, "Doc A")]
        },
        "devcollab.document.get_structure": structure(DOC_A, "Doc A"),
    })
    reader = DocumentReader(client, batch_tools_enabled=False)

    await reader.locate_documents(WORKSPACE_ID, REPOSITORY_ID, ["src/A.java"])
    await reader.read_structures(WORKSPACE_ID, [DOC_A])

    assert [name for name, _, _ in client.calls] == [
        "devcollab.document.find_candidates",
        "devcollab.document.get_structure",
    ]


@pytest.mark.asyncio
async def test_batch_document_reader_rejects_inputs_over_tool_limit():
    client = FakeMcpClient({})
    reader = DocumentReader(client, batch_tools_enabled=True)
    many_docs = [UUID(int=index + 1) for index in range(101)]
    many_paths = [f"src/File{index}.java" for index in range(101)]

    with pytest.raises(ValueError, match="documentIds"):
        await reader.read_structures(WORKSPACE_ID, many_docs)
    with pytest.raises(ValueError, match="paths"):
        await reader.locate_documents(WORKSPACE_ID, REPOSITORY_ID, many_paths)
    assert client.calls == []


def candidate(document_id, title):
    return {
        "documentId": str(document_id),
        "title": title,
        "existingBindingCount": 1,
        "matchedBlockIds": [str(BLOCK_A)],
    }


def structure(document_id, title):
    return {
        "documentId": str(document_id),
        "title": title,
        "documentType": "DESIGN",
        "blocks": [
            {
                "blockId": str(BLOCK_A),
                "blockType": "PARAGRAPH",
                "sortOrder": 0,
                "version": 0,
                "plainText": "hello",
                "content": None,
            }
        ],
    }
