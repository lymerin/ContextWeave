# ContextWeave MCP Context Server 第一阶段施工计划

- 文档编号：15
- 文档版本：V0.1
- 文档状态：待实施
- 适用仓库：ContextWeave
- 实施范围：MCP 基础模块、可扩展能力注册、真实工作区上下文读取、真实代码区间读取
- 后续阶段：文档结构与绑定查询、待审核文档变更、LangGraph + DeepSeek Agent
- 编制日期：2026-07-23

---

## 1. 文档目的

本计划用于指导 ContextWeave 建立可长期扩展的 MCP Context Server，而不是实现一次性的 MCP 演示。

第一阶段要打通以下真实链路：

```text
MCP Client
    ↓ Streamable HTTP
devcollab-mcp-server
    ↓ 已有内部接口或最小扩展后的 gRPC/HTTP 合约
Knowledge Core
    ↓
工作区权限、Git 仓库投影、Git 文件内容投影
```

第一阶段完成后，后续可以按同一能力注册机制增加：

```text
devcollab.document.get_structure
devcollab.document.find_candidates
devcollab.binding.list
devcollab.review.submit_document_change
devcollab.code.inspect_security
devcollab.code.inspect_syntax
devcollab.relation.find_api_calls
devcollab.relation.find_message_flow
devcollab.document.detect_drift
```

不得为了新增能力重写 Transport、认证、错误模型、上下文预算或审计链路。

---

## 2. 已确定的技术决策

### 2.1 MCP SDK

采用：

```text
官方 MCP Java SDK 2.0.0
io.modelcontextprotocol.sdk:mcp-bom:2.0.0
io.modelcontextprotocol.sdk:mcp-core
io.modelcontextprotocol.sdk:mcp-json-jackson2
```

理由：

1. 2.0.0 是正式 GA 版本；
2. 对齐 MCP `2025-11-25` 规范；
3. 支持 Tools、Resources、Prompts；
4. 支持同步与异步 Server API；
5. 核心模块提供 Servlet 和 Streamable HTTP，不要求升级到外部 Web 框架；
6. 支持 Jackson 2，适配当前 Spring Boot 3.5.x 技术栈；
7. 支持工具参数校验、结构化输出和能力协商；
8. 后续增加 Tool 或 Resource 不需要更换协议栈。

技术参考：

- https://github.com/modelcontextprotocol/java-sdk/releases/tag/v2.0.0
- https://java.sdk.modelcontextprotocol.io/v2.0.0/
- https://java.sdk.modelcontextprotocol.io/v2.0.0/quickstart/
- https://modelcontextprotocol.io/specification/2025-11-25

### 2.2 不采用 Spring AI 2.x MCP Starter

当前 ContextWeave 使用 Spring Boot 3.5.x。Spring AI 2.0 面向 Spring Boot 4.0/4.1 和 Spring Framework 7。

第一阶段不得为 MCP 功能升级整个项目的 Spring Boot 主版本。

因此采用：

```text
Spring Boot 3.5.x
+ 官方 MCP Java SDK 2.0.0
+ MCP Core Servlet Streamable HTTP Transport
+ Jackson 2
```

以后全项目自然升级到 Spring Boot 4 时，再评估 Spring AI MCP Starter 和注解模型。

### 2.3 服务形态

新增独立可启动模块：

```text
devcollab-mcp-server/
```

它是 MCP 协议网关，不是新的业务数据库服务。

它不得：

- 直接访问 PostgreSQL；
- 直接读取 `.data`；
- 执行 JGit；
- 直接调用 DeepSeek；
- 实现 LangGraph；
- 直接修改正式文档；
- 复制 Knowledge Core 的 Repository；
- 信任前端传入的 userId；
- 建立第二套工作区权限模型。

### 2.4 第一阶段传输与执行模型

```text
传输：Streamable HTTP
端点：/mcp
Server API：同步 API
序列化：Jackson 2
运行时：Java 21
```

同步 API 用于第一阶段的短时只读查询。长任务、并行 Agent 和异步审核不在本阶段。

### 2.5 写入边界

