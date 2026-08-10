# ContextWeave 优化施工计划（2026-08）

> 目标：正确性修复先于性能优化，产出可写进简历的量化性能成果。
> 分工：Codex 实现大头（按模块分支并行 + 写回归测试 + 跑实验），本会话负责规格决策、契约定义、review 与最终报告。
> 冲突规避：每个任务一个独立分支；跨模块契约（批量工具 schema、缓存归属）先在本计划中定案，再动手。

---

## 阶段 0：准备（Codex，半天）

- [ ] 跑通现有测试套件并记录现状：`.\mvnw.cmd test`（core/worker/gateway）、agent-service 的 pytest（注意排除 `.pytest-tmp`）
- [ ] 确认 `devcollab.repository.inspect_code_metadata` 的返回是否含 `blobSha`（P1 前提）；不含则记录为"需加字段"，在阶段 4 开头添加
- [ ] 打基线 tag：`git tag perf-baseline`（当前 commit），后续实验一律从该 commit 派生，保证可复现
- 验收：测试全绿记录在案；blobSha 结论明确；tag 存在

## 阶段 1：正确性修复（先于一切性能工作，测试先行）

每个修复 = 先写复现测试（红）→ 修复（绿）→ 模块全量回归。
**不要把"写测试"拆成独立阶段**：测试是每个修复的交付物之一。

| # | 位置 | 修复 | 针对性回归测试 | 分工 |
|---|---|---|---|---|
| 1.1 | core 漂移管线 | 应用阶段"先 wipe 再插 proposal"改差量 upsert；未漂移绑定必须保留；wipe 时记录删除明细日志 | 同 block PRIMARY+SUPPORTING 只漂移其一，另一个必须存活 | 语义我先定（下见），实现 Codex |
| 1.2 | core binding-only | 禁止 REMOVE_BINDING 走自动应用（applyBindingOnly 仅允许 UPSERT，或改 requireAdmin）；成员删除他人绑定必须经审批 | MEMBER 提交含 REMOVE_BINDING 的 binding-only CR 必须返回 PENDING 走审批 | 同上 |
| 1.3 | core JwtProperties | 拒绝占位符默认值，启动 fail-fast（仿照 AgentDelegationProperties） | 用默认 secret 启动必须抛异常 | Codex |
| 1.4 | agent 审批门 | fail-closed：除 APPLIED 外一律按 PENDING 处理（404→MISSING 视为 PENDING 重试，不再放行）；**同时预算判定豁免 SKELETON_REVIEW_PENDING**（真实失败耗尽预算后撞 PENDING 不得永久 FAILED，见 worker.py:547） | 骨架 review 404 / 状态缺失 / 未预期状态 → 批次单元必须重试而非执行；预算耗尽后撞 PENDING 仍等待 | Codex |
| 1.5 | agent 429 | `MODEL_RATE_LIMITED` 加入 RETRYABLE_ERRORS（P2 的前提） | 429 后走退避并最终成功，单元不永久 FAILED | Codex |
| 1.6 | agent run_id 幂等 | `run_id` 去掉 attempt 序号，重放时 clientRequestId 稳定；处理原请求已 APPLIED 的 409 | 提交成功后崩溃→重认领→不产生第二份变更请求 | Codex |
| 1.7 | agent 死端点 | `POST /api/v1/agent-runs`（run_executor 从未创建，必然 500）：删除或注入实现 | 无（删端点即可，保留 /context） | Codex |
| 1.8 | core token_type | JWT verify 后按调用上下文校验 token_type（HTTP 拒绝非 access；gRPC 拒绝非 delegation）；delegation 强制校验 allowed_tools | 用 delegation token 调 REST 接口必须 401/403 | Codex |
| 1.9 | gateway JWT | 与 1.3 同：拒绝占位符默认值，启动 fail-fast | 默认值启动失败 | Codex |
| 1.10 | 仓库卫生 | `git rm -r agent-service/.pytest-tmp`；`.gitignore` 补 `.pytest-tmp/` 与 `docs/9[0-9]-*` 模式；用 filter-repo/BFG 清历史（.pytest-tmp 含真实 JWT） | 无 | Codex |
| 1.11 | 文档/配置 | README：手动流程补 Kafka topic 预创建步骤、agent 端口 8091→8092；`.env.example` 补 `DEVCOLLAB_AGENT_INTERNAL_TOKEN` 并注明双端一致 | 无 | Codex |
| 1.1b | core 漂移 REPLACE + 绑定删除旁路 | 第一批 review 发现（2026-08-09，本会话）：① 非 NONE 漂移（MOVED/COSMETIC/SIGNATURE_CHANGED）的 UPSERT bindingId=null → 应用侧只新建不删旧 → **旧位置僵尸绑定永存**，且 SIGNATURE_CHANGED 被 findExactBinding 短路吞掉（签名漂移永不修复）；② applyBindingProposal 对 bindingId 非 null 的 UPSERT 在自动应用路径执行 `deleteBinding(任意 id)`，而 lockAndValidateBindingTargets 只校验 REMOVE → **MEMBER 可无审批删除任意绑定** | 见下方 1.1b 语义规格；红测已就位（DriftBindingPreservationTest 增强 + BindingUpsertCannotDeleteArbitraryBindingTests） | 本会话定案，Codex 实现 |
| 1.12 | core 绑定查询过滤 | 既有失败：`GitKnowledgeIntegrationTests.shouldCreateAndQueryLegacyRangeAndSymbolBindings`（期望 7 实际 8）——单跑复现，8 条全为测试自建：`includeLegacy=true&revision=A` 查询返回了 `revision=B` 的绑定 → revision 过滤对部分锚点失效（真实查询 bug，非本批引入） | 修 SQL 过滤语义：revision=A 不得返回 revision=B 绑定；修后该测试绿 | Codex |

