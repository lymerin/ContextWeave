from conftest import FakeMcpClient, MemoryRunStore, request_payload
from fastapi.testclient import TestClient

from app import perf_metrics


def test_health_does_not_require_model_key(client: TestClient) -> None:
    assert client.get("/health").json() == {"status": "UP", "mode": "context-only"}


def test_readiness_exposes_dependency_status(client: TestClient) -> None:
    response = client.get("/diagnostics/readiness")

    assert response.status_code == 200
    body = response.json()
    assert body["status"] in {"UP", "DEGRADED"}
    assert body["checks"]["database"]["status"] == "UP"
    assert body["checks"]["redis"]["status"] == "UP"
    assert set(body["checks"]) == {"database", "redis", "knowledgeCore", "mcp"}
    assert "agent_internal_service_token" not in str(body)


def test_perf_metrics_endpoint_is_machine_readable(client: TestClient) -> None:
    perf_metrics.reset()
    perf_metrics.increment("session_initialize_count")
    perf_metrics.increment("delegation_exchange_count")
    perf_metrics.increment("rate_limit_429_count")
    perf_metrics.increment("retry_count")
    perf_metrics.add_backoff(60)
    perf_metrics.record_mcp_tool("devcollab.code.read", 12.5)
    with perf_metrics.stage("read"):
        pass

    response = client.get("/api/v1/perf/metrics")
    assert response.status_code == 200
    body = response.json()
    assert body["mcp"]["tool_calls"] == 1
    assert body["mcp"]["session_initialize_count"] == 1
    assert body["mcp"]["duration_ms"]["p95"] == 12.5
    assert body["mcp"]["by_tool"]["devcollab.code.read"]["count"] == 1
    assert body["delegation"]["exchange_count"] == 1
    assert body["retry"]["rate_limit_429_count"] == 1
    assert body["retry"]["retry_count"] == 1
    assert body["retry"]["backoff_ms_total"] == 60_000
    assert body["stages"]["read"]["count"] == 1

    reset = client.post("/api/v1/perf/metrics/reset")
    assert reset.status_code == 200
    assert client.get("/api/v1/perf/metrics").json()["mcp"]["tool_calls"] == 0


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