第一阶段全部只读。

后续 Agent 不能直接修改正式文档，只能提交结构化的待审核变更方案，由用户在“待我评审”区域审核。

---

## 3. 可扩展架构

建议目录结构以实际包名为准，但职责必须保持分离：

```text
devcollab-mcp-server/
├── application/
│   ├── WorkspaceContextService
│   └── CodeContextService
├── capability/
│   ├── McpToolContributor
│   ├── McpResourceContributor
│   ├── tool/
│   │   ├── WorkspaceGetContextTool
│   │   └── CodeReadTool
│   └── resource/
├── client/
│   └── KnowledgeCoreContextGateway
├── config/
│   ├── McpServerConfiguration
│   └── McpProperties
├── governance/
│   ├── ContextBudgetPolicy
│   ├── McpAuthorizationPolicy
│   └── McpAuditRecorder
├── security/
│   ├── McpUserIdentity
│   └── McpAuthenticationResolver
├── error/
│   ├── McpDomainException
│   ├── McpErrorCode
│   └── McpErrorMapper
└── transport/
    └── StreamableHttpTransportConfiguration
```

### 3.1 能力贡献接口

不得把所有 Tool 注册写进一个不断膨胀的配置类。

建立类似以下扩展点：

```java
public interface McpToolContributor {
    List<McpServerFeatures.SyncToolSpecification> tools();
}

public interface McpResourceContributor {
    List<McpServerFeatures.SyncResourceSpecification> resources();
}
```

最终注册器收集所有贡献者：

```text
List<McpToolContributor>
    ↓
扁平化 Tool specifications
    ↓
注册到 McpSyncServer
```

后续新增能力时只新增 Contributor，不修改 Transport。

### 3.2 领域服务与协议分离

Tool Handler 只负责：

1. 解析结构化参数；
2. 调用 Application Service；
3. 转换为 MCP structuredContent；
4. 映射结构化错误。

不得在 Tool Handler 中：

- 拼 SQL；
- 读取本地文件；
- 解析 JWT 业务规则；
- 写复杂权限逻辑；
- 直接调用多个底层 Repository；
- 散落字符数和行数限制。

---

## 4. 施工前文档同步

Codex 在修改代码前必须先阅读：

```text
docs/00-devcollab-document-index.md
docs/05-devcollab-document-governance-v0.1.md
docs/01-devcollab-product-requirements-v0.1.md
docs/02-devcollab-system-architecture-v0.3.md
docs/03-devcollab-architecture-verification-v0.1.md
docs/07-devcollab-agent-rag-architecture-v0.1.md
docs/08-devcollab-observability-design-v0.2.md
docs/12-devcollab-git-knowledge-design-v0.4.md
docs/14-devcollab-code-document-authoring-design-v0.1.md
```

实际文件版本可能已经变化，必须以仓库当前文件为准，不得根据本计划虚构文件。

先按照 `05-devcollab-document-governance` 的规则判断：

- 应原地更新；
- 还是新建下一版本文件；
- 是否需要更新文档头部状态；
- 是否需要更新索引版本号。

### 4.1 必须同步的文档内容

#### 00 文档索引

增加本计划：

```text
15-devcollab-mcp-context-server-implementation-plan-v0.1.md
```

说明：

```text
定义 MCP Context Server 的正式技术选型、边界、可扩展能力模型、第一阶段施工和验收。
```

#### 01 产品需求

加入 MCP 的产品定位：

```text
ContextWeave 通过标准 MCP 能力向 Agent 和外部 AI 客户端提供受权限控制的代码、文档和关联上下文。
```

补充边界：

- 第一阶段只读；
- Agent 后续提交待审核方案；
- 不允许 Agent 直接覆盖正式文档；
- MCP 不是聊天机器人页面；
- MCP 不是另一套 Git 文件系统。

#### 02 系统架构

新增：

```text
devcollab-mcp-server
```

明确：

- 服务职责；
- 与 Knowledge Core 的调用关系；
- 信任边界；
- JWT 身份传递；
- Core 二次权限校验；
- 不访问 PostgreSQL 和 `.data`；
- Streamable HTTP `/mcp`；
- 后续 Agent 通过 MCP 调用领域能力。

