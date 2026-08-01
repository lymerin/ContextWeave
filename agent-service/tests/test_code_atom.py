from __future__ import annotations

from app.code_atom import CodeAtomKind, PythonCodeAtomExtractor, java_symbol_to_atom

SOURCE = """
from fastapi import FastAPI
from pydantic import BaseModel

app = FastAPI()

class ReviewRequest(BaseModel):
    title: str

    def to_domain(self):
        return build_review(self.title)

class ReviewService:
    @classmethod
    def create(cls, request: ReviewRequest):
        return request.to_domain()

@app.post("/reviews", response_model=ReviewRequest)
async def create_review(request: ReviewRequest) -> ReviewRequest:
    return ReviewService.create(request)
""".lstrip()


def extract():
    return PythonCodeAtomExtractor().extract(
        SOURCE,
        file_path="app/main.py",
        repository_id="repository",
        revision="commit-sha",
    )


def test_extracts_stable_module_classes_methods_and_fastapi_route() -> None:
    first = extract()
    second = extract()

    assert [item.atom_id for item in first] == [item.atom_id for item in second]
    assert first[0].kind == CodeAtomKind.MODULE
    assert {item.display_name for item in first} >= {
        "ReviewRequest",
        "to_domain",
        "ReviewService",
        "create",
        "create_review",
    }
    route = next(item for item in first if item.kind == CodeAtomKind.HTTP_ROUTE)
    assert (route.route_method, route.route_path, route.response_model) == (
        "POST",
        "/reviews",
        "ReviewRequest",
    )
    assert route.start_line < route.end_line
    assert route.metadata_value("annotations") == "ReviewRequest"
    assert "create" in (route.metadata_value("directCalls") or "")


def test_preserves_parent_relationship_and_pydantic_marker() -> None:
    atoms = extract()
    request = next(item for item in atoms if item.display_name == "ReviewRequest")
    conversion = next(item for item in atoms if item.qualified_name == "ReviewRequest.to_domain")
    service_create = next(item for item in atoms if item.qualified_name == "ReviewService.create")

    assert request.metadata_value("isPydanticModel") == "true"
    assert conversion.parent_atom_id == request.atom_id
    assert service_create.kind == CodeAtomKind.CLASS_METHOD


def test_dynamic_or_ambiguous_routes_remain_plain_functions() -> None:
    source = """
path = "/dynamic"
@app.get(path)
def dynamic(): pass

@app.api_route("/multi", methods=["GET", "POST"])
def multi(): pass
""".lstrip()
    atoms = PythonCodeAtomExtractor().extract(
        source,
        file_path="app/routes.py",
        repository_id="repository",
        revision="revision",
    )
    assert not any(item.kind == CodeAtomKind.HTTP_ROUTE for item in atoms)
    assert {item.display_name for item in atoms if item.kind == CodeAtomKind.FUNCTION} == {
        "dynamic",
        "multi",
    }


def test_syntax_error_returns_no_atoms() -> None:
    assert (
        PythonCodeAtomExtractor().extract(
            "def broken(:",
            file_path="broken.py",
            repository_id="repository",
            revision="revision",
        )
        == ()
    )


def test_java_adapter_reuses_projected_symbol_truth() -> None:
    atom = java_symbol_to_atom(
        {
            "symbolKey": "src/AuthController.java#login()",
            "qualifiedName": "AuthController#login",
            "simpleName": "login",
            "symbolKind": "METHOD",
            "language": "Java",
            "startLine": 10,
            "endLine": 14,
        },
        repository_id="repository",
        revision="revision",
        file_path="src/AuthController.java",
    )
    assert atom is not None
    assert atom.symbol_key == "src/AuthController.java#login()"
    assert (atom.start_line, atom.end_line) == (10, 14)
