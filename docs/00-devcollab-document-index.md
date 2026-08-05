# 00 DevCollab 文档索引

## 1. 正式项目文档

| 顺序 | 文档 | 作用 | 当前版本 | 版本库状态 |
|---:|---|---|---|---|
| 01 | `01-devcollab-product-requirements-v0.1.md` | 定义产品目标、范围、功能需求、优先级和验收条件 | V0.1 | 应提交 |
| 02 | `02-devcollab-system-architecture-v0.3.md` | 定义系统边界、服务职责、数据流、一致性、安全及技术基线 | V0.3 | 应提交 |
| 03 | `03-devcollab-architecture-verification-v0.1.md` | 定义架构测试、压测、故障实验和证据要求 | V0.1 | 应提交 |
| 04 | `04-devcollab-frontend-design-v0.2.md` | 定义信息架构、页面布局、关键流程、状态表达及接口能力 | V0.2 | 应提交 |
| 05 | `05-devcollab-document-governance-v0.1.md` | 统一文档目录、编号、命名、提交属性、正文结构和维护流程 | V0.1 | 应提交 |
| 06 | `06-devcollab-authentication-design-v0.4.md` | 定义注册、登录、Token、Cookie、会话、限流、安全及验收 | V0.4 | 应提交 |
| 07 | `07-devcollab-agent-rag-architecture-v0.1.md` | 定义 LangChain/LangGraph、RAG 入库、混合检索、Reviewer、Evidence、安全和评测 | V0.1 | 应提交 |
| 08 | `08-devcollab-observability-design-v0.2.md` | 定义本地指标、日志、链路追踪、Grafana 展示、故障演练与验收口径 | V0.2 | 应提交 |
| 10 | `10-devcollab-structured-block-contract-v0.2.md` | 定义 Tiptap 专用 Node 映射、纯文本投影、Schema 白名单、迁移兼容及跨协议契约 | V0.2 | 应提交 |
| 11 | `11-devcollab-editor-verification-v0.1.md` | 定义四类 Block 浏览器闭环、版本快照与多 Editor 性能基线 | V0.1 | 应提交 |
| 12 | `12-devcollab-git-knowledge-design-v0.4.md` | 定义公开仓库同步、真实 Diff、Java 符号与确定性代码依赖图投影 | V0.4 | 应提交 |
| 13 | `13-devcollab-git-markdown-import-design-v0.1.md` | 定义仓库 Markdown 快照、幂等导入文档树及可伸缩工作区导航 | V0.1 | 应提交 |
| 14 | `14-devcollab-code-document-authoring-design-v0.1.md` | 定义真实代码—文档关系、受控上下文、异步文档提案、Evidence 与人工审阅边界 | V0.1 | Slice 1 与 MCP 阶段一已批准实施；其余阶段仍为设计草案 |
| 15 | `15-devcollab-mcp-context-server-implementation-plan-v0.1.md` | 定义 MCP Context Server 的正式技术选型、边界、可扩展能力模型、第一阶段施工和验收 | V0.1 | 应提交 |
| 16 | `16-devcollab-document-change-review-design-v0.1.md` | 定义结构化文档变更请求、人工评审、Block 版本冲突、原子应用和第三阶段前端工作台 | V0.1 | 待评审，应提交 |

### 设计权威资产

| 目录 | 内容 | 版本库状态 |
|---|---|---|
| `design/code-doc-linked-workbench/` | Code ↔ Doc Linked Workspace 的 HTML 原型、设计约束与应用验收截图 | 应提交 |
| `design/document-change-review/` | 第三阶段文档变更待评审列表、详情、Diff、Evidence 和终态交互原型 | 应提交 |

正式文档的评审顺序为需求、总体架构、验证、前端设计和专项设计。Agent 专项设计属于 V1.1 后置阶段，只有核心平台、Git、搜索与权限基础通过验证后才进入实施评审。

## 2. 本地学习材料

