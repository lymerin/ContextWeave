# 给 Codex 的接线批提示词（第六批——阶段 3/4/5 开关化接线，2026-08-10）

## 运行模式（重要）

你在 `main` 分支，HEAD = `7df0ac7`（含阶段 2 基线 + 阶段 3/4/5 骨架）。本批与第五批不同：**接线会改变既有路径的行为**，因此纪律更严：

- **每个接线点 = 一个开关 + 一个 commit**：先加开关（默认关闭，关闭时行为与现在逐字节一致）→ 接线 → 单元测试 → **开关开启状态跑全量回归** → 提交 → 继续下一个。
- 按任务顺序连续执行，不要停下来等人；每个 commit 提交后立即继续。
- 遇到真正无法自行裁决的问题（跨模块契约、与 IMPLEMENTATION_PLAN.md 冲突）→ 写入 `tools/perf/NIGHT-HOLDING-ISSUES.md`（第六批小节）并继续下一个能做的任务。
- 你有自动批准权限；危险操作（删文件、force push、清数据）不执行，记 HOLDING。
- 每个任务开始前：`git status` 确认干净、在正确分支。

## 背景

阶段 3/4/5 骨架已 review 通过并入 main（`7df0ac7`）：
- `McpSessionPool`（app/runtime/mcp_session_pool.py）：job 级会话复用，未接线
- `DelegationTokenCache`（app/runtime/delegation_token_cache.py）：TTL ≤300s，未接线
- 批量 MCP 工具：`devcollab.document.find_candidates_batch`、`devcollab.document.get_structure_batch` 已注册；`devcollab.code.read_batch` 已注册但**本批不接线**（原因见任务一 1.3）
- `ContentAddressableCache` / `ReverseDependencyIndex`（devcollab-mcp-server/cache/）：未接线
- `StagedPipeline` + `AimdConcurrencyController`（app/runtime/staged_concurrency.py）：未接线，生产代码未引用

**最高原则：行为等价**。开关关闭 = 现状行为逐字节一致（现有测试全绿即证明）；开关开启 = 在确定性回归（temperature=0，同输入同输出）下与关闭态输出一致。任何输出差异都是 bug，必须修到一致才能提交。

**已知骨架缺陷（接线前必须先修）**：`StagedPipeline.run()` 某阶段 worker 抛异常时 `asyncio.gather` 直接抛，但其余阶段 task 不取消、下游哨兵不放置 → worker 泄漏挂起（任务三 3.1 修）。

## 已验证事实（勘察结论，直接采信，勿重新推导）

1. **code-metadata batch 响应不含 blobSha**：`knowledge-core/git/application/CodeMetadataBatchResult.java` 的 `FileMetadata`（L12-26）没有 blobSha 字段；只有 `git/api/GitRepositoryFileResponse.java`（list_files 接口 `/git/repositories/{id}/files`）有。→ **devcollab-mcp-server 的 `inspect_code_metadata` 输出 blobSha 在真实数据流中恒为 null**，任务二 2.1 必须先修。
2. **imports/exportedSymbols 是请求时正则现算**：`knowledge-core/git/application/CodeMetadataInspector.java`（`inspect(GitRepositoryFile)` L41-57）对 `content_text` 跑正则，结果不落库。
3. **worker 每次 `GIT_REPOSITORY_SYNC_REQUESTED` 全量重建**：`GitRepositoryProjectionStore.replaceFiles`/`replaceCodeGraph`（先 DELETE 再全量 INSERT），同步串行完成。
4. **file_classification 不调 MCP**：`app/runtime/file_classification.py` 是纯本地启发式（扩展名表+目录黑名单）。候选查找在 `app/platform_mcp/document_reader.py`：`find_candidates`（L60-89，每文件 1 次 `devcollab.document.find_candidates`）、`locate_documents`（L91-105，逐 path 循环）、`read_structures`（L19-58，逐 documentId 调 `devcollab.document.get_structure`）。
5. **绑定优先已实现**：`binding_reader.read_batch` 已用 `devcollab.binding.list_batch`（L33-53），`job_executor.py` L259-261 先查绑定、L280-283 `resolve_bindings(existing_bindings=bindings_list)` 复用 → 本批不需要新增"绑定优先"逻辑。
6. **"2 路并发"在 worker 调度层**：`app/worker.py` `run()` L148-189 用 `agent_project_unit_concurrency`（默认 2）并发 `_execute(unit)`；`job_executor.execute()` 内部**串行**（`for scope in scopes` L161）。→ 阶段 5 接线点在 worker 调度，不在 execute 内部。
7. **source_reader 每文件 1 次 code.read**：`source_reader.py` `_read_full`（L67-105）调 `devcollab.code.read`，超过 `_MCP_MAX_LINES=400` 用 startLine/endLine 分段续读。
8. **session 每调用新建**：`mcp_client.py` `_invoke_tool`（L118-148）每次调用 `httpx.AsyncClient` + `streamable_http_client` + `ClientSession` + `initialize()`，埋点 `session_initialize_count`、`record_mcp_tool`；异常统一转 `McpClientError`（timeout→MCP_UNAVAILABLE 等）。
9. **delegation 每调用一次 exchange**：`DelegatedMcpClient`（app/runtime/delegated_mcp_client.py）`call_tool` L38-41、`read_code_details` L61-64、`submit_document_change` L115-118 三处调 `delegation_client.exchange`；`exchange` 返回**带 `Bearer ` 前缀**的完整 header 值（delegation_client.py L86）。`delegation_client.exchange` 签名 `(*, delegation_id, job_id)` 与 `DelegationTokenCache.get` 的 `ExchangeFunc` 完全匹配。
10. **agent 文件清单**：`workspace_reader.list_files`（L41-67）调 `devcollab.repository.list_files` 整仓一次，返回元数据 dict（含 blobSha，来自 GitRepositoryFileResponse）——增量读取的 blobSha 来源。