更新架构图和数据流图。

#### 03 架构验证

新增 MCP 验证项：

- MCP Inspector 连接；
- initialize；
- tools/list；
- tools/call；
- 真实工作区查询；
- 真实代码区间查询；
- 越权拒绝；
- 非法行号；
- 超限与截断；
- Core 不可用；
- 日志不泄露 JWT 或代码全文；
- 原有服务回归。

#### 07 Agent/RAG 架构

将 Agent 的工具入口统一调整为：

```text
LangGraph Agent
    ↓ MCP Client
ContextWeave MCP Server
    ↓
Knowledge Core
```

明确：

- Agent 不直接调用数据库；
- Agent 不直接读取 `.data`；
- DeepSeek 不嵌入 MCP Server；
- MCP 提供能力，LangGraph 定义稳定工作流；
- Agent 只能提交待审核方案。

第一阶段不施工 LangGraph 和 DeepSeek。

#### 08 可观测性设计

增加 MCP 观测字段：

```text
toolName
toolCallId
userId
workspaceId
repositoryId
latencyMs
inputSize
outputSize
truncated
resultStatus
errorCode
```

禁止日志记录：

- JWT；
- Refresh Token；
- 完整代码正文；
- 完整文档正文；
- 密钥与仓库凭证。

#### 12 Git Knowledge 设计

补充：

- MCP 只读取 Worker 已生成的 Git 投影；
- MCP 不直接读取 clone 目录；
- `code.read` 的 commitHash、path、language、内容和截断语义；
- MCP 不改变现有 clone、fetch、scan 流程。

#### 14 Code ↔ Doc Authoring 设计

补充 MCP 能力边界和阶段：

```text
阶段一：workspace.get_context、code.read
阶段二：document.get_structure、document.find_candidates、binding.list
阶段三：review.submit_document_change
阶段四：LangGraph + DeepSeek 单 Agent
```

补充未来审核闭环：

```text
Agent 生成结构化变更计划
→ MCP submit_document_change
→ PENDING_REVIEW
→ 用户审核
→ 事务应用
```

### 4.2 本阶段不需要修改的文档

除非审计发现直接冲突，否则不修改：

- Structured Block Contract；
- Editor Verification；
- Git Markdown Import；
- 本地 90–99 学习文档；
- 已有 HTML 视觉原型。

不得为了“同步文档”进行全文重写或无关格式化。

### 4.3 文档同步提交

文档同步完成并自检后，先单独提交：

```text
文档：同步 MCP 上下文服务设计基线
```

代码施工不得与文档同步混在同一个提交中。

---

## 5. 第一阶段施工范围

### 5.1 本阶段交付

1. `devcollab-mcp-server` 独立模块；
2. 官方 MCP Java SDK 2.0.0；
3. Streamable HTTP `/mcp`；
4. 可扩展 Tool/Resource Contributor 机制；
5. 现有 JWT 身份解析；
6. Knowledge Core 的真实读取链路；
7. `devcollab.workspace.get_context`；
8. `devcollab.code.read`；
9. 上下文预算；
10. 结构化错误；
11. 工具审计日志；
12. 自动化测试；
13. MCP Inspector 真实验收；
14. 启动与操作文档。

### 5.2 明确不施工

本阶段不做：

- LangGraph；
- DeepSeek；
- Agent 页面按钮；
- 多 Agent；
- 文档候选搜索；
- Code ↔ Doc 绑定查询 Tool；
- 待审核数据库表；
- 文档新增或修改；
- 安全审查；
- 语法检查；
- API 调用图；
- 消息流分析；
- 向量数据库；
- Elasticsearch 新索引；
- OAuth；
- GitHub App；
- Spring Boot 4 升级；
- Spring AI 2.x Starter；
- 修改 JGit clone；
- 修改 `.data` 目录结构。

---

## 6. 施工阶段

### 6.1 Phase 0：仓库审计

Codex 必须先输出并写入实施记录：