| 顺序 | 文档 | 主题 | 版本 | 版本库状态 |
|---:|---|---|---|---|
| 90 | `90-local-search-outbox-es-learning-v0.1.md` | PostgreSQL 搜索、Transactional Outbox、ES 搜索投影、PG vs ES 基准对比 | V0.1 | 已由 `.gitignore` 忽略 |
| 91 | `91-local-interview-knowledge-map-v0.16.md` | 全栈学习总索引、Java 代码图与当前证据边界 | V0.16 | 已由 `.gitignore` 精确忽略 |
| 92 | `92-local-frontend-technology-adr-v0.9.md` | 仓库树目录优先自然排序、VS Code 式节点对齐与低动画交互验收 | V0.9 | 已由 `.gitignore` 精确忽略 |
| 93 | `93-local-authentication-interview-guide-v0.3.md` | 登录全链路、JWT、Cookie、Origin 白名单与真实排错 | V0.3 | 已由 `.gitignore` 忽略 |
| 94 | `94-local-workspace-document-permission-v0.2.md` | 工作空间、成员权限、文档树管理、RBAC 隔离 | V0.2 | 已由 `.gitignore` 忽略 |
| 95 | `95-local-document-block-learning-guide-v0.7.md` | Tiptap 浏览器闭环、Schema 排错、保存可访问性与性能基线 | V0.7 | 已由 `.gitignore` 忽略 |
| 96 | `96-local-frontend-backend-integration-guide-v0.2.md` | 前后端联调、Refresh 自动续期、CSRF Header、Origin 与代理 | V0.2 | 已由 `.gitignore` 忽略 |
| 97 | `97-local-project-explanation-v0.2.md` | 项目总览、MVP 总结、亮点速查、下一步计划 | V0.2 | 已由 `.gitignore` 忽略 |
| 98 | `98-local-document-lifecycle-review-learning-v0.1.md` | 文档状态机、版本快照、操作时间线、Review Issue | V0.1 | 已由 `.gitignore` 忽略 |
| 99 | `99-local-redis-caffeine-architecture-learning-v0.3.md` | Redis 限流与去重、Caffeine 多级缓存、Kafka 跨节点失效、Redis L2 接入补齐设计与验收口径 | V0.3 | 已由 `.gitignore` 精确忽略 |
| 99 | `99-local-git-knowledge-learning-v0.10.md` | Git 身份、源码投影、Monaco Tabs 高度链与首次渲染排错 | V0.10 | 已由 `.gitignore` 精确忽略 |

每份本地学习文档覆盖一个独立技术主题，按面试 4 维度（技术栈是什么 → 解决了什么问题/带来什么提升 → 业务闭环流程 → 架构设计原因）组织。91 为总索引，收录全部技术栈速查表。

## 3. 文档维护规则

所有 Markdown 文档统一存放在 `docs/`，并执行 `05-devcollab-document-governance-v0.1.md`。文件名前两位数字用于固定阅读顺序；本地文档使用 90–99 编号并同步加入 `.gitignore`。

## 当前有效本地学习入口修正

| 编号 | 文档 | 主题 | 版本 | 版本库状态 |
|---:|---|---|---|---|
| 90 | `90-local-outbox-kafka-minio-learning-v0.7.md` | Transactional Outbox、Kafka Worker、MinIO 文档快照及真实排错复盘 | V0.7 | 已由 `.gitignore` 忽略 |
| 90 | `90-local-architecture-integration-troubleshooting-v0.5.md` | Windows 一键启动、Nginx 统一入口、状态恢复、演示编排与真实故障排查 | V0.5 | 已由 `.gitignore` 精确忽略 |
| 90 | `90-local-collaboration-gateway-learning-v0.4.md` | WebSocket 四类可靠操作、documentSequence 游标分页、断线增量补偿与真实排错 | V0.4 | 已由 `.gitignore` 精确忽略 |
| 90 | `90-local-core-grpc-learning-v0.3.md` | Gateway→Core gRPC 指标、HTTP/gRPC 同语义基准、真实排错与 HTTP Client 删除门槛 | V0.3 | 已由 `.gitignore` 精确忽略 |
| 91 | `91-local-interview-knowledge-map-v0.16.md` | 当前技术栈、Java 代码图、Git 排错与证据边界总索引 | V0.16 | 已由 `.gitignore` 精确忽略 |
| 93 | `93-local-authentication-interview-guide-v0.3.md` | Refresh Session、CSRF、Origin 白名单与真实掉线排错 | V0.3 | 已由 `.gitignore` 精确忽略 |
| 95 | `95-local-document-block-learning-guide-v0.7.md` | Tiptap 浏览器闭环、Schema 排错、版本快照与性能基线 | V0.7 | 已由 `.gitignore` 精确忽略 |
| 96 | `96-local-frontend-backend-integration-guide-v0.2.md` | Router、Axios、Vite/Nginx 代理与会话恢复联调 | V0.2 | 已由 `.gitignore` 精确忽略 |
| 92 | `92-local-frontend-technology-adr-v0.9.md` | 仓库树数据适配、稳定 key、Element Plus Tree 布局与真实浏览器排错 | V0.9 | 已由 `.gitignore` 精确忽略 |
| 99 | `99-local-git-knowledge-learning-v0.10.md` | 公开仓库同步、Monaco Tabs 高度链、Java AST 代码图与排错 | V0.10 | 已由 `.gitignore` 精确忽略 |

> 说明：较早的本地学习材料保留为历史版本；上表“当前有效本地学习入口修正”中的对应主题新版本优先于旧快照。
