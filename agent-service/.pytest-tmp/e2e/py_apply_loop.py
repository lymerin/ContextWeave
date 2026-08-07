"""Python 对照组 review 自动应用循环。

轮询 agent_units：COMPLETED 的 SEMANTIC_ANALYSIS 单元若其 review 尚未
APPLIED，则通过 knowledge-core apply 端点应用。全部 10 批完成即退出。
"""
import json
import subprocess
import sys
import time

JOB = "1c0f53c4-8bff-4f54-903f-d18dea397e79"
WS = "a7958db9-210f-4e33-8e9d-b75cd5f9d40b"
LOGIN = "agent-service/.pytest-tmp/e2e/py_login.json"

PSQL = [
    "docker", "exec", "devcollab-postgres", "psql", "-U", "devcollab",
    "-d", "devcollab", "-t", "-A", "-c",
]


def psql(sql: str) -> list[str]:
    out = subprocess.run(
        PSQL + [sql], capture_output=True, text=True,
        encoding="utf-8", errors="replace",
    ).stdout or ""
    return [line.strip() for line in out.splitlines() if line.strip()]


def fresh_token() -> str:
    out = subprocess.run(
        ["curl.exe", "-s", "-m", "10", "-X", "POST",
         "http://localhost:8080/api/v1/auth/login",
         "-H", "Content-Type: application/json", "-d", f"@{LOGIN}"],
        capture_output=True, text=True,
        encoding="utf-8", errors="replace",
    ).stdout or ""
    return json.loads(out)["accessToken"]


def apply(token: str, req_id: str) -> str:
    out = subprocess.run(
        ["curl.exe", "-s", "-m", "15", "-X", "POST",
         f"http://localhost:8080/api/v1/workspaces/{WS}/document-change-requests/{req_id}/apply",
         "-H", f"Authorization: Bearer {token}"],
        capture_output=True, text=True,
        encoding="utf-8", errors="replace",
    ).stdout or ""
    try:
        return json.loads(out)["request"]["status"]
    except Exception:
        return f"ERR:{out[:120]}"


def main() -> None:
    token = fresh_token()
    refreshed = time.time()
    applied: set[str] = set()
    while True:
        rows = psql(
            "SELECT review_request_id FROM agent_service.agent_units "
            f"WHERE job_id='{JOB}' AND unit_kind='SEMANTIC_ANALYSIS' "
            "AND status='COMPLETED' AND review_request_id IS NOT NULL"
        )
        if not rows:
            remaining = psql(
                f"SELECT COUNT(*) FROM agent_service.agent_units "
                f"WHERE job_id='{JOB}' AND status <> 'COMPLETED'"
            )
            if remaining and remaining[0] == "0":
                print("ALL_DONE")
                return
            time.sleep(30)
            continue
        for req_id in rows:
            if req_id in applied:
                continue
            if time.time() - refreshed > 1200:
                token = fresh_token()
                refreshed = time.time()
            status = apply(token, req_id)
            print(f"{req_id} -> {status}", flush=True)
            applied.add(req_id)
        time.sleep(30)


if __name__ == "__main__":
    main()