1. 当前 Git HEAD；
2. `git status -sb`；
3. 父 POM modules；
4. Java 版本；
5. Spring Boot 版本；
6. Jackson 版本；
7. 当前 gRPC Contract；
8. Workspace 查询入口；
9. Git Repository 查询入口；
10. Git 文件内容查询入口；
11. JWT 验证与用户身份模型；
12. 当前服务端口；
13. 本地启动脚本；
14. 当前测试体系；
15. 是否存在未提交用户修改。

不得在审计前虚构类名、接口、端口或数据库表。

#### 决策门

Knowledge Core 调用方式按以下顺序选择：

1. 已有 gRPC 合约能覆盖需求：直接复用；
2. 只需小幅扩展现有 gRPC 合约：做最小合约扩展；
3. gRPC 扩展明显超出本阶段：复用现有受认证 HTTP API；
4. 禁止 MCP Server 直接依赖 Knowledge Core Repository 或数据库实现。

Codex 必须在实施报告中记录选择及理由。

#### 2026-07-23 Phase 0 实施记录

| 审计项 | 仓库真实结果 |
|---|---|
| Git 基线 | `main`，HEAD `a867329` |
| 未提交用户修改 | `web/src/components/workspace/WorkspaceCreateDialog.vue`、`web/src/views/HomeView.vue`、`docs/100-local-transactional-outbox-learning-guide-v0.1.md`、`docs/design/devcollab-workspace-interaction-spec.html`；均不属于 MCP 施工，不修改、不暂存 |
| 父 POM modules | `devcollab-grpc-contract`、`knowledge-core`、`devcollab-worker`、`collaboration-gateway` |
| Java | 编译目标 Java 21；本机运行时 Java 21.0.9 LTS |
| Spring Boot | 父 POM `spring-boot-starter-parent` 3.5.16 |
| Jackson | Spring Boot 依赖管理下 Jackson 2，当前解析到 `jackson-databind` 2.21.4 |
| 当前 gRPC Contract | `KnowledgeCoreCollaborationService`，只覆盖 `VerifyDocumentAccess`、`ApplyDocumentOperation`、`ListDocumentOperations` |
| Workspace 查询 | `GET /api/v1/workspaces/{workspaceId}`，返回真实 `id/name/currentUserRole/createdAt/updatedAt` 并校验成员关系 |
| Git Repository 查询 | `GET /api/v1/workspaces/{workspaceId}/git/repositories`，返回真实仓库同步状态 |
| Git 文件内容查询 | `GET /api/v1/workspaces/{workspaceId}/git/repositories/{repositoryId}/source?path=...`，读取数据库中的 Worker 文件投影并校验成员关系 |
| JWT 与身份 | Auth0 `java-jwt` 4.4.0；`JwtTokenService` 校验 issuer、audience、HMAC256、有效期，可信身份为 `CurrentUser(userId, sessionId, username)` |
| 当前端口 | Core HTTP 8080、Core gRPC 9090、Worker 8082、Gateway 8090、Nginx 8088；MCP 使用独立可配置端口，默认 8091 |
| 本地启动 | `tools/local-demo.ps1` 管理 Core、Worker、Gateway 和 Nginx，现阶段不改该脚本 |
| 测试体系 | JUnit 5、Spring Boot/MockMvc、H2、Testcontainers；Worker/Gateway 单元与集成测试；Web 使用 Vitest，构建执行 Vue TypeScript 检查 |

调用方式选择：第一阶段复用现有受认证 HTTP API，不扩展 gRPC。原因是当前 gRPC 合约只服务实时协作，而 Workspace、Repository 和 Source 三个 HTTP 查询已完整覆盖本阶段真实字段、成员授权和错误体系；为两个只读 Tool 新增一组 gRPC 消息及服务会重复已有契约并扩大改动。MCP Server 使用与 Core 相同配置的 JWT 规则解析可信身份，并把原 Bearer Access Token 转发给 Core；Core 仍通过现有 Spring Security 和应用服务逐次校验工作空间成员关系。MCP Server 不依赖 Core Repository、PostgreSQL 或 `.data`。