**1.1 语义规格（本会话定案，Codex 不得自行发挥）**：
- `DriftDetectionService.detectAndSubmit`：对 proposal 触碰的每个 target，生成该 target 下**全部**绑定项——漂移的走 REPLACE/REMOVE，未漂移的生成 UPSERT 保留项；
- 应用侧删除 `removeBindingsForTarget` 的整删逻辑，改为逐项 diff upsert（proposal 里没有的现存绑定 = 保留）；
- 确需删除时（文件删除/绑定 REMOVE）记录删除明细（绑定 id、symbol、target）到变更流水。
- 回归测试：同一 block 绑定 PRIMARY+SUPPORTING 两个 symbol，仅 PRIMARY 漂移 → 修复后两个绑定都存活。

**1.1b 语义规格（第一批 review 补定，Codex 不得自行发挥）**：
- **REPLACE 语义**：非 NONE 漂移（SYMBOL_MOVED/COSMETIC/SIGNATURE_CHANGED）的 UPSERT proposal 必须携带 `bindingId = 原绑定 id`，字段 = 漂移后新值（位置/行号/签名）；NONE 保留项不变（bindingId=原 id，字段=原值）；broken → REMOVE_BINDING（不变）。
- **内部入口**：`DocumentChangeApplicationService` 增加 `createDrift(...)`（内部变体，复用 create 主体逻辑）；`DriftDetectionService` 改调 createDrift。用户路径（REST/agent 的 `create(...)`）：bindingProposals 中任何 UPSERT 携带 bindingId → 抛 conflict（REQUEST_TARGET_CHANGED）拒绝——**用户/agent 的 UPSERT 永远不带 bindingId**（新建语义），REPLACE 只属于漂移自愈。
- **应用侧 REPLACE**：applyBindingProposal 对 bindingId 非 null 的 UPSERT（仅内部路径可达）：加载绑定 → 校验存在 + workspace 归属 + target（documentId/blockId 与 proposal 一致）→ **就地更新字段（update by id，禁止 delete+create）** → 事件流水记"绑定更新"明细。UPSERT 分支**删除 deleteBinding 调用**（deleteBinding 只保留给 REMOVE_BINDING）。
- **SIGNATURE_CHANGED 修复**：REPLACE 走 update-by-id 后不再被 findExactBinding 短路吞掉——signature 必须随 REPLACE 更新（当前实现下签名漂移永不修复）。
- **唯一约束兜底**：update 撞唯一冲突 → 目标已有同键绑定 → 保留目标绑定、删除被替换的旧绑定（记删除明细）。正常漂移流不触发。
- **回归测试**（工作区已就位，红）：① moved/COSMETIC 的 UPSERT 必须携带旧 bindingId（`DriftBindingPreservationTest` 两个用例）；② MEMBER 携带他人 bindingId 的 UPSERT：不得自动应用（4xx 或 PENDING）、victim 绑定必须存活（`BindingUpsertCannotDeleteArbitraryBindingTests`）。

**1.2 语义规格**：`applyBindingOnly` 遇 `REMOVE_BINDING` proposal 一律返回 PENDING 走审批；UPSERT 语义维持现状（漂移自愈的自动写继续走原路径，本修复只堵"成员删他人绑定"）。

**1.4 语义规格**：`_review_status` 返回值只认 `APPLIED` 放行；`PENDING`/`MISSING`/空串/未预期值全部抛 `SkeletonReviewPendingError`（重试，不消耗预算）；`REJECTED`/`STALE` 维持 NO_CHANGE。

并行窗口：1.1、1.2 的 spec 已在本计划定案，1.3–1.11 互不依赖 → Codex 可分模块多分支并行（core / agent / gateway / 仓库卫生 / 文档）。

验收：`mvnw test` + agent pytest 全绿；1.1/1.2/1.4/1.5/1.6/1.8 有针对性回归测试；1.10 后 `git log --all` 不再出现 `.pytest-tmp`。

## 阶段 2：性能基线与埋点（Codex 大头）

- agent-service 加埋点（只加不重构，改动最小）：
  - MCP tool 调用计数 + 单次耗时分布（P95/P99）
  - session `initialize()` 计数
  - delegation exchange 计数
  - 分阶段耗时：读取 / 解析 / 模型调用 / 变更提交
  - 429 次数、重试次数、重试消耗
- 基线实验（优化前数字 = 简历对比的"before"）：
  - 场景：小仓 / 100 文件仓 / 现有 479 文件压力仓 / "仅 1% 文件变化"增量场景
  - 每组预热后 20–30 轮，同一 commit（perf-baseline tag）
  - 记录：tool 调用数、session init 数、MCP P95、Unit 吞吐、队列等待、峰值内存、总耗时、输出字节
- 产出：`tools/perf/REPORT-v1.md`（before 数字）

## 阶段 3：P0 — 连接复用 + N+1 消除（Codex 实现，契约本会话定）

**已验证事实（Codex 直接采信，勿重新推导）**：
- 所用 MCP SDK（1.x）`ClientSession.send_request` 按 request id 匹配响应，**无串行锁** → job 级共享 session 不阻塞 2 路 unit 并发；
- 现状每次 tool 调用：新建 `httpx.AsyncClient` + `streamable_http_client` + `ClientSession` + `initialize()`（mcp_client.py:116-141），且 `DelegatedMcpClient.call_tool` 每次都先做一次 delegation exchange（delegated_mcp_client.py:38-41）；
- 已有批量工具：`binding.list_batch`、`repository.inspect_code_metadata`；缺失：`code.read_batch` / `get_structure_batch` / `find_candidates_batch`（需在 Java MCP server 添加）；
- `code.read` 单次上限 400 行（startLine/endLine 分页），批量接口必须保留行数与总字节上限语义。

