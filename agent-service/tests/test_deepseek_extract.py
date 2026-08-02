import sys

sys.path.insert(0, "agent-service")
from app.providers.deepseek import _extract_json_object


def test_markdown_fenced() -> None:
    real = '''现在我已从两个结构块中获取了全部源码。以下为完整的语义分析。

---

```json
{
  "overall_responsibility": "定义 Pydantic 模型",
  "semantic_groups": [
    {"group_id": "g1", "order": 1, "title": "模型", "primary_atom_ids": ["a1"], "informed_by_atom_ids": ["a1"]}
  ],
  "member_interpretations": [],
  "execution_flow": []
}
```
'''
    r = _extract_json_object(real)
    assert r["overall_responsibility"] == "定义 Pydantic 模型"
    assert len(r["semantic_groups"]) == 1


def test_plain_json() -> None:
    r = _extract_json_object('{"a": 1, "b": [2, 3]}')
    assert r == {"a": 1, "b": [2, 3]}


def test_no_fence_with_suffix() -> None:
    r = _extract_json_object('分析结果如下\n{"x": "值"}\n以上就是全部')
    assert r == {"x": "值"}


def test_unfenced_no_tag() -> None:
    r = _extract_json_object('```\n{"y": 1}\n```')
    assert r == {"y": 1}


def test_invalid() -> None:
    try:
        _extract_json_object("这不是JSON")
        assert False, "should have raised"
    except ValueError:
        pass


if __name__ == "__main__":
    for fn in (
        test_markdown_fenced,
        test_plain_json,
        test_no_fence_with_suffix,
        test_unfenced_no_tag,
        test_invalid,
    ):
        fn()
        print(f"PASS: {fn.__name__}")
    print("ALL TESTS PASSED")