### 6.2 Phase 1：Maven 模块与应用骨架

新增：

```text
devcollab-mcp-server/pom.xml
devcollab-mcp-server/src/main/java/.../DevCollabMcpServerApplication.java
devcollab-mcp-server/src/main/resources/application.yml
```

要求：

1. 父 POM加入 module；
2. 父 POM仍然不可作为 Spring Boot 应用启动；
3. MCP 模块可单独启动；
4. 使用当前项目统一 Java 版本；
5. 使用 MCP BOM 2.0.0；
6. 使用 `mcp-core`；
7. 使用 `mcp-json-jackson2`；
8. 不引入 Jackson 3；
9. 不升级 Spring Boot；
10. 不污染其他模块依赖；
11. 不修改现有服务端口；
12. 新端口必须配置化并避免冲突。

建议配置前缀：

```yaml
devcollab:
  mcp:
    endpoint: /mcp
    server-name: devcollab-context-server
    server-version: 0.1.0
    max-code-lines: 400
    max-output-characters: 40000
    allowed-origins:
      - http://localhost
      - http://127.0.0.1
```

具体字段以 SDK 和当前配置风格为准。

### 6.3 Phase 2：Transport 和 MCP Server

使用官方 SDK 提供的 Servlet Streamable HTTP Transport。

不得：

- 手写 JSON-RPC；
- 自己解析 initialize；
- 自己模拟 tools/list；
- 自己实现 session 协议；
- 退回旧 SSE 作为主方案。

MCP Server 必须声明：

- server name；
- server version；
- tools capability；
- 后续可扩展 resources capability。

端点固定为：

```text
/mcp
```

如果 SDK 对 GET、POST、DELETE 的处理方式有明确要求，严格使用 SDK，不自行改写协议语义。

安全基础：

- Host 校验；
- Origin 校验；
- 仅允许配置中的本地开发来源；
- 生产来源必须显式配置；
- 拒绝任意通配符生产配置。

### 6.4 Phase 3：身份与权限

第一阶段沿用 ContextWeave 当前 Access Token：

```http
Authorization: Bearer <access-token>
```

流程：

```text
MCP 请求
→ 解析现有 JWT
→ 得到可信 userId
→ 构造 McpUserIdentity
→ 调用 Knowledge Core
→ Knowledge Core 按 workspace membership 再次校验
```

要求：

1. 不接受请求参数中的 userId；
2. 不允许匿名读取工作区；
3. 不复制一套不同的 Token 规则；
4. 不记录 JWT；
5. 权限失败统一映射为 `PERMISSION_DENIED`；
6. 不因知道 UUID 就允许读取；
7. Tool 内部不得自行绕开 Core 权限。

若当前 JWT 组件无法跨模块复用：

- 提取最小共享认证契约；
- 或在 MCP 模块使用相同算法和配置；
- 不复制硬编码密钥；
- 不把 MCP 加进 `permitAll`。

### 6.5 Phase 4：上下文 Gateway

建立协议无关接口，名称以项目实际风格为准：

```java
public interface WorkspaceContextGateway {
    WorkspaceContext getWorkspaceContext(
        McpUserIdentity identity,
        UUID workspaceId
    );
}

public interface CodeContextGateway {
    CodeRangeContext readCode(
        McpUserIdentity identity,
        ReadCodeQuery query
    );
}
```

Application Service 不依赖 MCP Schema 类型。

推荐领域返回模型：

```text
WorkspaceContext
RepositoryContext
CodeRangeContext
ExistingBindingSummary
```

第一阶段 `CodeRangeContext` 可不返回完整绑定，但数据模型要允许后续扩展。

### 6.6 Phase 5：Tool 1——工作区上下文

Tool 名称：

```text
devcollab.workspace.get_context
```

描述必须明确：

```text
读取当前用户有权访问的一个 ContextWeave 工作区及其 Git 仓库同步概况。
只读，不修改任何数据。
```

