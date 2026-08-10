# Batch 6 Report: Phase 3/4/5 Switch Wiring

Date: 2026-08-10
Branch: `main`
Start HEAD: `7df0ac7`
Final HEAD before report commit: `61de556`

## Summary

This batch wired the Phase 3/4/5 skeletons behind default-off switches. Existing hot paths remain unchanged unless the corresponding switch is enabled.

## Completed Wiring

- `AGENT_MCP_SESSION_POOL_ENABLED`: `OfficialMcpClient` can use job-scoped `McpSessionPool`; default off.
- `AGENT_DELEGATION_TOKEN_CACHE_ENABLED` plus `AGENT_DELEGATION_TOKEN_TTL_SECONDS`: delegated MCP calls can reuse exchanged access tokens; default off, TTL capped at 300 seconds.
- `AGENT_BATCH_TOOLS_ENABLED`: `DocumentReader` can use `find_candidates_batch` and `get_structure_batch`; default off.
- `repository.inspect_code_metadata`: `blobSha` is now included in knowledge-core metadata output.
- `DEVCOLLAB_CODE_METADATA_CACHE_ENABLED`: knowledge-core regex metadata parsing can use a content-addressed LRU cache; default off.
- `AGENT_INCREMENTAL_READ_ENABLED`: agent read stage can use `list_files` blobSha metadata to avoid repeated source reads within a `SourceReader`; default off.
- `StagedPipeline.run`: exceptions now cancel producer/stage/worker tasks and rethrow the original exception.
- `AGENT_STAGED_PIPELINE_ENABLED`: fallback AIMD wrapper around model calls; default off.

## Commits

- `bbd41a9` 功能：开关化接入 MCP 会话池
- `4f55b34` 功能：开关化接入 delegation token 缓存
- `7688ef0` 功能：开关化接入文档批量工具
- `63e6690` 修复：code-metadata 返回缺失 blobSha
- `956f5a4` 功能：开关化接入 code-metadata 内容缓存
- `c341914` 功能：开关化接入源码增量读取
- `5d4dc53` 修复：StagedPipeline 异常时取消下游任务
- `71bd317` 功能：开关化接入模型 AIMD 退避
- `61de556` 修复：补齐 code-metadata 测试缓存配置

## Test Output

- `python -m py_compile ...`: passed for touched agent modules.
- Agent targeted tests:
  - session pool + client wiring: `4 passed`
  - delegation token cache + client wiring: `5 passed`
  - document batch tools: `6 passed`
  - incremental source read: `5 passed`
  - staged pipeline/AIMD: `8 passed`
- Knowledge-core targeted cache suite: `23 passed`.
- Knowledge-core full suite: `276 tests run`, `0 failures`, `4 skipped`.
- Agent full suite: collection failed on missing `tree_sitter_java`.
- MCP full suite: `100 tests run`, `2 failures`, both existing trailing-newline assertions.

## Holding Issues

See `tools/perf/NIGHT-HOLDING-ISSUES.md`.

Key items:

- `devcollab.code.read_batch` is not wired because its 200KB truncation contract is not equivalent to current paged `code.read` behavior.
- Full `StagedPipeline` replacement of `JobExecutor` is deferred; only AIMD model fallback is wired.

## Next Batch Suggestions

- Decide `code.read_batch` pagination/range contract before agent source read replacement.
- Specify `JobExecutor` equivalence boundaries before full four-stage pipeline migration.
- Run after-switch deterministic comparisons with all switches individually enabled, then combined.
