"""复现 DeepSeek 空响应：大输入 vs 小输入对比。

构造与 analyze_batch 完全同构的请求（system prompt + user payload +
response_format=json_object），观察大 inline_sources 是否触发空 content。
"""
import glob
import json
import os
import sys

import httpx

API_KEY = os.environ["DEEPSEEK_API_KEY"]
BASE = os.environ.get("DEEPSEEK_BASE_URL", "https://api.deepseek.com")
MODEL = os.environ.get("DEEPSEEK_MODEL", "deepseek-v4-flash")

SYSTEM = open(
    os.path.join(os.path.dirname(__file__), "..", "..", "app", "providers", "deepseek.py"),
    encoding="utf-8",
).read()
# 取文件中 _SEMANTIC_SYSTEM_PROMPT 的内容（粗略提取首尾即可，关键在结构一致）
START = SYSTEM.find('_SEMANTIC_SYSTEM_PROMPT = """')
END = SYSTEM.find('"""', START + len('_SEMANTIC_SYSTEM_PROMPT = """'))
SYS_PROMPT = SYSTEM[START + len('_SEMANTIC_SYSTEM_PROMPT = """'):END]

OUTPUT_CONTRACT = {
    "overall_responsibility": "string",
    "semantic_groups": [{
        "group_id": "string", "order": 0, "title": "string",
        "target_kind": "SYMBOL", "summary": "string",
        "content_markdown": "string",
        "primary_atom_ids": ["symbol_key"],
        "informed_by_atom_ids": ["symbol_key"],
        "evidence_refs": [{"atom_id": "symbol_key"}],
    }],
    "member_interpretations": [{
        "atom_id": "symbol_key", "responsibility": "string",
        "role": "string", "content_markdown": "string",
    }],
}


def load_py_sources(root: str, budget: int) -> list[dict]:
    """读取目录下 .py 文件直到接近 budget 字符，模拟模块总览内联。"""
    sources, total = [], 0
    for path in sorted(glob.glob(os.path.join(root, "**", "*.py"), recursive=True))[:60]:
        try:
            text = open(path, encoding="utf-8").read()
        except OSError:
            continue
        if total + len(text) > budget:
            continue
        sources.append({
            "symbol_key": f"PYTHON:{path}:demo:FUNCTION",
            "file_path": path, "start_line": 1, "end_line": 500,
            "source": text[:40000],
        })
        total += len(text)
    return sources


def call(sources: list[dict], label: str) -> None:
    user_payload = {
        "instruction": "解释整组代码的完整语义，为每个 atom 生成 responsibility。",
        "output_contract": OUTPUT_CONTRACT,
        "inline_sources": sources,
    }
    body = {
        "model": MODEL,
        "temperature": 0,
        "messages": [
            {"role": "system", "content": SYS_PROMPT},
            {"role": "user", "content": json.dumps(user_payload, ensure_ascii=False)},
        ],
        "response_format": {"type": "json_object"},
    }
    size = len(json.dumps(body, ensure_ascii=False).encode())
    print(f"\n=== {label}: request body {size/1024:.0f} KiB, "
          f"{len(sources)} inline sources ===", flush=True)
    try:
        r = httpx.post(
            f"{BASE}/chat/completions", headers={"Authorization": f"Bearer {API_KEY}"},
            json=body, timeout=180,
        )
    except httpx.TimeoutException:
        print("TIMEOUT", flush=True)
        return
    print(f"HTTP {r.status_code}", flush=True)
    try:
        data = r.json()
    except Exception:
        print(f"body head: {r.text[:300]}", flush=True)
        return
    if r.status_code != 200:
        print(f"error body: {json.dumps(data, ensure_ascii=False)[:500]}", flush=True)
        return
    msg = data["choices"][0]["message"]
    content = msg.get("content") or ""
    usage = data.get("usage", {})
    print(f"finish_reason={data['choices'][0].get('finish_reason')} "
          f"content_len={len(content)} reasoning_len={len(msg.get('reasoning_content') or '')} "
          f"usage={usage}", flush=True)
    if content:
        print(f"content head: {content[:120]!r}", flush=True)


if __name__ == "__main__":
    root = sys.argv[1] if len(sys.argv) > 1 else "agent-service/app"
    big = load_py_sources(root, 40_000)
    call(big, "BIG(40K)")
    small = load_py_sources(root, 3_000)
    call(small, "SMALL(3K)")
