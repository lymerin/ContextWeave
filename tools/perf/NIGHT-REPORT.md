# Night Report: 2026-08-10 Phase 3/4/5 Skeleton

## Summary

- Base branch: `fix/perf-baseline`
- Start HEAD: `ca5b3b7`
- Final merged HEAD before report commit: `77cc7d5`
- Completed branches: `fix/perf-phase3`, `fix/perf-phase4`, `fix/perf-phase5`
- Scope: skeleton only; no tuning, no long experiments, no wiring into existing hot paths.

## Completed Work

### Phase 3 P0

- Added `McpSessionPool` with job-scoped reuse of `httpx.AsyncClient`, `streamable_http_client`, and `ClientSession`.
- Added `DelegationTokenCache` with delegation-id keying, TTL capped at 300 seconds, and async-safe miss coalescing.
- Added MCP batch tools: `devcollab.code.read_batch`, `devcollab.document.get_structure_batch`, `devcollab.document.find_candidates_batch`.
- Existing single-file tools remain in place.

Commits: `f64f153`, `4953044`, `2d9ab4b`

### Phase 4 P1

- Added `blobSha` to `repository.inspect_code_metadata` output.
- Added `ContentAddressableCache` keyed by `blobSha + parserVersion`, with LRU capacity and hit/miss stats.
- Added `ReverseDependencyIndex` for export-symbol to dependent-file lookup and incremental updates.
- Cache/index are not wired into parsing paths.

Commits: `36ad1a0`, `a83973f`, `9d8de5d`

### Phase 5 P2

- Added `StagedPipeline` with read/parse/model/submit queues.
- Added AIMD model concurrency controller with multiplicative decrease on rate limit/latency and additive recovery on stable calls.
- Existing `job_executor.py` 2-way concurrency path remains unchanged.

Commit: `0f139df`

## Files Changed

- `agent-service/app/runtime/mcp_session_pool.py`
- `agent-service/app/runtime/delegation_token_cache.py`
- `agent-service/app/runtime/staged_concurrency.py`
- `agent-service/tests/test_mcp_session_pool.py`
- `agent-service/tests/test_delegation_token_cache.py`
- `agent-service/tests/test_staged_concurrency.py`
- `contracts/mcp/code_read_batch_input.json`
- `contracts/mcp/code_read_batch_output.json`
- `contracts/mcp/document_get_structure_batch_input.json`
- `contracts/mcp/document_get_structure_batch_output.json`
- `contracts/mcp/document_find_candidates_batch_input.json`
- `contracts/mcp/document_find_candidates_batch_output.json`
- `contracts/mcp/repository_inspect_code_metadata_output.json`
- `devcollab-mcp-server/src/main/java/com/devcollab/mcp/application/*`
- `devcollab-mcp-server/src/main/java/com/devcollab/mcp/cache/*`
- `devcollab-mcp-server/src/main/java/com/devcollab/mcp/capability/tool/*BatchToolContributor.java`
- `devcollab-mcp-server/src/main/java/com/devcollab/mcp/client/*`
- `devcollab-mcp-server/src/test/java/com/devcollab/mcp/**/*`

## Test Output

- `python -m py_compile app\runtime\mcp_session_pool.py app\runtime\delegation_token_cache.py app\runtime\staged_concurrency.py`: passed
- `python -m pytest tests\test_mcp_session_pool.py tests\test_delegation_token_cache.py tests\test_staged_concurrency.py -q`: `8 passed`
- `.\mvnw.cmd -pl devcollab-mcp-server compile`: `BUILD SUCCESS`
- MCP affected suite: `57 passed`
- MCP full suite: `100 tests run`, `2 failures`; see `NIGHT-HOLDING-ISSUES.md`
- Agent full pytest: collection failed on missing `tree_sitter_java`; see `NIGHT-HOLDING-ISSUES.md`

## Acceptance Criteria

- Existing behavior preserved: no existing agent, parser, or hot-path concurrency code was rewired.
- New abstractions exist independently and default inactive.
- Batch MCP tool contracts and schema registration are present.
- `blobSha` is added without removing or changing existing metadata fields.
- Cache and dependency index structures have focused unit coverage.
- Staged concurrency skeleton has rate-limit, recovery, and queue backpressure tests.

## Remaining Mismatches

- Full MCP test suite has the pre-existing trailing-newline assertion mismatch in `ContextBudgetPolicy`/protocol coverage.
- Full agent pytest still needs `tree_sitter_java` available in the active Python environment.

## Next Batch

- Decide whether to fix the trailing newline behavior or update the affected assertions as an explicit behavior decision.
- Install/repair `tree_sitter_java` in the test environment before claiming full agent regression.
- Review phase 3/4/5 skeleton APIs, then wire each behind default-off flags in narrow follow-up commits.
