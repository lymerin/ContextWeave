"""Probe: submit agent job directly inside agent-service container."""
import json
import sys
import urllib.request

tok = sys.argv[1]
body = {
    "workspaceId": "a7958db9-210f-4e33-8e9d-b75cd5f9d40b",
    "repositoryId": "4fff7424-eff5-4e22-80a1-09eccf110267",
    "scope": {"type": "PROJECT_INITIALIZATION"},
    "userInstruction": "module docs",
}
req = urllib.request.Request(
    "http://localhost:8092/api/v1/agent-jobs",
    data=json.dumps(body).encode(),
    headers={"Content-Type": "application/json",
             "Authorization": f"Bearer {tok}"},
    method="POST",
)
try:
    resp = urllib.request.urlopen(req, timeout=20)
    print(resp.status, resp.read().decode())
except urllib.error.HTTPError as exc:
    print(exc.code, exc.read().decode())