工作项：
1. **会话复用**：job 级共享 httpx.AsyncClient + ClientSession（一个 session 服务 job 内全部 unit），initialize 只做一次；异常/超时后重建
2. **delegation token 短 TTL 缓存**（按 delegation_id，TTL ≤ 5min），消除每次调用一次 exchange，同时规避并发 exchange 互相轮换失效
3. **批量工具**（Java MCP server + 契约）：
   - `devcollab.code.read_batch`：paths + revision，返回 {path→(content, language, totalLines, truncated)}，总字节上限（如 200KB）截断标记
   - `devcollab.document.get_structure_batch`：documentIds，返回结构数组（保留 includeBlockContent 语义）
   - `devcollab.document.find_candidates_batch`：paths，返回 path→candidates
4. **绑定优先**：unit 先 `binding.list_batch` 查现有绑定 → 仅未绑定文件走 find_candidates（遵守项目原则：候选复用必须有 existing_binding_count>0 证据）
5. **SourceReader/DocumentReader 换批量调用**，保留分页续读兜底

验收（对照阶段 2 基线）：tool 调用数下降 ≥50%；session init 数趋近 0；MCP P95 明显下降；**确定性回归通过**（见阶段 6）。

## 阶段 4：P1 — 内容寻址增量缓存（Codex 骨架，语义本会话定）

**已验证事实**：`blob_sha` 已存于 worker（GitRepositoryFileProjection）；AST/符号解析发生在 worker（Java 侧），缓存归属 Java 侧；漂移检测同样在每次新 commit 重建符号表 → 同一缓存可服务 agent 管线和漂移管线。

工作项：
1. 确认/添加 `inspect_code_metadata` 返回 `blobSha`（阶段 0 结论）
2. worker 侧缓存：key = `blob_sha + 解析器/提取器版本号`；进程内 LRU（可选磁盘），数量级控制（不把整个 479 文件仓的 AST 全塞内存，按 LRU 淘汰）
3. 反向依赖索引：文件 A 导出符号变化 → 沿反向闭包重算受影响文件（复用已有 Java 依赖图，不要只重算 A 自己）
4. 增量读取路径：agent 先拿 revision 文件清单 + blobSha，与本地缓存比对，未变化文件**跳过源码拉取**（省的是网络链路，不只是解析）
5. 缓存命中/未命中日志：检测意外失效风暴（大量 miss 时告警）

语义规格：缓存 key 必须含解析器版本；任何代码变更导致的失效是确定性行为而非巧合；缓存不命中 = 重新解析，**绝不允许返回过期 AST**（宁可 miss 不可错）。

验收：AST 命中率（warm/cold 分离报告）；1% 文件变化场景总耗时显著下降（对照基线）；源码读取字节下降。

## 阶段 5：P2 — 分阶段自适应并发（Codex 实现，参数本会话定）

先决条件：1.5（429 可重试）已完成。

工作项：
1. 固定 2 路并发（config.py:99/103）重构为分阶段有界队列：读取 → 解析 → 模型 → 提交
2. 读取/解析阶段：并发可高（IO/CPU 密集无成本风险），有界队列容量 = 峰值内存上限
3. 模型阶段：AIMD（429/延迟超阈值 → 乘性减半 + 退避；稳定 → 加性增），初始值沿用 2
4. 提交阶段：同文档串行（同时规避 create_batch_units ordinal 唯一约束竞态）；跨文档可并行
5. 优先利用 DeepSeek 响应头的限流信息（若存在）做 AIMD 输入，不盲猜

验收：Unit 吞吐提升；队列等待 P95 有界；429 率下降；重试率、峰值内存、总成本纳入报告。

## 阶段 6：验证与简历素材（Codex 出数据，本会话写报告）

1. 全量实验：4 组场景 × 20–30 轮（预热后），代码状态 = 阶段 5 完成后的 commit
2. 质量门槛（不下降才能宣称提速）：
   - 槽位覆盖率、Binding 精确率、评审通过率（对照修复后基线）
   - **确定性回归**：temperature=0 下，同输入输出必须逐字节一致——优化后与修复后基线 diff 为空
   - 幂等性：无重复变更请求（1.6 的回归测试在实验轮次中重复通过）
3. 产出 `tools/perf/REPORT-v2.md`：优化后数字 + 与 v1 的对比表 + 各阶段贡献分解
4. 简历条目草稿：每项优化的量化数字 + 叙事（连接复用消除 N+1 / 内容寻址增量分析 / 分阶段自适应调度）

---

## 实验纪律（贯穿阶段 2–6）

- 同 commit 对比：before/after 必须同一仓库 commit 派生，源码漂移会污染所有指标
- warm/cold 分离：P1 命中率只报 warm；1% 增量场景单独报告
- 预热 ≥ 5 轮（模型缓存、连接池、JIT 稳定），正式轮 20–30
- 每次实验记录：repo、commit、参数、环境（并发数、版本）、轮次编号——可复现是实验有效的前提

## 测试样例（本会话已就位，Codex 开工即用）

