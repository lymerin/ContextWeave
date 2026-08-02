import sys

sys.path.insert(0, "agent-service")
from app.providers.deepseek import _extract_json_object


def test_fenced_with_inner_braces_in_string() -> None:
    # 围栏内 JSON 字符串里包含 { } 和反引号，且前置说明也有 { }
    s = """前置说明：规则集包含 {4条} 静态规则，以下是结果。

```json
{
  "overall_responsibility": "执行评审规则 {见附录}",
  "unresolved_findings": [
    "未提供 MIN_LEN 常量 `等` 的具体值 {范围}",
    "relation_count=0"
  ],
  "member_interpretations": []
}
```"""
    r = _extract_json_object(s)
    assert r["overall_responsibility"] == "执行评审规则 {见附录}"
    assert len(r["unresolved_findings"]) == 2


def test_multiple_braces_before_json() -> None:
    # 前置说明有多个 { }
    s = """流程 {a} 与 {b} 完成。开始分析。
{"x": 1, "y": [2, 3]}
附加说明{结束}"""
    r = _extract_json_object(s)
    assert r == {"x": 1, "y": [2, 3]}


def test_plain() -> None:
    assert _extract_json_object('{"a": 1}') == {"a": 1}


def test_inner_triple_backtick() -> None:
    # 文本里含 ``` 但不构成闭合围栏
    s = '说明\n```json\n{"a": 1, "note": "代码是 ``` 这种"}\n```\n结尾'
    r = _extract_json_object(s)
    assert r["a"] == 1


def test_no_fence() -> None:
    s = '结果如下\n{"k": "v"}\n完毕'
    assert _extract_json_object(s) == {"k": "v"}


if __name__ == "__main__":
    for fn in (
        test_fenced_with_inner_braces_in_string,
        test_multiple_braces_before_json,
        test_plain,
        test_inner_triple_backtick,
        test_no_fence,
    ):
        fn()
        print(f"PASS: {fn.__name__}")
    print("ALL PASSED")
