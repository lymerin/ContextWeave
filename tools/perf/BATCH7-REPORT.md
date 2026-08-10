# Batch 7 Report: read_batch Contract, Agent Wiring, Switch Comparison Script

Date: 2026-08-10
Branch: `main`
Start HEAD: `8d812d5`
Report base HEAD: `b42749f`

## Summary

本批完成 `devcollab.code.read_batch` 契约增强、agent 侧默认关闭的批量源码读取接线，以及 off / 单开关 / 全 on 的冒烟对比脚本。默认配置不改变现有读取路径。

## Completed

1. `devcollab.code.read_batch` 契约增强
   - 移除跨文件总 200KB 截断语义。
   - 每个文件使用与 `devcollab.code.read` 相同的首段行分页语义。
   - file item 对齐 `code.read` 字段：`path`、`commitHash`、`language`、`sizeBytes`、`startLine`、`endLine`、`totalLines`、`content`、`truncated`、`omittedLineCount`、`omittedCharacterCount`。
   - 顶层字段为 `workspaceId`、`repositoryId`、`revision`、`truncated`、`files`。

2. Agent 批量源码读取接线
   - 新增 `AGENT_CODE_READ_BATCH_ENABLED`，默认 `false`。
   - `SourceReader` 在开关开启时按 100 paths 分块调用 `devcollab.code.read_batch`。
   - 大文件首段之后继续沿用 `devcollab.code.read` 分页续读，保证与单文件读取字节级一致。
   - 保留本地 `max_chars` 截断和 blobSha job 内缓存逻辑。
   - 新增计数：`code_read_batch_calls`、`code_read_batch_continuations`。

3. 实验脚本
   - 新增 `tools/perf/run-switch-comparison.py`。
   - 参数：`--repo-url`、`--switches off|single|all`、`--rounds-per-config`（默认 1，超过 1 直接拒绝）。
   - 复用 `run-baseline.py` 的 API client、仓库准备、job 触发、轮次采集和统计函数。
   - 输出 `tools/perf/switch-comparison-<ts>.json`，包含配置环境、perf metrics、MCP by-tool 计数和 job result summary。

## Commits

- `3852811` 功能：read_batch 契约增强为逐文件首段读取（对齐 code.read 行语义）
- `7482a1c` 功能：开关化接入 code.read_batch 源码批量读取
- `b42749f` 功能：开关对比实验脚本（off vs 单开关 vs 全 on）

## Tests

- `.\mvnw.cmd -pl devcollab-mcp-server "-Dtest=CodeReadApplicationServiceTests,McpToolSchemasTests" test`
  - 10 tests, 0 failures.
- `python -m py_compile app\config.py app\platform_mcp\source_reader.py app\worker.py app\clients\mcp_client.py app\contracts\validator.py`
  - passed.
- `python -m pytest tests\test_source_reader_incremental_read.py tests\test_official_mcp_client_session_pool.py tests\test_mcp_contracts.py -q`
  - 26 passed, 1 warning.
- `python -m py_compile tools\perf\run-switch-comparison.py`
  - passed.
- `python -m ruff check ..\tools\perf\run-switch-comparison.py`
  - All checks passed.
- `python -m ruff format --check ..\tools\perf\run-switch-comparison.py`
  - already formatted.
- `python tools\perf\run-switch-comparison.py --help`
  - passed.
- `python -m pytest tests/ -x`
  - collected 123 items / 1 error.
  - Existing environment issue: `ModuleNotFoundError: No module named 'tree_sitter_java'`.
- `.\mvnw.cmd -pl devcollab-mcp-server test`
  - 103 tests run, 2 failures, 0 errors.
  - Existing trailing-newline assertions:
    - `ContextBudgetPolicyTests.lineAndCharacterBudgetsReportRealOmissions`
    - `McpServerProtocolTests.authenticatedMemberCanReadWorkspaceAndCodeThroughOfficialClient`

## Smoke Status

脚本级冒烟已完成：编译、lint、格式检查和 `--help` 参数面验证通过。真实 off/on job 对比未在本批执行，因为该脚本需要公开 GitHub repo、运行中的服务，以及每个配置生效前的服务重启或外部环境切换；本批按提示词不跑长实验。

## Holding Issues

见 `tools/perf/NIGHT-HOLDING-ISSUES.md` 第七批。核心 deferred 项：

- 完整四阶段 `StagedPipeline` 替换 `JobExecutor` 的 read/parse/model/submit 控制流。
- 跨 job 持久化增量缓存。

## Next Batch Suggestions

- 用公开 GitHub repo 跑 `run-switch-comparison.py --switches all --rounds-per-config 1`，并在每个配置前通过 `--restart-command` 或外部流程确保服务读取对应环境变量。
- 基于 switch comparison 的数据决定是否继续推进完整四阶段 pipeline。
- 若 job 内增量读取收益显著，再规格化跨 job 持久化缓存的一致性和失效策略。
