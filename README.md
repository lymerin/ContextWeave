# ContextWeave · 工程知识治理与代码-文档一致性平台

> Knowledge × Agent Collaboration — 回到团队知识现场，继续协作。

ContextWeave（前身 DevCollab，BETA）是面向 2～10 人软件开发团队的实时工程知识协作平台：它把需求、架构、接口和代码变更放进同一套权限、版本、评审与证据链中，并以**符号级（AST）绑定**打通代码与文档——每一次代码变更入库，文档漂移自动检测、自动修复；同时内置 **AI 文档撰写 Agent**，把代码"织"成结构化文档，经人工审批后落库。让"文档永远追上代码"不再依赖人的自觉。

当前仓库已经形成可运行的核心闭环：登录 → 工作区与成员权限 → 文档树 → Tiptap Block 编辑 → WebSocket 实时协作 → 评审发布 → Kafka 异步投影 → 搜索、通知与 MinIO 快照；Git 工程知识模块提供供应商无关的仓库、Commit/PR、Diff 与代码路径关联契约，并在此基础上实现了**代码-文档漂移的自动检测与静默自愈**；Agent 模块（DeepSeek）在人工审批门下自动撰写与更新文档。

## 当前能力

- **认证安全**：Spring Security、BCrypt、JWT Access Token（30 分钟）、HttpOnly Refresh Cookie、双提交 CSRF、Origin 校验、登录限流（5 次/15 分钟）。
- **工作区权限**：ADMIN/MEMBER 两种可扩展角色、成员邀请、角色调整和移除；仓库管理仅限 ADMIN。
- **文档治理**：九种文档类型（需求/API/架构/数据库/前端/后端/测试/部署/ADR）、无限嵌套文档树、状态机（DRAFT→IN_REVIEW→PUBLISHED/REJECTED→DEPRECATED）、评审、不可变发布版本（CURRENT/SUPERSEDED）、历史快照、Review Issue。
- **结构化编辑**：Vue 3 + Tiptap 3，段落/标题/代码/待办 Block；稳定 `blockId + version` 乐观锁。
- **实时协作**：独立 Collaboration Gateway、WebSocket 房间、在线成员 Presence、操作逐条 ACK（APPLIED/DUPLICATE/CONFLICT）、Redis Lua 幂等去重、expectedVersion 冲突检测、断线按序列增量 CATCH_UP 恢复。
- **内部 RPC**：独立 Protobuf 契约、Gateway gRPC Client、Core gRPC Server；HTTP 兼容通道暂时保留。
- **异步平台**：Transactional Outbox、Kafka 多 Topic（document/review/notification/git/cache）、独立 Worker、Consumer Inbox 幂等去重、重试与 DLQ。
- **搜索与对象**：PostgreSQL 搜索基线、Elasticsearch 可重建投影、MinIO 发布快照。
- **缓存与协调**：Redis 登录/操作限流、去重、Presence；Caffeine + Redis 多级缓存和 Kafka 跨节点失效。
- **通知**：评审提交与发布通知、未读状态和前端通知中心。
- **Git 工程知识**：仓库异步同步、真实 Commit Diff、Java 符号/文件依赖图、文档/Block 代码路径绑定（FILE/RANGE/SYMBOL 锚点、symbolKey、PRIMARY/SUPPORTING 角色）及变更影响查询。
- **漂移检测与静默自愈**：以 Binding 为中心的符号级重新解析，六级漂移分级（行偏移/符号移动/签名变化/符号删除/文件删除），位置自动校正、失效绑定自动解耦、**签名变更自动改写文档正文**；双分析器符号键格式冲突时整体跳过、绝不误删绑定。
- **变更审查与审计**：Change Request（PENDING/APPLIED/REJECTED/STALE）+ 文档操作/绑定增删/证据（Commit、行号）四要素，clientRequestId 去重；自动修复全程写入变更流水，可事后审查、可回滚。
- **代码-文档联动工作台**：三栏联动（源码 | 代码锚点栏 | 文档），四种模式（联动/代码聚焦/文档聚焦/漂移评审），绑定锚点编号圆点 + 漂移红点提示，任一侧点击双向聚焦跳转；未绑定段落引导"模块检查"。
- **评审工作台**：变更 before/after Diff、可信代码证据（Binding 优先、提交快照回退）、结构完整/代码上下文/版本冲突三项检查、Binding 变更提案（含置信度）、STALE 与冲突时禁止覆盖人工修改。
- **Agent 文档撰写**：DeepSeek 驱动的项目发现 → 骨架规划 → 批次撰写管线（详见下文），骨架 Review 人工审批门，Bounded Repair 修复循环，产出经 Change Request 提交人工审批，**永不自行应用**。
- **MCP 服务**：`devcollab-mcp-server` 提供 10 个工具（代码读取、绑定查询、文档结构、仓库文件/变更、提交文档变更等），供 Agent 按需读写知识库。
- **可观测性**：OpenTelemetry、Prometheus、Loki、Tempo、Grafana、Alloy 本地编排和验收工具。