输入 Schema：

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": ["workspaceId"],
  "properties": {
    "workspaceId": {
      "type": "string",
      "format": "uuid"
    }
  }
}
```

结构化输出：

```json
{
  "workspaceId": "uuid",
  "name": "devcolab",
  "currentUserRole": "ADMIN",
  "repositories": [
    {
      "repositoryId": "uuid",
      "name": "devcolab",
      "provider": "GITHUB",
      "remoteUrl": "https://github.com/...",
      "defaultBranch": "main",
      "syncStatus": "READY",
      "lastSyncedCommit": "..."
    }
  ]
}
```

输出只使用当前系统真实字段。若字段不存在，不得虚构。

Tool 行为提示：

```text
readOnlyHint = true
destructiveHint = false
idempotentHint = true
openWorldHint = false
```

### 6.7 Phase 6：Tool 2——代码区间读取

Tool 名称：

```text
devcollab.code.read
```

描述必须明确：

```text
从 ContextWeave 已扫描的 Git 仓库投影中读取一个文本代码文件或指定行区间。
只读，不访问本地 clone 目录。
```

输入 Schema：

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": ["workspaceId", "repositoryId", "path"],
  "properties": {
    "workspaceId": {"type": "string", "format": "uuid"},
    "repositoryId": {"type": "string", "format": "uuid"},
    "path": {"type": "string", "minLength": 1, "maxLength": 2048},
    "startLine": {"type": "integer", "minimum": 1},
    "endLine": {"type": "integer", "minimum": 1},
    "includeExistingBindings": {"type": "boolean", "default": false}
  }
}
```

语义：

1. 不传行号：从第一行开始，在预算内返回；
2. 只传一个行号：返回参数错误；
3. `startLine > endLine`：返回参数错误；
4. 请求超过最大 400 行：按配置截断并返回 `truncated=true`；
5. path 必须是仓库相对路径；
6. 禁止绝对路径；
7. 禁止 `..` 路径穿越；
8. 禁止读取二进制文件；
9. 禁止直接读取 `.data`。

结构化输出：

```json
{
  "workspaceId": "uuid",
  "repositoryId": "uuid",
  "path": "knowledge-core/src/main/java/Example.java",
  "commitHash": "...",
  "language": "java",
  "startLine": 1,
  "endLine": 120,
  "totalLines": 286,
  "content": "...",
  "truncated": false,
  "omittedLineCount": 0,
  "existingBindings": []
}
```

只返回真实存在的字段。

Tool 行为提示：

```text
readOnlyHint = true
destructiveHint = false
idempotentHint = true
openWorldHint = false
```

### 6.8 Phase 7：上下文预算

建立集中式：

```text
ContextBudgetPolicy
```

第一阶段默认限制：

```text
maxCodeLines = 400
maxOutputCharacters = 40000
maxPathCharacters = 2048
```

要求：

1. 配置化；
2. Tool 不硬编码；
3. 截断显式返回；
4. 不静默丢内容；
5. UTF-8 字符计数规则固定；
6. 预算命中写审计字段；
7. 不允许通过重复参数绕过。

### 6.9 Phase 8：错误模型

错误码：

```text
INVALID_ARGUMENT
INVALID_LINE_RANGE
INVALID_REPOSITORY_PATH
UNSUPPORTED_FILE_TYPE
WORKSPACE_NOT_FOUND
REPOSITORY_NOT_FOUND
FILE_NOT_FOUND
PERMISSION_DENIED
CONTEXT_LIMIT_EXCEEDED
CORE_UNAVAILABLE
INTERNAL_ERROR
```

错误结构：

```json
{
  "code": "INVALID_LINE_RANGE",
  "message": "startLine 不能大于 endLine",
  "retryable": false,
  "details": {
    "startLine": 80,
    "endLine": 20
  }
}
```

要求：

1. 不把业务错误全部映射为 internal error；
2. 不返回堆栈；
3. 不泄露 SQL、路径和密钥；
4. Core 超时标记 retryable；
5. 权限错误不可重试；
6. 参数错误不可重试；
7. 错误映射有单元测试。

### 6.10 Phase 9：审计与观测

每次 Tool 调用记录：

