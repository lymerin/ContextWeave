# 给 Codex 的第七批提示词（read_batch 契约增强 + 批量读取接线 + 实验脚本，2026-08-10）

## 运行模式（与前两批相同）

你在 `main` 分支，HEAD = `8d812d5`（batch 6 之后规格方已提交两个修复：`eb57426` list_files blobSha 透传、`8d812d5` MCP 工具计时剔除会话初始化）。本批与第六批同为**接线批**，纪律一致：

- **每个任务 = 一个 commit**：按任务顺序连续执行，不要停；每 commit 提交后继续。
- 遇到真正无法自行裁决的问题 → 写入 `tools/perf/NIGHT-HOLDING-ISSUES.md`（第七批小节）并继续。
- 每个任务开始前：`git status` 确认干净（已知脏文件除外，见约束）、在 `main`。
- 你有自动批准权限；危险操作（删文件、force push、清数据）不执行，记 HOLDING。

## 背景

batch 6 已接线：session pool、token cache、document 批量工具、code-metadata blobSha + 内容缓存、增量读取（blobSha 链路刚修通）、AIMD 降级。全部默认关闭，开关 off 时与 7df0ac7 行为逐字节一致（已全量验证：knowledge-core 276/0/4、agent 定向 20 passed、mcp-server 100/2 既有失败）。

本批目标：**接上最后一个批量工具 `devcollab.code.read_batch`（源码读取是 MCP 往返次数的大头）**，并准备开关对比实验脚本（阶段 6 铺路）。HOLDING 中记录的两个大项（StagedPipeline 完整接线、跨 job 增量持久化）**本批不做**，理由见任务四。

## 已验证事实（勘察结论，直接采信，勿重新推导）

1. **code.read 单次调用契约**：参数 `workspaceId, repositoryId, path, startLine?, endLine?, includeExistingBindings`；内部 `budgetPolicy.limit(source.content(), startLine, endLine)` 截断（默认 maxCodeLines=400）；响应字段：workspaceId, repositoryId, path, commitHash, language, sizeBytes, startLine, endLine, totalLines, content, truncated, omittedLineCount, omittedCharacterCount。
2. **source_reader._read_full 分页语义**（agent-service/app/platform_mcp/source_reader.py）：首读不带行参数 → 拿 totalLines/endLine/truncated；`while last_end > 0 and last_end < total_lines` 续读，每段 `startLine=last_end+1, endLine=min(last_end+400, total_lines)`，拼接 content；**续读决策只依赖 `endLine < totalLines`，不依赖 truncated 标志**。language 取首段。
3. **read_batch 现契约（未接线）**（devcollab-mcp-server/.../application/CodeReadApplicationService.java L74-131）：参数 `workspaceId, repositoryId, paths(≤100), revision`；每文件读 knowledge-core **完整内容**后做**跨文件 200KB 字节预算截断**（`fitWithinRemainingBytes`/`MAX_BATCH_BYTES`），无续读机制。**该字节预算语义与 agent 逐文件完整读取 + 本地按字符预算截断不等价**——这是 batch 6 未接线的原因。
4. **knowledge-core 内容源**：`coreGateway.readRepositorySource(...)` 返回**完整文件内容**（不截断）；400 行截断只发生在 mcp-server 的 `budgetPolicy.limit`。所以服务端做"每文件 400 行首段"批量读取与逐文件 code.read **共享同一条截断代码路径和同一内容源**。
5. **agent 侧本地预算截断**：source_reader.read_batch 在拿到完整内容后按 `self._max_chars`（字符数）逐文件扣减截断。**本批不动这段逻辑**——批量路径只替换"获取完整内容"的方式，截断仍在 agent 本地做，保证逐字节一致。
6. **revision 校验双层**：agent 所有调用走 delegated 模式，mcp-server 安全层按 `identity.delegationRevision()` 校验每次调用（含续读的 code.read）；read_batch 的显式 `revision` 参数是第二重保险。agent 传 `selection.revision`。
7. **实验工具链**：tools/perf/run-baseline.py 是 batch 2 的基线脚本（驱动 job + 采集 perf_metrics：tool_calls p50/p95、session_init_count、delegation_exchange_count、mcp_p95_ms，输出 JSON）。实验 repo **必须公开 HTTPS GitHub URL**（本地 fixture 被 DEVCOLLAB_GIT_ALLOWED_HOSTS 拒绝，见 baseline-small.json 的失败记录）。
8. **埋点就绪**：session_initialize_count、record_mcp_tool（按工具名）、metadata cache hit/miss、增量读取跳过数均已就位；`AGENT_CODE_READ_BATCH_ENABLED` 是新增开关，需要新埋点（见任务二 2.4）。

