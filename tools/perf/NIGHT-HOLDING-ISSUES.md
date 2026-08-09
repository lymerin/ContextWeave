# Night Holding Issues

## 2026-08-10 第五批

1. `agent-service` 全量 `python -m pytest tests/ -x` 在 collection 阶段失败：
   - `tests/test_java_parser.py`
   - `ModuleNotFoundError: No module named 'tree_sitter_java'`
   - 与提示词中说明的既有环境问题一致；本批未安装依赖、未改 parser 路径。

2. `devcollab-mcp-server` 全量 `.\mvnw.cmd -pl devcollab-mcp-server test` 剩余 2 个换行断言失败：
   - `ContextBudgetPolicyTests.lineAndCharacterBudgetsReportRealOmissions`
   - `McpServerProtocolTests.authenticatedMemberCanReadWorkspaceAndCodeThroughOfficialClient`
   - 共同表现：期望内容无末尾换行，实际返回多一个末尾换行。
   - 该失败涉及既有 `code.read`/`ContextBudgetPolicy` 行截取行为；本批约束为只加骨架、不改既有业务行为，因此未修。