## 任务一：阶段 3 接线（agent-service）

### 1.1 McpSessionPool 接线（开关 `AGENT_MCP_SESSION_POOL_ENABLED`，默认 false）

- `app/config.py` 的 `Settings` 增加 `agent_mcp_session_pool_enabled: bool = False`（pydantic-settings 风格，字段名即 env 名，布尔开关先例：`devcollab_memory_profile_enabled`）。
- `OfficialMcpClient`（app/clients/mcp_client.py）：增加可选构造参数 `session_pool`（McpSessionPool | None）与 `job_id`（str）。`_invoke_tool` 在 `session_pool is not None` 时改用 `session_pool.call_tool(job_id, name, arguments, authorization)`，否则走现有每调用新建 session 路径。
- **异常语义必须完全保留**：`McpSessionPool.call_tool` 抛原始异常（超时/连接错），`_invoke_tool` 外层 `httpx.TimeoutException`/`_contains_timeout`/`McpClientError` 转换逻辑一行不能丢；perf_metrics 埋点（`session_initialize_count`、`record_mcp_tool`）保持。
- 池的生命周期：worker 创建 `DelegatedMcpClient` 的同级（job 级）创建；job 收尾处 `McpSessionPool.aclose()`。
- 单元测试（mock 层）：开关 on 时同一 job 二次 `call_tool` 只 `initialize()` 一次；异常后重建；开关 off 走原路径（现有测试不动，全绿即回归证据）。

### 1.2 DelegationTokenCache 接线（开关 `AGENT_DELEGATION_TOKEN_CACHE_ENABLED`，默认 false）

- `Settings` 增加 `agent_delegation_token_cache_enabled: bool = False` 与 `agent_delegation_token_ttl_seconds: int = Field(300, ge=1, le=300)`。
- `DelegatedMcpClient`：增加可选构造参数 `token_cache`。`call_tool`/`read_code_details`/`submit_document_change` 三处 `self._delegation_client.exchange(...)` 在 cache 存在时改为 `token_cache.get(delegation_id=..., job_id=..., exchange=self._delegation_client.exchange)`。
- 缓存值 = exchange 返回值（已含 `Bearer ` 前缀），不要二次加前缀。
- 单元测试：TTL 内三次调用 exchange 仅 1 次（mock delegation client 计数）；过期后重新 exchange；开关 off 原路径。

### 1.3 批量工具接线（开关 `AGENT_BATCH_TOOLS_ENABLED`，默认 false）——**只接语义等价的**

