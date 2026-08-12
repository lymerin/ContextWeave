# 给 Codex 的夜间连续施工提示词（第五批——阶段 3/4/5 连续骨架，2026-08-10 凌晨）

## 运行模式（重要）

你现在处于**夜间无人值守连续施工模式**：
- 本提示词包含多个连续任务（阶段 3 → 阶段 4 → 阶段 5 的骨架）。**按顺序一个接一个执行，不要停下来等人**。
- 每完成一个任务（或任务中的每个 commit）：提交（中文信息，`功能：`/`修复：` 前缀），然后**立即继续下一个任务**，不要等用户回复。
- 除非遇到**真正无法自行决定的问题**（需要改跨模块契约、或与 IMPLEMENTATION_PLAN.md 规格冲突且无法自行裁决），才停下来把问题写进工作区 `tools/perf/NIGHT-HOLDING-ISSUES.md` 并继续下一个能做任务。**默认你能自行决定所有实现细节，按计划规格执行。**
- 你有自动批准权限（approval_policy=on-failure），无需请求批准即可执行常规操作；涉及危险操作（删除文件、force push、清空数据）不执行，记录到 HOLDING 文件。
- 每个任务开始前：`git status` 确认工作区干净、在正确分支。

## 背景

你在 `fix/perf-baseline` 分支，HEAD = `ca5b3b7`（含 access token 续期修复）。阶段 2 埋点+脚本已完成并 review 通过，基线实验数据正在补全（无需等待）。本批目标：**把阶段 3/4/5 的代码骨架全部搭出来并提交**——骨架 = 契约 + 数据流 + 接口签名完整、可编译可测试的最小实现，**不做参数调优、不跑长实验**（实验统一在骨架完成后由用户安排）。

**最高原则：只加不重构**。现有业务行为一律不得改变（读取/解析/并发/会话现状是基线实验的对照物）。所有新增代码必须与既有实现并存，切换开关默认关闭，开关默认值保持现有行为。

## 任务一：阶段 3 P0 骨架（连接复用 + 批量工具）

分支：`fix/perf-phase3`（从 ca5b3b7 派生）。

1. **会话复用抽象**（agent-service，新增 `app/runtime/mcp_session_pool.py`）：
   - 定义 `McpSessionPool`：按 job_id 复用 httpx.AsyncClient + streamable_http_client + ClientSession；`initialize()` 每 job 只一次；异常/超时后重建。
   - **只新增抽象 + 单元测试（mock 层验证：二次调用不再 initialize）**。**不要改** `mcp_client.py` 现有调用路径——接线留给用户 review 后单独开关。
2. **delegation token 缓存**（agent-service，新增 `app/runtime/delegation_token_cache.py`）：
   - `DelegationTokenCache`：key=delegation_id，TTL ≤5min，线程安全，缓存 miss 时回源 exchange。
   - 单元测试：TTL 内不重复 exchange；过期后重新 exchange。**不改** delegated_mcp_client.py 现有路径。
3. **批量工具契约**（devcollab-mcp-server，Java）：
   - `devcollab.code.read_batch`：入参 paths+revision，返回 path→{content, language, totalLines, truncated}，总字节上限 200KB 截断并标记 truncated。
   - `devcollab.document.get_structure_batch`：入参 documentIds，返回结构数组（保留 includeBlockContent 语义）。
   - `devcollab.document.find_candidates_batch`：入参 paths，返回 path→candidates。
   - 实现 + 工具 schema 注册 + 单元测试（含截断边界、空输入、超长输入）。**现有单文件工具保留不动**。
4. **验收**：三个模块各自 `python -m py_compile`/mvnw compile 通过；新增测试全绿；现有测试零回归。
5. 提交：每子项一个 commit，最后合并回 fix/perf-baseline。

## 任务二：阶段 4 P1 骨架（内容寻址增量缓存）

分支：`fix/perf-phase4`（从 fix/perf-baseline 派生）。

1. **blobSha 字段**：worker `GitRepositoryFileProjection` 确认已存 blob_sha（阶段 0 已确认存在）；devcollab-mcp-server `repository.inspect_code_metadata` 返回中**新增 blobSha 字段**（只加字段，不改既有字段语义）。
2. **缓存骨架**（devcollab-mcp-server Java，新增 `cache/ContentAddressableCache`）：
   - key = blob_sha + 解析器版本号；进程内 LRU（容量可配置）；命中/未命中计数。
   - 单元测试：同 blob_sha 二次命中；不同解析器版本不命中；LRU 淘汰。
   - **不改现有解析调用路径**（接线留开关）。
3. **反向依赖索引**：新增 `cache/ReverseDependencyIndex`：文件导出符号 → 依赖方文件集合；增量更新接口。单元测试：A 导出 → B 依赖 A → A 符号变更返回 [B]。**只建索引结构，不接线**。
4. 提交：每子项一个 commit。

## 任务三：阶段 5 P2 骨架（分阶段自适应并发）

分支：`fix/perf-phase5`（从 fix/perf-baseline 派生）。

1. **分阶段有界队列**（agent-service，新增 `app/runtime/staged_concurrency.py`）：
   - 定义 `StagedPipeline`：读取/解析/模型/提交四阶段，每阶段有界队列 + worker 数配置；模型阶段带 AIMD 控制器（429/延迟超阈值 → 乘性减半+退避；稳定 → 加性增）。
   - 单元测试（fake 模型调用）：429 后并发减半；稳定后恢复；队列满不丢任务。
   - **不改** job_executor.py 现有 2 路并发路径（开关默认关闭）。
2. 提交：一个 commit。

## 任务四：汇总

1. 合并三个分支到 fix/perf-baseline（`git merge` 即可，无冲突直接合）。
2. 更新 `IMPLEMENTATION_PLAN.md` 的"批次记录"：本批新增的骨架文件清单、测试数、提交 hash。
3. 写 `tools/perf/NIGHT-REPORT.md`：每任务完成情况、测试输出摘录、HOLDING 问题清单（如有）、下一批建议。

## 约束（全程）

- 提交信息中文惯例；不提交杂物（.data、日志、缓存、.env、node_modules）
- **只加不重构**：任何"顺手优化"既有路径的行为都算偏离——新抽象独立成文件，开关默认关闭
- 跑测试：agent-service 用 `python -m pytest tests/ -x`（collection 缺 tree_sitter_java 是既有环境问题，记录不修）；mcp-server 用仓库根 `./mvnw -pl devcollab-mcp-server test`（注意 mvnw 在仓库根目录，不是模块目录）
- 若某任务测试环境有问题：记录到 HOLDING 文件并继续下一个任务，不无限重试
- 遇到规格歧义：读 IMPLEMENTATION_PLAN.md 对应章节，按"已验证事实"和"语义本会话定案"执行；确实无法裁决的写 HOLDING，不要停
