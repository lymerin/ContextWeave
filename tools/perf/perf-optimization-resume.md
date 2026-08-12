# AI 工程文档生成管线性能优化 —— 数据与实现说明（简历素材）

> 用途：本文档供 Codex 打磨简历使用。包含项目背景、8 项优化的实现机制、A/B 实测量化数据、
> 实验方法论，以及诚实边界（哪些指标有混杂因素、哪些数字可放心写进简历）。

---

## 一、项目背景与挑战

所在系统是一个「AI 工程文档协作平台」：AI agent 自动分析 GitHub 仓库、生成并维护工程文档。
核心链路：**agent（DeepSeek 模型）→ MCP 工具服务 → 知识核心 → 仓库/文档数据**。

性能瓶颈（优化前实测）：

- 每个任务单元都重新初始化 MCP 会话 —— 单个 job（39 个单元）产生 **370 次会话初始化**
- 每次工具调用前都重新交换 delegation token —— 一个 job 产生 **762 次 token 交换**
- 单文件逐个读取：`code.read` 66 次、`get_structure` 123 次、`find_candidates` 61 次 —— 大量重复往返
- 文档 review 等待审批时单元以 300s 退避重试，一个 job 累计重试 360 次、退避 108 亿毫秒（约 30 小时等效等待）
- 整个 job 耗时 71 分钟，MCP 调用 p95 延迟 88.9ms

## 二、优化方案（8 个开关，全部默认关闭 ——「只加不改」原则）

设计原则：**行为等价**。所有优化以环境变量开关控制，默认 `false` 保持原行为，逐项验证、可独立回退。

| # | 开关 | 层级 | 优化内容 |
|---|---|---|---|
| 1 | `AGENT_MCP_SESSION_POOL_ENABLED` | agent | **MCP 会话池**：按 delegation 复用已建立的 MCP 会话，避免每个单元重复握手初始化 |
| 2 | `AGENT_DELEGATION_TOKEN_CACHE_ENABLED`（+TTL 300s） | agent | **delegation token 缓存**：按 delegation_id 缓存交换出的访问令牌（asyncio.Lock 并发安全，TTL 上限 300s），避免每次工具调用都走一次 token 交换 |
| 3 | `AGENT_BATCH_TOOLS_ENABLED` | agent | **批量工具**：单文件读取/结构查询/候选查询收敛为批量调用（`code.read_batch`、`document.get_structure_batch`、`document.find_candidates_batch`、`binding.list_batch`） |
| 4 | `AGENT_CODE_READ_BATCH_ENABLED` | agent | **代码读取批量化**：一次模型调用读取多个文件；超批大小自动续批（continuation），并统计续批次数 |
| 5 | `AGENT_INCREMENTAL_READ_ENABLED` | agent | **增量读取**：基于 git revision + blobSha 变更检测，文件未变化时跳过重复读取，只读变更部分 |
| 6 | `AGENT_STAGED_PIPELINE_ENABLED` | agent | **分阶段流水线**：项目流程分为 discovery → 骨架计划（SKELETON_PLAN）→ 文档生成，阶段化推进、计划先经 review 审批再执行 |
| 7 | `DEVCOLLAB_CODE_METADATA_CACHE_ENABLED` | knowledge-core | **代码元数据缓存**：缓存解析出的代码结构元数据，减少重复解析（宿主进程，本轮实验未纳入测量） |
| 8 | （配套）批量工具纳入 delegation 允许列表 | knowledge-core | 批量工具需加入 agent 委托令牌的 allowed_tools，否则权限拒绝（本轮实验发现并修复） |

## 三、可量化结果（A/B 实测）

**实验条件**：同一仓库 spring-petclinic（固定 commit `88e37c15`）、同一 scope（PROJECT_INITIALIZATION）、
同一指令、同一 agent 流程；仅切换 6 个 agent 侧开关。对照组 39 单元（37 完成），实验组 41 单元（38 完成），
各 3 个失败单元（并发 block 冲突），规模可比。

| 指标 | 优化前（off） | 优化后（on） | 变化 |
|---|---|---|---|
| MCP 会话初始化次数 | 370 | 42 | **-89%** |
| delegation token 交换次数 | 762 | 114 | **-85%** |
| MCP 工具调用总数 | 370 | 285 | **-23%** |
| 单文件读取 `code.read` | 66 | 0（41 次 `read_batch`） | 全部批量收敛 |
| 结构查询 `get_structure` | 123 | 0（36 次 `get_structure_batch`） | 全部批量收敛 |
| 候选查询 `find_candidates` | 61 | 0（41 次 `find_candidates_batch`） | 全部批量收敛 |
| MCP 调用 p95 延迟 | 88.9ms | 75.0ms | **-16%** |
| 重试次数（review 等待退避） | 360 | 36 | -90%（部分受审批提速影响，见诚实边界） |
| 退避累计等待 | 108,000,000ms | 10,800,000ms | -90%（同上） |
| 整个 job 总耗时 | 71.3 min | 19.6 min | -72%（部分受审批提速影响） |