- `devcollab.document.find_candidates_batch` → 接入 `document_reader.py`：`locate_documents`/`find_candidates` 的逐 path 循环改为一次批量调用；批量结果与逐个调用**逐字节一致**（骨架实现即逐 path 复用单文件逻辑）。
- `devcollab.document.get_structure_batch` → 接入 `document_reader.py` `read_structures`：逐 documentId 循环改为一次批量；结构与逐个调用逐字节一致。
- **`devcollab.code.read_batch` 本批不接线**：其 200KB 整文件截断语义与 `code.read` 现有 startLine/endLine 分页语义（400 行上限，`_MCP_MAX_LINES`）不等价，硬接破坏确定性回归。需要契约增强（行级参数）后才可接，在 HOLDING 中记为规格方（Claude）定案、第七批实施。
- 单元测试：批量结果 vs 逐个调用结果一致性（逐字节）；空输入、>100 项输入抛错语义与契约一致。
- 注意：`source_reader.py` 的 `read_batch` 是本地循环包装（逐 path `_read_full`），不是 MCP 批量工具，本批不动它。

### 1.4 验收：三个开关各自 off/on 两态；off 时现有测试全绿；on 时新增测试绿 + 现有测试零回归；每子项一个 commit。

## 任务二：阶段 4 接线（blobSha 修复 + 内容缓存 + 增量读取）

### 2.1 【先决修复】code-metadata 响应补 blobSha（单独 commit：`修复：code-metadata 返回缺失 blobSha`）

- `knowledge-core`：`CodeMetadataBatchResult.FileMetadata`（`git/application/CodeMetadataBatchResult.java`）增加 `blobSha` 字段（String，可空）；`CodeMetadataInspector.inspect` 从 `file.blobSha()` 填充。
- 更新 knowledge-core 相关契约测试：code-metadata 响应断言含 blobSha。
- devcollab-mcp-server 的 `HttpKnowledgeCoreGateway` 映射已有 blobSha 字段（`CodeMetadataPayload` 第 2 位），上游返回后自动生效——只需验证，不改映射代码（若字段名对不上则改映射，属例外）。
- 验证：mcp-server 契约测试 `codeMetadataJsonDeserializesBlobSha` 通过（已有，fixture 与真实响应形状现在一致了）；`./mvnw -pl devcollab-mcp-server test` 全量回归。
- 验收标准：真实数据流下 `inspect_code_metadata` 的 blobSha 不再恒为 null。

### 2.2 正则解析结果缓存（开关 `DEVCOLLAB_CODE_METADATA_CACHE_ENABLED`，默认 false，knowledge-core）

- 接线位置：`knowledge-core` 的 `inspectCodeMetadata` 路径（`GitKnowledgeApplicationService`/`CodeMetadataInspector` 层）。
- 在 knowledge-core 新建与 devcollab-mcp-server `cache/ContentAddressableCache.java` **同构**的缓存类（key = blobSha + 提取器版本常量；LRU；命中/未命中计数；绝不允许返回过期元数据——key 必须含版本）。devcollab-mcp-server 的骨架类保留不动（未来再合并）。
- 开关注入：knowledge-core 已有配置风格（application.yml `devcollab.*`），加 `devcollab.code-metadata-cache-enabled` 与容量配置（默认 1024）。
- 命中/未命中计数日志（WARN 级别仅在失效风暴时，正常命中 INFO 或 debug 计数，具体以既有日志风格为准）。
- 单元测试：同 blobSha+版本二次命中；版本变化不命中；LRU 淘汰；缓存 value 与现算结果一致（属性逐一对比）。

### 2.3 agent 增量读取（开关 `AGENT_INCREMENTAL_READ_ENABLED`，默认 false，job 内内存级）

- 数据来源：`workspace_reader.list_files` 返回的文件元数据（已验证含 blobSha，见事实 10）。
- 接线位置：`job_executor` 读取阶段（L115-132）与 `source_reader._read_full` 之间——以 `{path: blobSha}` 记录 job 内已读文件；重复 path 且 blobSha 未变 → 跳过 `code.read`（用 job 内已读内容）；blobSha 变化或首次 → 正常读取并更新记录。
- 埋点：`perf_metrics` 增加跳过文件数、实际拉取数两个计数（供实验批）。
- 单元测试：同 job 第二次处理跳过未变文件；blobSha 变化重新拉取；开关 off 原路径。
- 注：跨 job 持久化缓存（磁盘）不属本批——收益与实验设计（每轮新 job 时仅 job 内多 unit 受益）以埋点数据为准，跨 job 持久化记 HOLDING 建议。

### 2.4 验收：每子项一个 commit；2.2/2.3 的命中率/跳过数埋点就位（实验批用）。

## 任务三：阶段 5 接线（分阶段自适应并发，风险最高）

### 3.1 先修骨架缺陷（单独 commit，不接开关）