## Agent 文档撰写（agent-service）

以 DeepSeek 为模型后端、以 Postgres 为任务存储的异步文档撰写管线，覆盖三种触发场景：

| 单元类型 | 触发 | 行为 |
|---|---|---|
| `PROJECT_DISCOVERY` | 登记仓库 | 扫描全部文件 → DeepSeek 规划语义模块 → 物化分析单元，不产文档 |
| `SKELETON_PLAN` | 每模块一个 | 纯程序生成章节骨架 → 提交骨架 Review → 幂等创建批次单元 |
| `SEMANTIC_ANALYSIS` | 批次单元 | 一次会话内撰写文档 + 绑定（槽位清单随单元持久化） |
| `CURRENT_FILE_ANALYSIS` | IDE 打开文件 | 执行时按既有绑定发现目标文档，不预设文档边界 |

**骨架/批次/槽位机制**：骨架是章节树排版契约，槽位（总览/流程/符号速查）带稳定 ID，是批次填充目标与一致性匹配键；批次 1 = 总览+流程，批次 2..N = 每文件符号速查（每批 ≤ 10 符号）。

**人工审批门**：骨架 Review 未 APPLIED 前批次单元不执行（PENDING → 5 分钟退避自动重试，不消耗重试预算；REJECTED/STALE → 优雅结束），避免候选查询不到绑定而重复建文档。所有产出经 Change Request 提交，**永不自行应用**。

**可靠性**：job/unit 两级状态机（QUEUED→CLAIMED→RUNNING→COMPLETED/FAILED/RETRY_WAITING）、租约（FOR UPDATE SKIP LOCKED）+ 心跳续租、仅可重试错误重试（60/300s 退避）、批次/请求幂等键；Bounded Repair 循环（最多 2 轮，校验错误名单回注提示词）穷尽后失败不盲重试。

**模型边界**：骨架、绑定、排版纯程序实现，模型无权修改；模型只负责规划单元、撰写正文与按校验反馈修复。

## 架构

```text
Vue 3 Web（ContextWeave 前端）
  ├─ REST ───────────────> Nginx ──> Knowledge Core ──> PostgreSQL
  └─ WebSocket ──────────> Nginx ──> Collaboration Gateway
                                      └─ gRPC ────────> Knowledge Core

Knowledge Core
  ├─ Redis / Caffeine
  └─ Transactional Outbox ──> Kafka ──> Worker
                                        ├─ Elasticsearch
                                        ├─ Notification projection
                                        └─ MinIO snapshot

Agent 文档撰写
  agent-service (FastAPI) ──> agent-worker（Postgres 认领单元）
      ├─ DeepSeek 规划 / 撰写 / 修复
      ├─ platform_mcp：读写知识库（delegation 令牌转发用户鉴权）
      ├─ 骨架 Review 人工审批门
      └─ 产出 Change Request ──> 人工审批 ──> 落库
```

| 模块 | 职责 |
|---|---|
| `knowledge-core` | 权限、工作区、文档、版本、评审、Change Request、Git 元数据、事务和 Outbox |
| `collaboration-gateway` | WebSocket 连接、房间、Presence、背压和操作路由 |
| `devcollab-grpc-contract` | Gateway 与 Core 的 Protobuf/gRPC 契约 |
| `devcollab-worker` | Kafka 消费、幂等、搜索/通知/对象投影、JGit 仓库同步与 Java 符号解析 |
| `devcollab-mcp-server` | MCP 工具集（代码读取、绑定、文档结构、变更提交等） |
| `agent-service` | FastAPI Agent 服务 + 独立 worker 进程：规划/撰写/修复、骨架与批次、审批门 |
| `web` | Vue 3 文档工作台、代码-文档联动工作台、评审工作台、成员、搜索、通知 |

正式架构边界见 `docs/02-devcollab-system-architecture-v0.3.md`。

## 技术栈

| 层级 | 技术 |
|---|---|
| 前端 | Vue 3、TypeScript、Vite、Pinia、Vue Router、Element Plus、Tiptap 3、Prism.js、Axios |
| Core | Java 21、Spring Boot 3.5、Spring Security、Spring JDBC、Flyway |
| 协作/RPC | Spring WebFlux、WebSocket、gRPC、Protobuf |
| Agent | Python 3、FastAPI、asyncpg、DeepSeek（deepseek-v4）、MCP |
| 数据与中间件 | PostgreSQL 17、Redis 8、Caffeine、Kafka 4、Elasticsearch 8、MinIO |
| 可观测性 | OpenTelemetry、Prometheus、Loki、Tempo、Grafana、Alloy |
| 测试 | JUnit 5、Spring Boot Test、MockMvc、H2、Vitest + Vue Test Utils、Node E2E/基准工具 |

