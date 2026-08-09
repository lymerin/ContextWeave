from conftest import FakeMcpClient, MemoryRunStore, request_payload
from fastapi.testclient import TestClient


def test_health_does_not_require_model_key(client: TestClient) -> None:
    assert client.get("/health").json() == {"status": "UP", "mode": "context-only"}


def test_missing_authorization_is_401(client: TestClient) -> None:
    response = client.post("/api/v1/agent-runs/context", json=request_payload())
    assert response.status_code == 401
    assert response.json()["detail"]["code"] == "AUTHENTICATION_REQUIRED"


def test_empty_selected_paths_is_rejected(client: TestClient) -> None:
    payload = request_payload()
    payload["selectedPaths"] = []
    response = client.post(
        "/api/v1/agent-runs/context",
        json=payload,
        headers={"Authorization": "Bearer secret-token"},
    )
    assert response.status_code == 422


def test_too_many_selected_paths_is_rejected(client: TestClient) -> None:
    response = client.post(
        "/api/v1/agent-runs/context",
        json=request_payload(["a", "b", "c"]),
        headers={"Authorization": "Bearer secret-token"},
    )
    assert response.status_code == 422


def test_ready_run_is_persisted_without_token(
    client: TestClient,
    fake_mcp: FakeMcpClient,
    run_store: MemoryRunStore,
) -> None:
    response = client.post(
        "/api/v1/agent-runs/context",
        json=request_payload(),
        headers={"Authorization": "Bearer secret-token"},
    )
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "CONTEXT_READY"
    assert body["contextBundle"]["documents"][0]["source"] == "BOUND"
    assert "secret-token" not in str(run_store.values)
    assert "Authorization" not in str(run_store.values)
    assert set(run_store.ttls.values()) == {86400}
    assert all(call[2] == "Bearer secret-token" for call in fake_mcp.calls)
    assert (
        client.get(
            f"/api/v1/agent-runs/{body['runId']}",
            headers={"Authorization": "Bearer secret-token"},
        ).json()
        == body
    )


def test_legacy_agent_runs_create_endpoint_is_removed(client: TestClient) -> None:
    response = client.post(
        "/api/v1/agent-runs",
        json=request_payload(),
        headers={"Authorization": "Bearer secret-token"},
    )
    assert response.status_code == 404


def test_get_run_requires_authorization(client: TestClient) -> None:
    response = client.get("/api/v1/agent-runs/11111111-1111-1111-1111-111111111111")
    assert response.status_code == 401
    assert response.json()["detail"]["code"] == "AUTHENTICATION_REQUIRED"


def test_request_body_rejects_token_fields(client: TestClient) -> None:
    payload = request_payload()
    payload["token"] = "must-not-be-accepted"
    response = client.post(
        "/api/v1/agent-runs/context",
        json=payload,
        headers={"Authorization": "Bearer secret-token"},
    )
    assert response.status_code == 422