- `StagedPipeline.run()`：worker 异常时取消全部阶段 task（try/finally + `task.cancel()`），gather 收集取消结果后抛原始异常；下游哨兵不因异常悬挂。
- 回归测试：某阶段抛错 → `run()` 抛原始异常且无泄漏 task（`asyncio.all_tasks()` 断言除当前外为空）。

### 3.2 接线（开关 `AGENT_STAGED_PIPELINE_ENABLED`，默认 false）

- 接线位置：`app/worker.py` `run()` L148-189 的 unit 调度循环（`agent_project_unit_concurrency` 并发 `_execute(unit)`）——开关 on 时改用 `StagedPipeline` 驱动 unit 管线。
- **拆 `job_executor.execute` 为四阶段纯函数（机械移动代码，禁止改任何逻辑）**：
  - read：现有 `stage("read")` 段（L115-132）
  - parse：现有 `stage("parse")` 段（L141-147 + `build_execution_scopes`）
  - model：现有模型会话段（L161-198 的 `_run_semantic_session`/`_run_batch_session` 循环，含 `shape_context`/`freeze_context`）
  - submit：文档定位 + 组装 + 提交段（L249-309 的 `locate_documents`/`read_structures`/`read_batch`/`resolve_targets`/`resolve_bindings`/`assemble_and_validate`/`submit`，含 `stage("submit")` 埋点与 scope_results 收集）
- 拆解后：`execute` 的开关 off 路径 = 现有代码路径（零改动）；开关 on 路径 = 四阶段 func 交给 `StagedPipeline.run(units)`，结果按 unit 索引收集，后续既有收尾（失败计数、result 写入）逻辑复用同一份收集结果。
- 模型阶段挂 `AimdConcurrencyController`，初始并发 = `agent_project_unit_concurrency`（现值 2）；429（`MODEL_RATE_LIMITED`）→ 乘性减半 + 退避；稳定 → 加性增；与 deepseek.py 既有 429 重试（RETRYABLE_ERRORS）衔接（重试在阶段 func 内部，AIMD 管并发，不重复实现退避）。
- 提交阶段语义不得破坏：同文档串行 + `create_batch_units` ordinal 唯一约束（跨 unit 不同文档可并行；同 unit 内顺序由 stage func 保证）。
- **降级路径**：若拆 `execute` 时发现状态纠缠、无法在开关保护下做到逐字节等价，允许降级为"仅模型调用 AIMD 化"（deepseek.py 调用处挂并发控制），并在 HOLDING 说明理由与拆解障碍，由规格方定案。
- 单元/集成测试（fake 模型）：开关 on 时 429 减半、稳定恢复、队列满不丢任务；结果与 off 态逐字节一致。

### 3.3 验收：开关 off 全量回归绿（证明拆解零行为变化）；开关 on 集成测试绿 + 全量回归绿；每子项一个 commit。

## 任务四：汇总

1. 更新 `IMPLEMENTATION_PLAN.md`"批次记录"：第六批清单、开关名、提交 hash、测试数。
2. 写 `tools/perf/BATCH6-REPORT.md`：每任务完成情况、off/on 两态回归结果摘录、HOLDING 问题（含 read_batch 契约增强建议）、下一批建议（第七批：read_batch 契约增强 + 接线、跨 job 增量持久化、开关全 on 跑基线对比实验）。
3. 提交（中文信息，`功能：`/`修复：` 前缀）。

## 约束（全程）

- 提交信息中文惯例；不提交杂物（.data、日志、缓存、.env、node_modules）
- 开关默认一律 false；与开关无关的改动一律禁止（任何"顺手优化"都算偏离）
- 跑测试：agent-service `python -m pytest tests/ -x`（collection 缺 tree_sitter_java 是既有环境问题，记录不修）；mcp-server 用仓库根 `./mvnw -pl devcollab-mcp-server test`（既有 2 个末尾换行断言失败是既有问题，不修不改）；knowledge-core 用仓库根 `./mvnw -pl knowledge-core test`
- 冒烟允许但限量：开关全 on 后可跑 1–3 轮真实 job 验证全链路不炸、输出与 off 态一致；**不跑 20 轮长实验**
- 若任务测试环境有问题：记录 HOLDING 并继续，不无限重试
- 遇到规格歧义：读 IMPLEMENTATION_PLAN.md 对应章节；确实无法裁决的写 HOLDING，不要停