| 修复 | 测试文件 | 断言契约（红→绿） |
|---|---|---|
| 1.1 | `knowledge-core/src/test/java/com/devcollab/knowledgecore/git/application/DriftBindingPreservationTest.java` | 同 block 两个绑定、仅其一漂移：proposals 必须 2 条；漂移的 UPSERT/REMOVE 到位，未漂移的 bindingId == 现有绑定 id |
| 1.2 | `knowledge-core/src/test/java/com/devcollab/knowledgecore/documentchange/api/BindingOnlyRemoveRequiresApprovalTests.java` | 含 REMOVE_BINDING 的 binding-only CR：GET 后 `$.request.status` == PENDING，绑定仍存在（修复前 APPLIED 且被删） |
| 1.4 | `agent-service/tests/test_skeleton_review_gate_fail_closed.py` | 未知状态（MISSING/空串/CANCELLED/PROCESSING）→ RETRY_WAITING + SKELETON_REVIEW_PENDING + 300s + attempt 归零，JobExecutor 不得执行；预算耗尽后撞 PENDING 仍等待 |
| 1.1b | `knowledge-core/.../git/application/DriftBindingPreservationTest.java`（已增强） | moved 的 UPSERT 必须携带 `bindingId == moved.id()`（修复前 null → 红）；新增 lineShifted 用例：COSMETIC 同样携带旧 id + 新行号 |
| 1.1b | `knowledge-core/.../documentchange/api/BindingUpsertCannotDeleteArbitraryBindingTests.java` | MEMBER 提交 UPSERT 携带他人 bindingId：POST <500、不得自动 APPLIED（4xx 或 PENDING）、victim 绑定 count == 1（修复前自动删除 → 红） |

注：1.2 的 fixture 复制自 `PreciseBindingProposalIntegrationTests`；若 REMOVE_BINDING 请求 schema 不接受 `bindingId` 等字段，按 controller 的请求 schema 调整 payload 字段，**断言（PENDING + 绑定存活）是硬契约不可改**。1.1 修复须同时满足测试断言与规格（全量 proposal + 应用侧免 wipe）。

## 批次记录

**第一批（已完成，2026-08-09）**：1.1（37b67f8/58d0cfd）、1.2（31b847a/13ab577）、1.4（bfb5271/e861906）红→绿合并回 main，HEAD=e861906，未推送。阶段 0 结论：`inspect_code_metadata` 不含 `blobSha`（阶段 4 需加字段）；`perf-baseline` tag 已打（e766c1c）。遗留环境问题（记录不修）：agent pytest 全量 collection 缺 `tree_sitter_java`；devcollab-worker `RuntimeMemoryProfilerTests` 9 errors（Windows 临时目录 AccessDenied）。
**第一批 review（本会话）**：1.2/1.4 实现通过；1.1 检测侧通过，但应用侧发现 D1（僵尸绑定 + 签名漂移被吞）与 D2（绑定删除旁路）——补定 1.1b 规格（见上），红测已就位。另定 1.12（GitKnowledge 查询过滤 bug，既有失败，单跑复现）。

**第二批（已完成，2026-08-09 本会话 review 通过）**：1.1b + 1.3 + 1.5 + 1.6 + 1.7 + 1.8 + 1.9 + 1.10（部分）+ 1.11 + 1.12 全部合并回 main（9 功能提交 + 7 合并，ahead 20，未推送）。本会话复验：knowledge-core 271 tests 0 fail（4 skipped）；1.1b 全链路符合规格（createDrift 仅检测侧调用、用户路径拒绝 UPSERT+bindingId、update-by-id 保 id + FOR UPDATE 锁行 + 目标一致性校验、SIGNATURE_CHANGED 不再被 findExactBinding 吞掉、唯一冲突 fallback 保留并发目标）；1.12 应用层 filter 偏离 SQL 方案（原因：既有测试约束结构化 anchor 的 legacy 语义，已文档化，语义正确）；1.5/1.6 幂等闭环成立（run_id 稳定 + clientRequestId 内容指纹 + MCP 接受 APPLIED 重放）；1.3/1.8/1.9 fail-fast + token_type 上下文校验齐全；test_agent_retry_idempotency.py 覆盖 429 重试/崩溃重放/APPLIED 三场景。

**第二批 review 遗留（待决）**：
- ~~JWT 占位符矛盾（1.3×1.11 集成缺陷）~~ **已修复（2026-08-09 本会话直改）**：三端 application.yml（core/gateway/mcp-server）+ `.env.example` + `.env` + `tools/e2e-gateway-verification.md` 默认值统一改为 `devcollab-local-dev-jwt-secret-8f3a2c9e7b1d4f6a`；JwtProperties/GatewayJwtProperties 保留旧占位符拒绝（防部署沿用公开值）。测试文件中的旧值常量是拒绝逻辑测试，保持。
- ~~1.10 history cleanup 阻塞~~ **已完成（2026-08-09 用户批准后执行）**：mirror 备份（`devcolab-backup-20260809.git`）→ filter-repo 重写全部 refs 移除 `*pytest-tmp*`（217 提交保留，token blob 从 pack 物理清除）→ 额外发现并删除 3 个含敏感内容的 `refs/codex/turn-diffs/checkpoints` tree refs（Codex 工具在 .pytest-tmp 未忽略时保存的 turn 快照，各 57 个残留 blob；保留 2 个干净 checkpoint）→ reflog expire + gc prune → 重新挂 origin。**push 前注意**：新历史与 origin 旧历史 hash 全不同，首次 push 需 force；本地旧 `fix/*` 分支已重写保留（已合并，可清理）。

## 当前下一步

1. ✅ 第一批 1.1/1.2/1.4 已合并，review 完成
2. ✅ 第二批 1.1b/1.3/1.5-1.9/1.10/1.11/1.12 已合并，review 完成（271 green 复验）
3. ✅ JWT 占位符修复（本会话直改）+ 1.10 历史清理（用户批准，全 refs 重写完成）
4. ✅ 第三批（阶段 2 埋点+脚本）Codex 提交 4 commits，本会话 review 通过；403 根因（token 过期）本会话直改修复（ca5b3b7）
5. ▶ 第四批（基线补全）：白天分次跑 small/hundred/stress/incremental 各 20 轮，重写 REPORT-v1.md；提示词见 tools/perf/PROMPT-batch4.md
6. 基线完整后 → 阶段 3（连接复用 + 批量工具）开工