```text
toolName
toolCallId
userId
workspaceId
repositoryId
latencyMs
inputSize
outputSize
truncated
resultStatus
errorCode
```

禁止记录：

- JWT；
- Authorization Header；
- 代码正文；
- 文档正文；
- Git 凭证；
- DeepSeek Key；
- 数据库密码。

若项目已有 OpenTelemetry：

- 增加 MCP Tool Span；
- Span 名称使用稳定命名；
- 添加低基数属性；
- 不把 filePath 全量作为高基数 Metric 标签。

第一阶段至少提供日志和 Micrometer Timer/Counter；是否接入完整 Trace 以当前基础设施为准。

### 6.11 Phase 10：测试

#### 单元测试

覆盖：

1. Tool Schema；
2. Contributor 注册；
3. UUID 校验；
4. 路径校验；
5. 行区间校验；
6. 上下文截断；
7. 错误映射；
8. 审计脱敏。

#### MCP 协议集成测试

覆盖：

1. initialize 成功；
2. tools/list 返回两个 Tool；
3. Tool 名称稳定；
4. 输入 Schema `additionalProperties=false`；
5. workspace.get_context 调用成功；
6. code.read 调用成功；
7. structuredContent 可解析；
8. 非法输入被拒绝；
9. 未认证请求被拒绝；
10. 会话关闭正常。

#### Core 集成测试

覆盖：

1. 真实工作区返回；
2. 真实仓库返回；
3. 真实代码文件返回；
4. 普通成员只能读有权限工作区；
5. 非成员拒绝；
6. 不存在的工作区；
7. 不存在的仓库；
8. 不存在的文件；
9. 二进制文件；
10. Core 不可用。

#### 回归测试

必须确认：

- Knowledge Core 启动；
- Worker 启动；
- Gateway 启动；
- Web 构建；
- GitHub clone；
- 文件树；
- 工作区删除；
- 工作区重命名；
- 现有 Code ↔ Doc Linked Workbench。

不得为了 MCP 测试清空数据库或重建 volume。

### 6.12 Phase 11：MCP Inspector 验收

使用官方 MCP Inspector 连接：

```text
http://localhost:<mcp-port>/mcp
```

保存证据：

```text
design/mcp-context-server/
├── mcp-inspector-initialize.png
├── mcp-inspector-tools-list.png
├── mcp-workspace-get-context.png
├── mcp-code-read.png
├── mcp-permission-denied.png
└── mcp-invalid-line-range.png
```

截图必须显示真实 Tool 名称和真实业务结果，但不得泄露：

- Access Token；
- Refresh Token；
- 密钥；
- 私有仓库凭证；
- 用户敏感信息。

无法真实连接 MCP Inspector 时，不得宣布第一阶段完成。

---

## 7. 资源与 Prompts 的预留

第一阶段可以暂不发布正式 Resource，但注册架构必须支持 Resource Contributor。

第二阶段计划：

```text
devcollab://workspaces/{workspaceId}
devcollab://workspaces/{workspaceId}/documents/{documentId}
devcollab://workspaces/{workspaceId}/repositories/{repositoryId}/files/{path}
```

第一阶段不实现 Prompts，但不得阻止后续新增：

```text
devcollab.document.create_or_improve
devcollab.document.review_code_change
```

Prompts 只提供工作流模板，不能绕过 Tool 权限。

---

## 8. 后续阶段

### 第二阶段：文档与绑定上下文

增加：

```text
devcollab.document.get_structure
devcollab.document.find_candidates
devcollab.binding.list
```

目标：

```text
读取代码
→ 查询已有 Code ↔ Doc 绑定
→ 查找候选文档
→ 读取文档 Block 结构
→ 判断 CREATE_DOCUMENT / ADD_SECTION / UPDATE_BLOCK / NO_CHANGE
```

第二阶段仍然只读。

### 第三阶段：待审核文档变更

新增：

```text
devcollab.review.submit_document_change
```

Agent 一次提交完整结构化方案：

```text
CREATE_DOCUMENT
CREATE_BLOCK
UPDATE_BLOCK
CREATE_BINDING
```