**按单元归一化**（单元数不同，归一后仍显著）：

- 会话初始化 / 单元：9.5 → 1.0
- token 交换 / 单元：19.5 → 2.8
- 工具调用 / 单元：9.5 → 7.0

**新增可观测指标**：`code_read_batch_calls = 41`（批量读替代了 66 次单文件读，同时保留 continuation 计数）。

## 四、各项优化的实现机制

1. **MCP 会话池**（session_initialize 370→42）：同一 delegation 的所有单元共享已建立的 MCP 会话，
   会话按需创建、复用、释放；单元间不再重复执行 `initialize` 握手。42 次残留 = 41 个单元各自的
   delegation 生命周期 + 1 次 discovery，属于必要开销。
2. **token 缓存**（exchange 762→114）：以 delegation_id 为 key 缓存 exchange 结果，TTL 上限 300s，
   `asyncio.Lock` 保护并发；令牌仍由知识核心签发（HMAC256 + issuer/audience 校验），缓存只省往返不省安全。
3. **批量工具**：MCP 服务端新增 4 个批量工具（读取、结构、候选、绑定），agent 侧按开关择优调用；
   单次模型调用覆盖多文件/多查询，往返数按批大小收敛。delegation 允许列表需同步加入批量工具名。
4. **增量读取**：`list_files` 契约透传 `blobSha`，agent 侧比对 revision 变化，未变化文件跳过读取。
5. **分阶段流水线**：发现 → 骨架计划 → 文档生成三段式；骨架计划作为 document-change-requests 提交，
   由 workspace 管理员审批通过（apply）后才进入生成阶段，避免无计划的盲目生成。
6. **代码元数据缓存**（knowledge-core，宿主进程）：解析结果缓存，减少重复 AST 解析（未纳入本次测量）。

## 五、实验方法论（可信度设计）

- **A/B 对照**：同仓库、同 commit、同 scope、同指令；仅切换开关组
- **指标采集**：agent 侧 perf 指标按 job_id 过滤，含 MCP 调用次数/延迟分位数（p50/p95/p99）、
  会话初始化、token 交换、重试与退避，以及批量工具专属计数器
- **失败单元对称**：两轮各 3 个失败单元（并发 409 冲突），验证了两轮工作负载可比
- **开关默认关闭**：不改变既有行为路径，「只加不改」，任何优化都可独立回退验证

## 六、诚实边界（写简历时注意，不要过度宣称）

- **重试/退避/总耗时的下降，主要混杂因素是审批提速**：优化前 review 由人工分批审批（慢），
  优化后实验脚本自动每 60 秒审批一次。这部分数字（-90%/-72%）**不能全部归功于优化开关**。
- 可以放心归因于优化的：**会话初始化 -89%、token 交换 -85%、工具调用 -23%、p95 延迟 -16%**。
- 优化组有副作用：`list_files` 从 1 次增至 42 次（分阶段流水线每单元一次），净收益仍为正（总调用 -23%）。
- 第 7 项（代码元数据缓存）本轮未纳入测量，不要写进结果数据。
- 推荐简历措辞：*「为 AI 工程文档生成管线设计并落地 6 项性能优化（会话池、token 缓存、批量工具、增量读取、分阶段流水线），A/B 实测：MCP 会话初始化 -89%、授权交换 -85%、工具调用 -23%、p95 延迟 -16%」*。

## 七、过程中发现并修复的问题（体现工程严谨性）

实验过程中发现并修复 4 个真实缺陷（均为主仓库提交，非实验脚本）：

1. **MCP 工具契约 schema 缺陷**：`list_files` 输出缺 `blobSha` 字段（增量读取的前置依赖）、
   worker 失败处理 SQL 参数类型歧义 —— 修复并保持向后兼容
2. **delegation 允许列表缺批量工具**：开关全开后批量工具被权限层拒绝（`allowed_tools` 未包含
   `read_batch` 等 3 个批量工具）—— 补齐允许列表
3. **构建配置回归**：mcp-server 的 pom `<resources>` 覆盖把默认 `src/main/resources` 弄丢，
   clean 构建后 `application.yml` 缺失、旧默认 JWT secret 残留，导致跨服务令牌校验 401 ——
   修复资源声明，并用「自签令牌 + 阴性对照（错误密钥必 401）」验证链路
4. **跨服务令牌类型收紧导致的链路断裂**：知识核心 HTTP 过滤器（安全收紧提交）只接受用户令牌、
   拒绝 agent delegation 令牌，而 MCP 服务端透传 delegation 令牌调用数据接口 —— 修复过滤器
   接受 delegation 令牌（与 gRPC 侧校验对齐：必须携带 allowed_tools），端到端验证 200

---

*原始实验数据：`tools/perf/off-94060094-metrics.json`、`tools/perf/on-37ee8a44-*-metrics.json`（本地未提交）。*