---

## 给 Codex 的开工提示词（第一批——已完成，2026-08-09 本会话 review 通过/补齐，结果见"批次记录"）

你是 ContextWeave 项目的实现工程师。严格按施工计划执行第一批任务，逐字遵守其中规格，不做计划外改动。

**第一步：读计划**。完整阅读本文件（IMPLEMENTATION_PLAN.md）。"已验证事实"直接采信，不要重新推导；"语义本会话定案"的规格段（1.1/1.2/1.4）必须逐字遵守。

**第二步：阶段 0（现状摸底）**。跑通现有测试套件并记录结果：根目录 `.\mvnw.cmd test`（core/worker/gateway）、agent-service 的 pytest（排除 `.pytest-tmp`）。确认 `devcollab.repository.inspect_code_metadata` 返回是否含 `blobSha`，把结论写进报告。执行 `git tag perf-baseline`。**注意**：若某模块存在与本计划无关的既有测试失败，记录但不修复。

**第三步：阶段 1（测试先行，红→绿）**。工作区已就位三份回归测试，先跑确认红，再实现修复转绿：
- `DriftBindingPreservationTest.java` → 修复 1.1
- `BindingOnlyRemoveRequiresApprovalTests.java` → 修复 1.2
- `test_skeleton_review_gate_fail_closed.py` → 修复 1.4（含预算豁免）

分支策略（可并行）：`fix/core-drift-wipe`（1.1）、`fix/core-binding-approval`（1.2）、`fix/agent-approval-gate`（1.4+1.5+1.6+1.7）、`fix/core-auth-jwt`（1.3+1.8）、`fix/gateway-jwt`（1.9）、`fix/repo-hygiene`（1.10）、`fix/docs-config`（1.11）。每个分支：红测试 → 修复 → 测试绿 → 模块全量测试 → 报告 → 合并。

**第四步：报告**。每个分支完成后报告：改动文件清单、红→绿测试输出摘录、全量测试通过/失败数、遇到的问题（若有偏离规格的决策必须说明原因）。

**约束**：
- 提交信息用仓库惯例（中文，`功能：`/`修复：` 前缀）；不提交 `.pytest-tmp`、venv、node_modules、`.data` 等杂物
- 1.2 若因校验字段不匹配报 4xx：按 `GitKnowledgeIntegrationTests` 的种子方式调整 payload 字段，断言（PENDING + 绑定存活）是硬契约不可改
- 1.1 必须同时满足测试断言与计划规格（全量 proposal + 应用侧删除 wipe 整删逻辑）
- 1.4 必须同时满足 fail-closed 与预算豁免两项行为

## 给 Codex 的开工提示词（第二批——已完成，2026-08-09 本会话 review 通过，遗留见"批次记录"）

你是 ContextWeave 项目的实现工程师。执行施工计划第二批任务。第一批（1.1/1.2/1.4）已合并到 main（HEAD=e861906），本批在其上继续。

**第一步：读计划**。完整阅读本文件。第一批 review 新增了 **1.1b 与 1.12**（含完整语义规格，见阶段 1 表格与规格段）。规格段（1.1/1.1b/1.2/1.4）逐字遵守，不得自行发挥；1.1b 规格未覆盖的边界（如 createDrift 事务边界）→ 记录决策与原因报告给本会话，不静默自行发挥。

**第二步：本批红测已就位（先跑确认红，再修）**：
- `DriftBindingPreservationTest`（已增强：moved 用例断言 UPSERT 携带旧 bindingId；新增 lineShifted 用例）→ 检测侧 1.1b（注意：检测侧当前已绿，**1.1b 的主体是应用侧**，见下）
- `BindingUpsertCannotDeleteArbitraryBindingTests`（新文件）→ 应用侧 1.1b（D2 旁路；当前红：自动路径删除 victim）

**第三步：任务与分支（可并行）**：

| 分支 | 任务 | 要求 |
|---|---|---|
| fix/core-replace-binding | **1.1b** | ① `createDrift(...)` 内部入口（复用 create 主体），`DriftDetectionService` 改调它；② 用户路径 `create(...)`：UPSERT 携带 bindingId → 一律 conflict（REQUEST_TARGET_CHANGED）；③ 应用侧 REPLACE：bindingId 非 null 的 UPSERT 加载绑定并校验（存在 + workspace 归属 + target 一致）后**就地更新字段（update by id，禁止 delete+create）**，事件流水记"绑定更新"明细；④ UPSERT 分支删除 deleteBinding（deleteBinding 只保留给 REMOVE_BINDING）；⑤ SIGNATURE_CHANGED 不得被 findExactBinding 短路吞掉（signature 必须随 REPLACE 更新）；⑥ update 撞唯一冲突 → 保留目标已有绑定、删除被替换旧绑定（记删除明细） |
| fix/core-auth-jwt | 1.3 + 1.8 | 1.3：JwtProperties 拒绝占位符默认值，启动 fail-fast（仿 AgentDelegationProperties）。1.8：JWT verify 后按调用上下文校验 token_type（HTTP 拒非 access；gRPC 拒非 delegation）；delegation 强制校验 allowed_tools。**1.8 先写回归测试**（用 delegation token 调 REST 必须 401/403）再修 |
| fix/agent-retry-idempotency | 1.5 + 1.6 + 1.7 | 1.5：`MODEL_RATE_LIMITED` 加入 RETRYABLE_ERRORS——**先写测试**（429 后走退避并最终成功，单元不永久 FAILED，参考 test_skeleton_review_gate_fail_closed.py 的 fake 模式）再修；1.6：run_id 幂等键去 attempt 序号、重放时 clientRequestId 稳定、处理原请求已 APPLIED 的 409——**先写测试**（提交成功后崩溃→重认领→不产生第二份变更请求）再修；1.7：删死端点 `POST /api/v1/agent-runs` |
| fix/gateway-jwt | 1.9 | 占位符 fail-fast，仿 1.3 |
| fix/repo-hygiene | 1.10 | 工作区 `git rm -r agent-service/.pytest-tmp`（已删则确认）；`.gitignore` 补 `.pytest-tmp/` 与 `docs/9[0-9]-*`；**清历史**：`.pytest-tmp` 含真实 JWT，用 filter-repo/BFG 清理 `agent-service/.pytest-tmp` 路径的历史（只清该路径，不碰其他历史）；filter-repo 不可用则报告给本会话，不动手改 history |
| fix/docs-config | 1.11 | README：手动流程补 Kafka topic 预创建、agent 端口 8091→8092；`.env.example` 补 `DEVCOLLAB_AGENT_INTERNAL_TOKEN` 并注明双端一致 |
| fix/core-binding-query | **1.12** | `GitKnowledgeIntegrationTests.shouldCreateAndQueryLegacyRangeAndSymbolBindings` 既有失败（单跑复现）：`includeLegacy=true&revision=A` 返回了 `revision=B` 的 FILE 绑定 → 修查询过滤 SQL（revision 过滤对部分锚点失效）。**不改测试断言**（期望 7 是正确行为：revision=A 或 legacy null） |