只创建 `PENDING_REVIEW`，不得直接写正式文档。

审核应用时检查：

```text
sourceCommitHash
expectedDocumentVersion
idempotencyKey
```

### 第四阶段：LangGraph + DeepSeek

采用：

```text
Python
FastAPI
LangGraph
DeepSeek
MCP Client
```

稳定工作流：

```text
用户选择代码
→ load_code
→ load_bindings
→ find_candidate_documents
→ load_document_structure
→ analyze_gap
→ generate_change_plan
→ validate_plan
→ submit_pending_review
→ END
```

单 Agent 完成后再考虑多 Agent 并发。

---

## 9. 提交计划

### 提交 1：文档同步

```text
文档：同步 MCP 上下文服务设计基线
```

### 提交 2：MCP 基础模块

```text
功能：建立 ContextWeave MCP 服务基础模块
```

内容：

- Maven 模块；
- SDK 依赖；
- Transport；
- Server；
- Contributor 扩展点；
- 配置；
- 启动测试。

### 提交 3：真实只读 Tool

```text
功能：开放工作区与代码只读上下文工具
```

内容：

- 身份；
- Core Gateway；
- 两个 Tool；
- 预算；
- 错误；
- 审计；
- 测试；
- Inspector 证据。

每个提交前必须：

```text
git status -sb
git diff --check
```

不得：

- 创建备份分支；
- 创建 Tag；
- 改写历史；
- 修改用户未跟踪文件；
- 混入工作区返回按钮；
- 混入前端 Agent 页面；
- 混入其他产品功能。

---

## 10. 停止条件

出现以下情况立即停止并报告：

1. 必须升级 Spring Boot 4；
2. 必须修改历史 Flyway Migration；
3. 必须删除或重建数据库；
4. 必须删除 Docker volume；
5. 必须改写 Git 历史；
6. 必须让 MCP Server 直接访问数据库；
7. 必须让 MCP Server 读取 `.data`；
8. 当前 SDK 与项目依赖出现无法隔离的冲突；
9. 当前工作区存在无法判断归属的用户修改；
10. MCP Inspector 无法建立真实连接且原因未查明。

不得用危险操作绕过问题。

---

## 11. 第一阶段完成定义

全部满足才算完成：

```text
[ ] 项目文档已同步
[ ] 文档索引已加入本计划
[ ] 文档同步已独立提交
[ ] devcollab-mcp-server 可独立构建
[ ] devcollab-mcp-server 可独立启动
[ ] 使用官方 MCP Java SDK 2.0.0
[ ] 使用 Jackson 2
[ ] 未升级 Spring Boot
[ ] /mcp 可建立 Streamable HTTP 连接
[ ] initialize 成功
[ ] tools/list 返回两个 Tool
[ ] workspace.get_context 返回真实工作区
[ ] code.read 返回真实代码区间
[ ] 权限校验真实有效
[ ] 路径穿越被拒绝
[ ] 非法行区间被拒绝
[ ] 超限结果显式截断
[ ] 错误结构可分类
[ ] 日志不泄露敏感内容
[ ] 自动化测试通过
[ ] MCP Inspector 验收通过
[ ] 原有 Core、Worker、Gateway、Web 不受影响
[ ] GitHub clone 与文件树正常
[ ] git status 干净或只包含明确保留项
[ ] 已创建三个中文提交
```

---

## 12. Codex 最终报告格式

Codex 最终只报告：

1. 审计得到的真实项目基线；
2. 同步了哪些项目文档；
3. 文档版本如何处理；
4. 新增和修改的代码文件；
5. MCP SDK 与依赖；
6. Transport 类型与 `/mcp` 端点；
7. Knowledge Core 调用方式及选择理由；
8. 身份和权限链路；
9. 两个 Tool 的实际 Schema；
10. 上下文预算；
11. 错误模型；
12. 测试结果；
13. MCP Inspector 截图路径；
14. 原功能回归结果；
15. `git status -sb`；
16. 三个中文提交哈希；
17. 尚未施工的第二、三、四阶段内容。

不得把未真实执行的测试写成通过。