## 本地启动

Windows 下可直接双击根目录的 `start-dev.bat`。脚本会构建前端、启动中间件（PostgreSQL/Redis/Kafka/Elasticsearch/MinIO）与 Core/Worker/Gateway/Agent 服务、启动 Nginx，并在成功后打开 `http://localhost:8088`。重复双击不会重复启动健康服务；上次异常退出留下的陈旧进程状态会自动清理。

停止脚本管理的服务并保留基础设施容器：

```powershell
powershell -ExecutionPolicy Bypass -File tools\local-demo.ps1 stop -KeepInfrastructure
```

以下命令保留用于手动开发和单模块调试。

### 1. 环境

- Java 21
- Node.js
- Python 3.11+
- Docker Desktop
- 仓库内 Maven Wrapper

```powershell
Copy-Item .env.example .env
docker compose up -d
docker exec devcollab-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic devcollab.document.events --partitions 1 --replication-factor 1
docker exec devcollab-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic devcollab.cache.events --partitions 1 --replication-factor 1
docker exec devcollab-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic devcollab.review.events --partitions 1 --replication-factor 1
docker exec devcollab-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic devcollab.notification.events --partitions 1 --replication-factor 1
docker exec devcollab-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic devcollab.git.events --partitions 1 --replication-factor 1
docker exec devcollab-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic devcollab.dead-letter --partitions 1 --replication-factor 1
```

### 2. Core

```powershell
.\mvnw.cmd -pl knowledge-core -am spring-boot:run
```

默认 HTTP `8080`，gRPC 端口由 `knowledge-core/src/main/resources/application.yml` 配置。

### 3. Worker

```powershell
.\mvnw.cmd -pl devcollab-worker -am spring-boot:run
```

### 4. Collaboration Gateway

```powershell
.\mvnw.cmd -pl collaboration-gateway -am spring-boot:run
```

### 5. Agent 服务（可选，需配置 DeepSeek API Key）

```powershell
cd agent-service
pip install -e .
uvicorn app.main:app --port 8092
python app/worker.py
```

### 6. Web

```powershell
cd web
npm.cmd install
npm.cmd run dev
```

Vite 本地端口、Core 代理和 Gateway 代理可通过 `web/.env.example` 中的变量覆盖。

## 验证

```powershell
# 全量 Java Reactor
.\mvnw.cmd test

# 前端类型检查、单元测试和生产构建
cd web
npm.cmd run typecheck
npm.cmd run test
npm.cmd run build
```

`tools/` 包含 Kafka、Worker、Gateway、Nginx、Agent、可观测性和性能基准的可重复验收工具。正式验收口径见 `docs/03-devcollab-architecture-verification-v0.1.md` 与 `docs/11-devcollab-editor-verification-v0.1.md`。

## Git 工程知识边界

当前 Git 模块已经支持公开 GitHub 仓库的异步克隆、同步、文件树、Git Log 和安全删除，不保存 Git Token，也不会在 Core 请求线程执行 `git clone`：

```text
管理员登记公开 GitHub 仓库
→ Core 同事务写仓库元数据与 Outbox
→ Kafka Git Topic 驱动 Worker 使用 JGit 克隆到 .data
→ Worker 投影文件树、Author/Committer、真实 Diff、Java 符号与显式内部依赖
→ 成员将文档或 Block 关联到精确路径、目录/** 或 **/*.扩展名
→ 变更查询返回受影响文档与命中路径
```

文本 Diff 提供真实新增/删除行数与受控长度 Patch；二进制变更单独标记，避免伪造 `+0/-0`。真实链路可用 `node tools/e2e-git-repository-check.mjs` 验收。

**漂移自愈闭环**：新 Commit 入库 → 符号表重建 → 以 Binding 为"旧状态"、符号表为"新状态"逐条重新解析 → 六级分级判定 → 静默修复（变更流水留痕，可事后审查与回滚）。格式不兼容（如 Python AST 与 JavaParser 符号键冲突）时整体跳过，绝不以误删绑定为代价做自动化。

## 尚未完成

- 私有 GitHub/GitLab 凭证、Webhook 与增量同步 Adapter；
- 漂移修复记录的事后审查前端（后端流水与接口已就绪）；
- Agent Review 的 RAG、LLM、Evidence Verifier 和评测闭环（设计见 `docs/07-devcollab-agent-rag-architecture-v0.1.md`）；
- 生产 HTTPS、秘密管理、CI/CD 和多节点部署验证。

## 文档治理

项目文档统一位于 `docs/`，入口为 `docs/00-devcollab-document-index.md`。`00–89` 是应提交的团队基线；`90–99` 是由 `.gitignore` 精确忽略的本地学习和面试材料。