**第四步：验收**：1.1b 两份红测全绿；`.\mvnw.cmd test`（knowledge-core）0 失败；agent pytest（tests/ 下、排除 .pytest-tmp）0 失败（collection 缺 `tree_sitter_java` 是既有环境问题，记录不修）；1.5/1.6/1.8 有针对性回归测试；1.10 后 `git log --all -- agent-service/.pytest-tmp` 为空。

**约束**：
- 提交信息中文惯例（`功能：`/`修复：` 前缀）；不提交杂物
- 1.1b：UPSERT 分支不得再出现 deleteBinding；用户路径 UPSERT 携带 bindingId 一律 conflict；drift 行为（自动应用、不打断）不得改变
- 1.12 只修过滤语义，不改测试断言
- 每分支：红测 → 修复 → 绿 → 模块全量 → 报告（改动文件清单、红→绿测试输出摘录、全量通过/失败数、偏离规格的决策与原因）

## 给 Codex 的开工提示词（第三批——阶段 2 性能基线与埋点，2026-08-09 发送）

你是 ContextWeave 项目的实现工程师。执行施工计划**阶段 2**（性能基线与埋点）。前两批（1.1–1.12 + 1.1b）已完成并 review 通过。

**重要背景（先读再动）**：仓库历史已重写清理（`.pytest-tmp` 从全部历史移除，commit hash 全部变化）。**当前 main 头部 = `40f481a`（修复：JWT 开发默认 secret 替换公开占位符），`perf-baseline` tag 已存在于重写后的新历史中。所有实验以 `perf-baseline` 代码状态为准，禁止引用旧 hash。** 本地 `.env` 的 `DEVCOLLAB_JWT_SECRET` 已更新（旧公开占位符会被启动校验拒绝，如遇启动失败先核对 .env）。

**第一步：读计划**。完整阅读本文件"阶段 2：性能基线与埋点"与"实验纪律"两节，逐字遵守。阶段 2 产出顺序：先埋点（改动最小、只加不重构），后实验脚本，再跑基线实验，最后 `tools/perf/REPORT-v1.md`。

**第二步：埋点（2.1，只加不重构）**。在 agent-service 加可观测性，不引入新依赖（如已有 Prometheus/日志框架则复用，否则轻量自研计数器 + 时间窗口分位数）：
- MCP tool 调用计数 + 单次耗时分布（P50/P95/P99）
- session `initialize()` 计数
- delegation exchange 计数
- 分阶段耗时：读取 / 解析 / 模型调用 / 变更提交
- 429 次数、重试次数、重试消耗（退避时长合计）
- 埋点结果必须可被实验脚本从外部读取（JSON 汇总文件或 stdout 一行汇总），**禁止只写 DEBUG 日志**（不可解析）

**第三步：实验脚本（2.2）**。写 `tools/perf/run-baseline.py`（Python，可重复运行、幂等、无状态残留）：
- 4 场景：小仓（≤20 文件）/ 100 文件仓 / 现有压力仓（约 479 文件）/ "仅 1% 文件变化"增量场景
- 每组预热后 20–30 轮；一轮 = 一个完整 job 从入队到完成
- 每轮记录（一行汇总，JSON 或 CSV）：tool 调用数、session init 数、delegation exchange 数、MCP P95、Unit 吞吐（units/秒）、队列等待、峰值内存（rss 采样）、总耗时、输出字节、429/重试数
- **输出纪律（重要）**：脚本 stdout 只输出每轮一行汇总，禁止输出原始日志、工具响应、模型输出——实验日志会撑爆上下文，报告只需要汇总数字。脚本必须能从外部触发统计读取（例如结束后 dump 全部统计到 `tools/perf/baseline-<scenario>.json`）
- 压力仓一轮成本高（DeepSeek 费用/时长）：若单轮 >10 分钟或成本过高，记录实际轮数并说明原因，不强行跑满 20–30

**第四步：跑实验 + 报告**：
- 跑 4 场景基线，产出 `tools/perf/REPORT-v1.md`：4 场景 × 指标表（每指标 P50/P95/均值/样本数）+ 环境说明（硬件、模型、commit=perf-baseline）+ 可复现步骤（命令 + 前置条件）
- 报告：改动文件清单、埋点验证方式（如何确认计数正确）、REPORT-v1.md 内容摘要、遇到的问题与决策