## 任务一：read_batch 契约增强（mcp-server 服务端）

### 1.1 契约变更（规格方定义，照此实施，禁止自行扩展）

`devcollab.code.read_batch` 新契约：

- **输入**（不变）：`workspaceId, repositoryId, paths`（≤100，超出抛 INVALID_ARGUMENT 同现逻辑）, `revision`。
- **行为**：对每个 path（保持传入顺序）：
  1. 路径校验 + 读取 `readRepositorySource` + 不可读抛错 + revision 检查——**与现有 readBatch 逐行相同**；
  2. 内容截断改为 `budgetPolicy.limit(source.content(), null, null)`（**与 code.read 无行参数调用走同一条代码路径**，同样的 400 行上限）；
  3. **删除跨文件字节预算**：不再调用 `fitWithinRemainingBytes`，不再使用 `MAX_BATCH_BYTES`/`remainingBytes`/`batchTruncated` 聚合逻辑（响应顶层 `truncated` 保留为 boolean 聚合标志 = 任一文件 truncated）。
- **响应**：顶层 `workspaceId, repositoryId, revision, truncated`；`files` 为 `Map<path, file>`，每文件字段与 code.read 单次响应对齐：`path, commitHash, language, sizeBytes, startLine, endLine, totalLines, content, truncated, omittedLineCount, omittedCharacterCount`。
- **错误语义不变**：任一文件路径非法/不可读 → 整批抛同一错误码（与逐文件调用一致）。

### 1.2 验证

- 更新所有受影响的 mcp-server 测试：现有 read_batch 测试中任何依赖字节预算语义的断言改写为新契约（每文件 400 行首段 + 行元数据字段）。新增用例：小文件完整返回（truncated=false）、大文件（>400 行）返回首段 + totalLines 完整 + truncated=true + endLine=400、恰好 400 行文件的边界语义（以 `endLine`/`totalLines` 实测为准，断言与 code.read 单次调用一致——**同一 limit 路径，结果必须逐字段相同**）、空文件、paths>100 抛错。
- 全量：`./mvnw -pl devcollab-mcp-server test`（既有 2 个 trailing-newline 失败是既有问题，不修）。

### 1.3 提交

commit 消息：`功能：read_batch 契约增强为逐文件首段读取（对齐 code.read 行语义）`。

## 任务二：source_reader 接线 read_batch（agent-service）

### 2.1 新开关

`Settings` 增加 `agent_code_read_batch_enabled: bool = False`。**独立开关**（不复用 `AGENT_BATCH_TOOLS_ENABLED`）：实验需要归因"document 批量"与"code 批量读取"各自的收益。

### 2.2 接线

`SourceReader`（app/platform_mcp/source_reader.py）增加构造参数 `code_read_batch_enabled: bool = False`；`worker.py` 两处 `SourceReader(delegated)` 传入 `self._settings.agent_code_read_batch_enabled`。

`read_batch`（agent 侧）开关 on 时：

1. 把 `selection.paths` 按 **100 个/片**分片（服务端上限），每片一次 `devcollab.code.read_batch` 调用，参数 `workspaceId/repositoryId/paths/revision=selection.revision`；
2. 对每文件：语言/字段取批量响应；`content` = 首段；若 `endLine < totalLines` 则**复用既有 `_read_full` 的续读循环逻辑**（`startLine=endLine+1, endLine=min(endLine+400, totalLines)`，逐段 code.read 拼接）；
3. 拼接结果进入 `_read_cached` 缓存（key 逻辑不变，缓存完整拼接内容）；
4. 续读时 `content` 追加、`last_end` 更新——**把现有 `_read_full` 的分页循环提炼为可复用方法**（例如 `_read_remaining(workspace_id, repository_id, path, base_args, start_from, total_lines)`），逐文件路径与批量路径共用，保证两路径拼接逻辑逐字节一致。

**等价性论证（必须成立才提交）**：批量首段与逐文件首读走同一 `budgetPolicy.limit(content, null, null)` + 同一内容源 → 首段逐字节相同；续读是同一段拼接代码 + 同一行边界 → 全文件逐字节相同；`truncated` 标志批量路径 = 首段 truncated OR 续读段 truncated，逐文件路径 = 各段 truncated 聚合，首段两者同源 → 等价。agent 本地字符预算截断（2.4 之外）两路径共用，不动。