**约束**：
- 提交信息中文惯例（`功能：`/`修复：` 前缀）；不提交 `.pytest-tmp`、`.data`、日志等杂物（`.gitignore` 已含 `.pytest-tmp/`，勿再创建或提交）
- **埋点只加不重构**：不得顺手优化业务代码（读取/解析/并发/会话均为阶段 3–5 范围），此批任何行为变化都算偏离
- 实验脚本与报告路径固定：`tools/perf/run-baseline.py`、`tools/perf/REPORT-v1.md`
- 每步完成即提交（中文信息），分支建议 `fix/perf-baseline`；报告里给出分支/提交 hash 供 review

**第三批 review（2026-08-10 本会话）**：Codex 在 `fix/perf-baseline`（派生自 perf-baseline=df57e8f）提交 4 个 commits：`62ca474`（埋点，纯增量：PerfMetrics 线程安全 + JSONL 事件 + /api/v1/perf/metrics + reset）、`ea541de`（实验脚本）、`14bf0e8`（每轮 metrics 隔离）、`a2c88c1`（基线报告记录）。埋点质量通过（只加不重构、JSONL 可解析、percentile 计算正确）。实验：incremental 1 轮成功（84.33s，MCP P95 60.244ms，tool/session/delegation 各 1）；**small 场景失败 403 MCP_PERMISSION_DENIED——根因非 Codex 判断的 asyncpg AmbiguousParameterError，而是 run-baseline.py access token 过期**（脚本登录一次拿 token TTL=PT30M 后全程复用；small 轮 17:42:05→18:12:36 恰 30.5 分钟，轮询 agent-jobs authorize 401/403 被映射为 MCP_PERMISSION_DENIED）。**本会话直改修复**：`ca5b3b7`——ApiClient 记住注册账号密码，收到 401/403 自动重新登录换发 token 重试一次；已在 fix/perf-baseline 最新（位于 a2c88c1 之后）。验证实验进行中按用户要求终止（凌晨不等待长 job）。hundred/stress 场景未跑；REPORT-v1.md 已产出但 small 数据为失败轮。

**遗留（待 Codex 第四批处理）**：
- small/hundred/stress 场景基线补全（白天跑，勿熬夜挂机）
- REPORT-v1.md 用真实数据重写，403 根因与修复写入"已知问题"
- 若 small 重跑仍现 AmbiguousParameterError → 独立定位（可能是真实 worker bug），与 403 分开报告

**第四批执行记录（2026-08-10 凌晨）**：用户转发了第四批提示词，Codex 02:56 新回合开始执行（同步 ca5b3b7、重建 agent 镜像）。**small 实验第三次失败**：`Repository sync failed: Only public HTTPS repositories on allowed hosts are supported`——Codex 自建本地 fixture 仓库（127.0.0.1 临时 HTTP）被 knowledge-core 的 `DEVCOLLAB_GIT_ALLOWED_HOSTS`（仅 github.com）拒绝。**环境事实记录**：实验仓库必须用公开 HTTPS GitHub URL；本地 fixture 仓库不可行（除非临时放开 allowed hosts，属实验环境决策，不静默改动）。之后按用户指示：凌晨不再等实验，转向夜间连续骨架施工（第五批）。

## 给 Codex 的开工提示词（第五批——夜间连续施工，2026-08-10 凌晨发送，全文另存 tools/perf/PROMPT-batch5-night.md）

（全文见 `tools/perf/PROMPT-batch5-night.md`。模式：无人值守连续施工——阶段 3（会话复用抽象 + delegation token 缓存 + 批量工具契约）→ 阶段 4（blobSha 字段 + 内容寻址缓存骨架 + 反向依赖索引）→ 阶段 5（分阶段自适应并发骨架）→ 汇总 NIGHT-REPORT.md。全程"只加不重构"，新抽象独立成文件、开关默认关闭；每子项一个 commit；遇无法裁决问题写 `tools/perf/NIGHT-HOLDING-ISSUES.md` 后继续下一任务。**Codex 配置已加 `approval_policy = "on-failure"`**（备份 `~/.codex/config.toml.night-autopilot.bak`），夜间不再每步请求批准。）

## 批次记录：第五批夜间阶段 3/4/5 骨架（2026-08-10）

- 分支与合并：`fix/perf-phase3`、`fix/perf-phase4`、`fix/perf-phase5` 已按顺序合并回 `fix/perf-baseline`。
- 提交清单：`f64f153`、`4953044`、`2d9ab4b`、`36ad1a0`、`a83973f`、`9d8de5d`、`0f139df`。
- 新增骨架文件：`agent-service/app/runtime/mcp_session_pool.py`、`agent-service/app/runtime/delegation_token_cache.py`、`agent-service/app/runtime/staged_concurrency.py`、`devcollab-mcp-server/src/main/java/com/devcollab/mcp/cache/ContentAddressableCache.java`、`devcollab-mcp-server/src/main/java/com/devcollab/mcp/cache/ReverseDependencyIndex.java`、三份 MCP batch contributor、六份 batch schema。
- 测试记录：agent-service 新增测试 8 passed；devcollab-mcp-server 受影响测试 57 passed；`devcollab-mcp-server compile` SUCCESS。
- 全量回归记录：agent pytest 因缺 `tree_sitter_java` 在 collection 阶段失败；MCP full test 100 run / 2 failures，均为既有换行断言，详见 `tools/perf/NIGHT-HOLDING-ISSUES.md`。

## 给 Codex 的开工提示词（第六批——接线批，2026-08-10 发送，全文另存 tools/perf/PROMPT-batch6.md）