### 2.3 边界用例（单元测试必须覆盖）

- 小文件（<400 行）：批量返回即完整，零续读；
- 大文件（>400 行）：首段 + 续读拼接 == 逐文件路径完整内容（**逐字节断言**，用同一 fixture 跑两条路径对比）；
- 恰好 400 行：以 `endLine < totalLines` 为准决定续读，行为与逐文件路径一致；
- 空文件：不续读；
- paths > 100：分片后多次批量调用，结果与逐文件路径一致；
- 开关 off：现有路径逐字节不变（现有测试全绿即回归证据）；
- 批量调用抛错（不可读文件）：整批失败，错误类型与逐文件路径一致。

### 2.4 埋点

`perf_metrics` 增加两个 counter：`code_read_batch_calls`（批量调用次数）、`code_read_batch_continuations`（续读次数）。供阶段 6 实验量化往返次数下降。

### 2.5 提交

commit 消息：`功能：开关化接入 code.read_batch 源码批量读取`。

## 任务三：开关对比实验脚本（tools/perf）

### 3.1 目标

阶段 6 需要"同一 job，off vs 各开关单独 on vs 全 on"的**确定性对比**。本任务只把脚本做出来 + 冒烟，**不跑长实验**（每配置最多 1 轮）。

### 3.2 脚本

`tools/perf/run-switch-comparison.py`（先读 `run-baseline.py` 复用其 job 触发/指标采集模式，不要另起炉灶）：

- 参数：`--repo-url`（公开 HTTPS GitHub URL）、`--switches off|single|all`、`--rounds-per-config`（默认 1，冒烟用，禁止默认 20）；
- 执行序列：同一 job 定义分别在 开关全 off / 每个开关单独 on / 全 on 下执行；
- 输出：`tools/perf/switch-comparison-<ts>.json`，每配置记录 perf_metrics 汇总（tool_calls p50/p95、session_init_count、delegation_exchange_count、mcp_p95_ms、code_read_batch_calls、code_read_batch_continuations、metadata cache hit/miss、增量跳过数——以实际埋点名为准）+ job 输出结果摘要（供 diff）；
- 冒烟验收：off 与各 on 配置的 job 输出一致（确定性回归），指标 JSON 字段完整。

### 3.3 提交

commit 消息：`功能：开关对比实验脚本（off vs 单开关 vs 全 on）`。

## 任务四：汇总报告

1. 更新 `IMPLEMENTATION_PLAN.md`"批次记录"：第七批清单、commit hash、测试数。
2. 写 `tools/perf/BATCH7-REPORT.md`：两任务完成情况、off/on 回归结果摘录、冒烟结果、HOLDING 问题。
3. `tools/perf/NIGHT-HOLDING-ISSUES.md` 第七批小节，必须记录：
   - **StagedPipeline 完整接线**：规格方评估——read_batch 落地后 read 阶段 MCP 往返大幅下降，管线化主要收益（read/parse 并行）被削弱；job_executor 状态交织拆解风险高。等价边界规格 + 收益模型在阶段 6 实验数据出来后由规格方定案，本批不施工。
   - **跨 job 增量持久化**：等 job 内跳过数实验数据，本批不做。
4. 提交（中文信息，`功能：`/`修复：` 前缀）。

## 约束（全程）

- 提交信息中文惯例；不提交杂物（.data、日志、缓存、.env、node_modules、`tools/perf/baseline-small.json` 的本地修改、`.claude/`、`PROMPT-batch*.md` 未跟踪文件——**git add 时只加本批文件**）。
- 开关默认一律 false；与开关无关的改动一律禁止。
- 跑测试：agent-service `python -m pytest tests/ -x`（collection 缺 tree_sitter_java 是既有环境问题，记录不修）；mcp-server `./mvnw -pl devcollab-mcp-server test`（既有 2 个 trailing-newline 失败不修不改）；knowledge-core 本批不应被触碰。
- 冒烟允许但限量：任务三每配置 1 轮；**不跑 20 轮长实验**。
- 若任务测试环境有问题：记录 HOLDING 并继续，不无限重试。
- 遇到规格歧义：任务一契约以本文件 1.1 为准（规格方定义，禁止自行扩展）；其余先读 IMPLEMENTATION_PLAN.md，确实无法裁决的写 HOLDING，不要停。