（全文见 `tools/perf/PROMPT-batch6.md`。模式：开关化接线——每接线点一个默认关闭的开关 + 独立 commit；off 态与现状逐字节一致、on 态过确定性回归。任务一：阶段 3 接线——McpSessionPool（`AGENT_MCP_SESSION_POOL_ENABLED`）、DelegationTokenCache（`AGENT_DELEGATION_TOKEN_CACHE_ENABLED` + TTL=300s）、批量工具（`AGENT_BATCH_TOOLS_ENABLED`，只接 find_candidates_batch/get_structure_batch，**code.read_batch 因截断语义不等价不接，契约增强待定案**）。任务二：阶段 4——先修 code-metadata 缺失 blobSha（勘察发现真实 API 不返回，MCP 侧恒 null）、knowledge-core 正则结果缓存、agent job 内增量读取（`AGENT_INCREMENTAL_READ_ENABLED`，list_files 已含 blobSha）。任务三：阶段 5——先修 StagedPipeline 异常泄漏缺陷，再拆 job_executor 为四阶段接 worker 调度（`AGENT_STAGED_PIPELINE_ENABLED`，含降级路径）。勘察事实 10 条已在提示词内定案，Codex 直接采信。）

## 批次记录：第六批阶段 3/4/5 开关化接线（2026-08-10）

- 分支与提交：在 `main` 上连续提交 `bbd41a9`、`4f55b34`、`7688ef0`、`63e6690`、`956f5a4`、`c341914`、`5d4dc53`、`71bd317`、`61de556`。
- 开关清单：`AGENT_MCP_SESSION_POOL_ENABLED`、`AGENT_DELEGATION_TOKEN_CACHE_ENABLED`、`AGENT_DELEGATION_TOKEN_TTL_SECONDS`、`AGENT_BATCH_TOOLS_ENABLED`、`DEVCOLLAB_CODE_METADATA_CACHE_ENABLED`、`DEVCOLLAB_CODE_METADATA_CACHE_CAPACITY`、`AGENT_INCREMENTAL_READ_ENABLED`、`AGENT_STAGED_PIPELINE_ENABLED`，均默认关闭。
- 文件清单：agent `mcp_client.py`/`delegated_mcp_client.py`/`document_reader.py`/`workspace_reader.py`/`source_reader.py`/`job_executor.py`/`worker.py`/`config.py`；knowledge-core `GitKnowledgeApplicationService`、`CodeMetadataBatchResult`、`CodeMetadataInspector`、`ContentAddressableCache`、`CodeMetadataCacheProperties`、配置文件与对应测试。
- 测试记录：agent 定向测试命令均通过（session pool 4、delegation cache 5、document batch 6、incremental read 5、staged/AIMD 8）；knowledge-core 定向缓存 suite 23 passed；knowledge-core full `276 tests run / 0 failures / 4 skipped`。
- 全量回归记录：agent full pytest 仍因缺 `tree_sitter_java` collection 失败；MCP full test 仍为 100 run / 2 trailing-newline failures；两者均记录在 `tools/perf/NIGHT-HOLDING-ISSUES.md`。
- 降级/未接线：`devcollab.code.read_batch` 因 200KB 截断语义不等价未接；完整四阶段 `StagedPipeline` 替换 `JobExecutor` 风险过高，本批仅接 AIMD 模型退避 fallback。详见 `tools/perf/BATCH6-REPORT.md`。

## 批次记录：第七批 read_batch 契约增强 + 批量读取接线 + 实验脚本（2026-08-10）

- 分支与起点：在 `main` 上连续施工，起始 HEAD=`8d812d5`。
- 提交清单：
  - `3852811` 功能：read_batch 契约增强为逐文件首段读取（对齐 code.read 行语义）
  - `7482a1c` 功能：开关化接入 code.read_batch 源码批量读取
  - `b42749f` 功能：开关对比实验脚本（off vs 单开关 vs 全 on）
- 新增/增强文件清单：
  - `contracts/mcp/code_read_batch_output.json`
  - `devcollab-mcp-server/src/main/java/com/devcollab/mcp/application/CodeReadApplicationService.java`
  - `devcollab-mcp-server/src/test/java/com/devcollab/mcp/application/CodeReadApplicationServiceTests.java`
  - `agent-service/app/platform_mcp/source_reader.py`
  - `agent-service/app/config.py`
  - `agent-service/app/worker.py`
  - `agent-service/app/clients/mcp_client.py`
  - `agent-service/app/contracts/validator.py`
  - `agent-service/tests/test_source_reader_incremental_read.py`
  - `agent-service/tests/test_mcp_contracts.py`
  - `agent-service/tests/test_official_mcp_client_session_pool.py`
  - `tools/perf/run-switch-comparison.py`
- 测试数与验收摘录：
  - MCP 定向：`CodeReadApplicationServiceTests,McpToolSchemasTests` 共 10 tests，0 failures。
  - Agent 定向：source reader + official client + contract tests 共 26 passed。
  - 脚本冒烟：`run-switch-comparison.py` 通过 `py_compile`、`ruff check`、`ruff format --check`、`--help`。
  - Agent 全量：`python -m pytest tests/ -x` collected 123 items / 1 error，仍为既有 `tree_sitter_java` 缺失。
  - MCP 全量：`./mvnw.cmd -pl devcollab-mcp-server test` 为 103 run / 2 failures，仍为既有末尾换行断言差异。
- 行为边界：所有新增接线由 `AGENT_CODE_READ_BATCH_ENABLED=false` 默认关闭；未改变默认 hot path。`code.read_batch` 的服务端契约已从总 200KB 截断改为逐文件首段读取，并与 `code.read` 行分页语义对齐。
- 遗留：完整四阶段 `StagedPipeline` 替换和跨 job 持久化增量缓存继续 deferred，详见 `tools/perf/NIGHT-HOLDING-ISSUES.md` 第七批。
