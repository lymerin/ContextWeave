# ContextWeave 结构化代码单元与跨文件 Workflow 绑定 V1

| 项目 | 内容 |
|---|---|
| 文档状态 | 施工前设计基线 |
| 审计基线 | `main@0973822` |
| 审计日期 | 2026-07-30 |
| 本轮范围 | 只读源码审计与下一轮施工设计 |
| 目标语言 | Python、Java |
| 非目标语言 | TypeScript、JavaScript、Vue、SQL 继续使用现有文件候选 |

本文只描述已经从当前仓库验证的生产链路，以及下一轮应插入的内部结构。本文不实现
`CodeAtom`、`CodeEdge`、`WorkflowCandidate` 或 `DocumentBlockPlan`，不修改 Prompt、
Review、Binding、数据库和前端。

## 1. 问题陈述

当前真实错误案例是：

- 代码主关联落在 `agent-review-service/app/rules.py` 的
  `review_document()`（L35–105）；
- 文档 Block 却解释 `POST /api/v1/agent/review` 的完整请求到响应流程；
- Block 还包含请求 Schema、Pydantic 转换、领域对象、规则、响应转换、状态码和认证等
  多种职责。

这不是单纯的“模型不够强”，而是当前程序只向模型提供了两类不对称对象：

1. 项目规划阶段只有文件级元数据和符号名称，没有可遍历的函数/方法关系图；
2. Binding 阶段虽然临时产生符号候选，却没有 Workflow、Block 职责计划和主辅角色。

因此模型只能在“一个过宽的文档 Block”和“若干互不相连的代码候选”之间选择语义最接近
的一项。`review_document()` 正好包含最多审查语义，便被选成主关联；Apply 只是如实保存该
结果，不是首次出错位置。

## 2. 当前真实生产链路

```mermaid
flowchart LR
    A["PROJECT_INITIALIZATION Job"] --> B["ProjectDiscoveryService.execute"]
    B --> C["ProjectIndex（文件级）"]
    C --> D["DeepSeekUnitPlanner.plan"]
    D --> E["UnitPlan"]
    E --> F["materialize_deepseek_units"]
    F --> G["agent_units / unit_files / unit_documents"]
    G --> H["ProjectUnitContextBuilder.build"]
    H --> I["DocumentSyncWorkflow.plan_changes"]
    I --> J["AgentPlan：Document Operations"]
    J --> K["BindingCandidateBuilder.build"]
    K --> L["DeepSeekProvider.plan_block_bindings"]
    L --> M["BindingPlanExpander.expand"]
    M --> N["AgentPlanValidator.validate"]
    N --> O["MCP review.submit_document_change"]
    O --> P["DocumentChangeApplicationService"]
    P --> Q["人工 Apply"]
    Q --> R["DocumentBlock + CodeDocumentBinding"]
    R --> S["CodeWorkbenchView 多关联展示"]
```

### 2.1 符号级调用链清单

| 步骤 | 真实符号 | 输入 → 输出 | 持久化 | 调用方 → 消费方 | AI 可控内容与当前规则 |
|---|---|---|---|---|---|
| 项目发现 | `ProjectDiscoveryService.execute()`，`agent-service/app/runtime/project_discovery.py` | workspace/repository/revision → files、`ProjectFile`、Planner batches、`project_index`、stats | `agent_job_files` 在后续 Repository 调用中保存 | `AgentWorker.run_project_discovery()` → Planner | AI 不参与；路径来自 MCP；按路径排序 |
| 元数据读取 | `ProjectDiscoveryService._load_metadata()` | 最多 100 个 path/批 → 文件元数据 | 文件元数据持久化到 `agent_job_files` | Project discovery → `ProjectIndex` | AI 不参与；revision 必须匹配 |
| 文件元数据解析 | `CodeMetadataInspector.inspect()`，`knowledge-core/src/main/java/com/devcollab/knowledgecore/git/application/CodeMetadataInspector.java` | `GitRepositoryFile` → `FileMetadata` | 不单独持久化 | Core metadata API → Agent | 正则解析；不产生行号、父符号或调用边 |
| ProjectIndex | `ProjectDiscoveryService.execute()` 内 `project_index` 构建 | `ProjectFile` + Binding 摘要 → dict | 不直接持久化；组成字段分别持久化 | discovery → `DeepSeekUnitPlanner` | AI 只能看到截断后的 imports、symbols、routes 和 Binding 摘要 |
| Unit 规划 | `DeepSeekUnitPlanner.plan()` | ProjectIndex/批次 → `UnitPlan` | Plan 本身不保存；物化结果保存 | Worker → `UnitPlanValidator` | AI 决定 Unit 名称、kind、primary/supporting 文件、相关文档；只能引用现有路径/文档 |
| Unit 校验 | `UnitPlanValidator.validate()` | `UnitPlan` + ProjectIndex → valid plan | 否 | Planner → materializer | 校验数量、未知文件/文档、重复、退化；不校验调用链 |
| Unit 物化 | `materialize_deepseek_units()`，`agent-service/app/runtime/semantic_planner.py` | valid `UnitPlan` → `PlannedSemanticUnit[]` | `agent_units`、`agent_unit_files`、`agent_unit_documents` | Worker → `PostgresAgentJobRepository.complete_project_discovery()` | 不再推断分组；稳定排序和 UUID5 |
| Unit 上下文 | `ProjectUnitContextBuilder.build()` | Unit 文件/文档 → `AgentState` | 否 | semantic Worker → Document workflow | 读取固定 revision 源码、Java symbols、正式 Binding 和文档结构 |
| 模型上下文 | `build_bundle()` + `build_model_context()` | `AgentState` → 受限 dict | 否 | Context builder → document model | 路径、源码、现有 Binding/Block 由程序给出；模型不能改来源对象 |
| 文档规划 | `DocumentSyncWorkflow.plan_changes()` | model context → `AgentPlan` | 否 | Worker → `AgentPlanValidator` | AI 自由生成标题、Block 数量、正文、Operation；本 pass 必须留空 Binding |
| 文档校验 | `AgentPlanValidator.validate()` | `AgentPlan` + model context → valid plan | 否 | document pass/repair → Binding pass | 校验目标、版本、证据、正文质量；当前缺少 Workflow/BlockPlan 约束 |
| 代码候选 | `BindingCandidateBuilder.build()` | model context + document plan → `CodeCandidate[]` | 否 | Binding pass → DeepSeek | 程序生成 path/symbol/range；总数 40、preview 600 字符 |
| 文档候选 | `BindingCandidateBuilder._document_candidates()` | 现有 Block + 本次 Operations → `DocumentAnchorCandidate[]` | 否 | Binding pass → DeepSeek | 程序生成真实 document/block 或 client operation 引用；总数 40 |
| Binding 选择 | `DeepSeekProvider.plan_block_bindings()` | 两组候选 → `BindingPlan` | 否 | Workflow → Expander | AI 只能返回两个候选 ID、reason、confidence；不能返回 path/UUID/行号 |
| Binding 展开 | `BindingPlanExpander.expand()` | selections + candidate maps → `BindingProposal[]` | 否 | Binding pass → Validator | 程序复制候选的 filePath/symbol/range/document/block；按代码路径、范围、Block 顺序排序 |
| Review 构建 | `DocumentChangeApplicationService.create()` 及 `buildBindingProposal()` | MCP command → ChangeRequest/Operation/BindingProposal/Evidence | PostgreSQL | MCP submit tool → Review UI | Core 重新校验 workspace、目标、路径、Anchor、sequence |
| Review Apply | `DocumentChangeApplicationService.apply()`、`applyOperation()`、`applyBindingProposal()` | PENDING request → APPLIED/STALE | PostgreSQL 单事务 | 管理员审批 → Document/Binding repositories | AI 不参与；文档与 Binding 在同事务应用；精确重复写幂等 |
| Block 落库 | `DocumentBlockApplicationService` + `DocumentBlockContentCodec` | ADD/UPDATE content → `DocumentBlock` | `document_blocks` | Apply → 文档工作台 | Markdown 在 Core 中由 `MarkdownToTiptapConverter` 转成 Tiptap JSON |
| Binding 落库 | `GitKnowledgeApplicationService.createBinding()` | `CreateCodeBindingCommand` → `CodeDocumentBinding` | `code_document_bindings` | Apply → Binding 查询 | 支持 FILE/SYMBOL/RANGE；没有主辅角色和语义顺序 |
| 前端展示 | `buildBindingFixture()` / `sortBindings()`，`web/src/utils/linkedWorkbenchBindings.ts` | 正式 Binding API → Anchor/Link | 否 | `web/src/views/CodeWorkbenchView.vue` → Code/Rail/Document | 固定 `relationType=DESCRIBES`；按 revision、精度、行号、Block sortOrder、ID 排序 |

## 3. 当前数据结构

| 结构 | 当前真实字段 | 当前缺口 |
|---|---|---|
| `ProjectFile` | id、file_path、language、size、package/module/layer、role/import/export/top-level symbols | 无符号行号、父子关系、decorator、调用边 |
| `UnitPlanItem` | name、kind、summary、primaryFiles、supportingFiles、relatedDocumentIds、groupingEvidence | Unit 是文件集合，不是可执行 Workflow |
| `PlannedSemanticUnit` | semantic key/kind、目录、语言、大小、files、documents | 不含 Atom、Edge、Workflow |
| `CodeCandidate` | candidateId、repository/revision/path、anchorKind、symbolKey、range、language、displayName、preview/hash | candidateId 每次随机；没有 parent、decorator、signature、关系 |
| `DocumentAnchorCandidate` | existing/created document、existing/created Block、title/label/preview/schema/sortOrder | 没有 Block purpose、target kind、允许声明 |
| `BindingSelection` | codeCandidateId、documentAnchorCandidateId、reason、confidence | 没有 PRIMARY/SUPPORTING 和 ordinal |
| `DocumentOperation` | 4 种 operation、document/block 引用、版本、标题/类型/正文 | Block 边界完全由模型决定；没有 `blockKey`/purpose |
| `CodeDocumentBinding` | workspace/repository/document/block、path、revision、FILE/SYMBOL/RANGE、symbol/range | 能多绑定；没有 role、relationship、ordinal、metadata |
| `DocumentBlock` | document、type、plainText、Tiptap JSON、sortOrder、version | 顶层有序 Block；没有 purpose/kind/evidence 字段 |
| 前端 `CodeDocumentLink` | codeAnchorId、document/block、relationType、精确位置 | relationType 目前由前端固定为 `DESCRIBES` |

## 4. 错误案例的首次成因

### 4.1 首次错误位置

首次粒度错误发生在 `DocumentSyncWorkflow.plan_changes()`：

- 输入只有 Unit 中若干文件的源码及有限 symbol 目录；
- `AgentPlan` 允许模型自由决定 ADD_BLOCK 数量和每块职责；
- 没有程序生成的 `DocumentBlockPlan` 规定“接口、请求、转换、规则、响应”必须分块。

当前 Prompt 已经要求“每个主要符号独立 Block”，但这不是可执行结构约束。更关键的是，
`ProjectUnitContextBuilder` 的 `symbols` 来自 Core 的 Java Code Graph；Python 文件没有对应
持久化图，因此 `AgentPlanValidator._major_symbol_count()` 对该 Python 案例可能得到 0，
`GIANT_DOCUMENT_BLOCK` 不会触发。

### 4.2 Binding 阶段为何放大问题

`BindingCandidateBuilder._python_candidates()` 可以找到 `review_document()`，但：

- 候选之间没有 CALLS/USES_SCHEMA/RETURNS 等边；
- 没有“本 Block 是 WORKFLOW”这一目标类型；
- 没有主 Anchor 与 supporting Anchor；
- `review_document()` 的 71 行 preview 与宽泛接口文档具有最高词义重合。

因此 Binding 模型并非自由编造了行号，而是在错误粒度的合法候选中选了最接近项。

### 4.3 后续阶段责任

- `BindingPlanExpander` 正确复制候选，未制造错误路径；
- `AgentPlanValidator` 能检查 ID、归属、版本和重复，但不能检查职责层级；
- `DocumentChangeApplicationService.apply()` 正确原子应用，不应承担语义纠错；
- 前端只是按正式 Binding 展示，不能在展示层猜测缺失关系。

结论：**结构缺失是根因，Prompt 是次因，换模型不能补出程序没有提供的 Workflow 和边。**

## 5. Python 索引现状

### 5.1 两条不相同的 Python 识别链

1. `CodeMetadataInspector.inspectPython()` 使用正则：
   - 识别 import/from import；
   - 识别带可选 `async` 的 class/def 名称；
   - 通过通用正则提取少量 route hint；
   - 不提供行号、qualifiedName、parent、decorator 或调用。
2. `BindingCandidateBuilder._python_candidates()` 使用标准库 `ast`：
   - 解析模块顶层 `FunctionDef`、`AsyncFunctionDef`、`ClassDef`；
   - 解析 class 直接子级 method；
   - `lineno` 和 `end_lineno` 形成完整符号范围；
   - symbolKey 为 `PYTHON:{filePath}:{qualifiedName}:{KIND}`；
   - 不生成 import、常量、decorator、route、调用边和子 RANGE。

当前没有第三方 Python parser，也没有 Tree-sitter。

### 5.2 当前实际能力回答

| 问题 | 结论 |
|---|---|
| 是否使用 `ast` | 是，仅 Binding 候选阶段 |
| AST 入口 | `BindingCandidateBuilder._python_candidates()` |
| Symbol 字段 | 通过 `CodeCandidate` 暂存：path、kind、symbolKey、range、displayName、preview/hash |
| 行号 | `node.lineno` / `node.end_lineno` |
| qualifiedName | 顶层为 name，方法为 `Class.method`；不含模块名 |
| parent | 未保存 |
| decorator / FastAPI method/path | AST 能看到但当前未读取；ProjectIndex route 只是正则 hint |
| function call | 未读取 |
| 跨文件 import | ProjectIndex 保存 import 字符串，不解析到目标文件 |
| stable symbol key | 同 path/name/kind 下稳定；candidateId 使用随机 token，只在当前 pass 有效 |
| RANGE 候选 | 没有；模型也不能自由返回范围 |
| 源码 | 文档模型得到受总预算限制的源码；Binding 模型只得到每候选最多 600 字符 preview |
| 数量 | code/document 各最多 40；Unit 文件最多由配置限制为 10 |

大函数只会形成一个大 SYMBOL，是因为 `_python_candidates()` 只遍历 module/class 的声明，
不遍历函数体的结构语句。

### 5.3 基准项目当前可识别符号

由当前 AST 规则可识别：

- `agent-review-service/app/main.py`：
  `health` L16–17、`review` L21–31；
- `agent-review-service/app/schemas.py`：
  `ReviewBlockRequest` L16–28、`ReviewBlockRequest.to_domain` L22–28、
  `ReviewDocumentRequest` L31–47、`ReviewDocumentRequest.to_domain` L39–47、
  `ReviewIssueSuggestionResponse` L50–67、
  `ReviewIssueSuggestionResponse.from_domain` L59–67、
  `ReviewDocumentResponse` L70–74；
- `agent-review-service/app/domain.py`：
  4 个枚举、`DocumentBlock`、`DocumentReviewContext`、
  `DocumentReviewContext.from_blocks`、`ReviewIssueSuggestion`、`ReviewResult`；
- `agent-review-service/app/rules.py`：
  `review_document` L35–105、`_combined_text` L108–109、
  `_meaningful_text` L112–113、`_contains_any` L116–118、
  `_severity_order` L121–127。

这些符号现在是互不相连的 Binding 候选。

## 6. Java Code Graph 现状

`devcollab-worker/src/main/java/com/devcollab/worker/git/JavaCodeGraphAnalyzer.java`
使用 JavaParser，不是正则：

- 类型：class/interface/enum/record/annotation；
- 成员：method/constructor/field；
- 字段：filePath、symbolKey、language、kind、qualified/simple name、signature、
  parentSymbolKey、start/end line；
- 文件边：只保存可解析的内部显式 `IMPORTS`；
- 符号边：只保存 `EXTENDS`、`IMPLEMENTS`；
- 通配 import 不产生边；
- 每个 Java 文件最大 2 MiB；
- 单文件解析失败不会阻断其他文件。

`JavaCodeGraphAnalyzer.typeSymbolKey()` 使用 qualifiedName 与 filePath SHA-256 前 16 位。
成员 key 由父 key 与 signature 组合。同 revision 内稳定；移动/重命名文件后不稳定，这是预期。

结果由 `GitRepositoryProjectionStore.replaceCodeGraph()` 写入：

- `code_symbols`；
- `code_symbol_dependencies`；
- `code_file_dependencies`。

Core 的 `GitKnowledgeApplicationService.getSource()` 返回当前文件 symbols；
`getCodeGraph()` 能返回 symbols、symbol dependencies 和 file dependencies。
但 `ProjectDiscoveryService` 只调用 metadata batch，未把持久化 Code Graph 放进
ProjectIndex；`ProjectUnitContextBuilder` 也只通过 source details 取得 symbols，没有取
dependency endpoint。

当前明确不存在：

- method call 边；
- Java annotation/decorator 字段落入 `CodeSymbol`；
- Controller endpoint 与 method 的绑定；
- DTO 转换边；
- Bean 配置语义；
- Java 调用顺序。

V1 不重写 Java Code Graph。下一轮只适配现有 symbols、IMPORTS、EXTENDS、IMPLEMENTS；
缺失关系保持 UNRESOLVED。

## 7. DocumentBlock 粒度来源

| 粒度 | 当前决定者 | 说明 |
|---|---|---|
| Unit/文档职责 | DeepSeek `UnitPlan` | 一对多文件；不是 Block 计划 |
| 文档标题 | `plan_document_sync()` 输出的 CREATE_DOCUMENT | AI 自由文本，Validator 校验标题/语言/职责 |
| Block 数量与边界 | `plan_document_sync()` 输出的 ADD_BLOCK/UPDATE_BLOCK | AI 自由决定；当前错误首次发生处 |
| Block 顺序 | Operation `sequenceNumber`，Apply 时按该顺序追加 | 第一版 Review 不支持任意插入位置 |
| Markdown 渲染 | `DocumentBlockContentCodec.normalize()` | MARKDOWN 转 Tiptap JSON；只是渲染，不决定语义边界 |
| 数据库存储 | `DocumentBlock` 顶层有序列表 | 一个 Operation 对一个 Block |
| Binding 粒度 | 独立 Binding pass | Block 产生后再选择代码候选 |

因此“Markdown 中有多个二级标题”不等于多个语义 Block。一个 ADD_BLOCK 的 Markdown
即使包含六个标题，数据库仍只有一个 Block，Binding 也只能绑定这个整体 Block。

当前没有 Block purpose、targetKind、allowedClaims 或 workflowCandidateId 字段。
不改数据库也能通过多个 ADD_BLOCK 产生更细 Block；缺的是施工前的确定性 Block 计划。

## 8. Binding Proposal 现状

### 8.1 候选生成

- 每个文件先产生 FILE 候选；
- Python 再产生 AST SYMBOL 候选；
- Java 复用 `codeFiles[].symbols`；
- 不支持的语言只保留 FILE；
- 文档候选包含整篇文档、现有 Block、本次 CREATE_DOCUMENT 和 ADD_BLOCK；
- candidateId 由 `secrets.token_urlsafe()` 生成，只在一次执行中有效；
- code candidates 最终按 filePath、精确范围、symbolKey、candidateId 排序。

### 8.2 模型权限边界

DeepSeek 只能输出：

- `codeCandidateId`；
- `documentAnchorCandidateId`；
- 中文 `reason`；
- 0–1 `confidence`。

DeepSeek 不能自由返回 filePath、revision、UUID、symbolKey、startLine/endLine。
这些字段由 `BindingPlanExpander` 从候选复制。

### 8.3 正式排序

Review proposal 的 sequence 按：

1. filePath；
2. 有范围优先；
3. startLine/endLine；
4. document Block sortOrder；
5. candidate IDs。

Apply 后正式 Binding 查询不保留该 sequence。Core 与前端分别按 revision、Block 是否精确、
Anchor kind、行号/Block sortOrder、标题/ID 做确定性排序。当前没有正式 N/M 或主辅字段。

### 8.4 当前 Validator 的边界

已有：未知候选 ID、重复候选对、目标归属、文件已读、revision/anchor 结构、Block 归属、
Operation 顺序、重复 Binding、证据、文档版本和若干正文质量校验。

缺少：Block 目标层级、Workflow 完整性、主辅角色、跨文件执行顺序、Anchor 过宽、
BlockPlan 覆盖、代码声明白名单。

## 9. 正式模型承载能力

### 9.1 可直接复用

- 一个 `DocumentBlock` 已可拥有多个 `CodeDocumentBinding`；
- 每个 Binding 对应一个 FILE/SYMBOL/RANGE Anchor；
- 一个代码 Anchor 可通过多条 Binding 对应多个 Block；
- Review 已支持在同一事务创建文档、多个 Block 和多个 Binding；
- created document/block client reference 会在 Apply 中映射为真实 UUID；
- `DocumentBlock.version` 与 Review STALE 机制可继续使用；
- 前端已能展示、切换和双向定位多 Anchor。

### 9.2 当前不能表达

- `CodeDocumentBinding` 没有 PRIMARY/SUPPORTING；
- 没有 Workflow 内的 ordinal；
- 没有持久化 relationship，前端固定为 `DESCRIBES`；
- Apply 后 candidateId 只留在 Review proposal，不在正式 Binding；
- `DocumentBlock` 没有 purpose，但 purpose 可保持 Agent 中间态。

### 9.3 数据库结论

`CodeAtom`、`CodeEdge`、`WorkflowCandidate`、`DocumentBlockPlan` V1 都可以是固定 revision
下的 Agent 运行时对象，不新增表。

若 V1 只要求“一个 Block 正确关联多个 Anchor”，**不需要数据库迁移**。

若验收还要求刷新后保留“PRIMARY/SUPPORTING + Workflow 顺序”，现有模型确实缺能力。
最小变更不是新表，而是下一版本 Migration 对
`document_change_binding_proposals` 和 `code_document_bindings` 各增加：

- `binding_role VARCHAR(20)`：`PRIMARY` / `SUPPORTING`；
- `binding_ordinal INTEGER`：同一 Block 内从 1 连续递增。

同时扩展现有 Java record、JDBC mapper、API DTO 和前端类型。不要把角色塞进 `reason`，
也不要把 Review 的 sequence 冒充最终 Binding 顺序。

## 10. CodeAtom V1 设计

### 10.1 建议结构

在现有 `agent-service/app/schemas/binding_plans.py` 增加内部模型，不创建数据库表：

| 字段 | 必填 | 来源 |
|---|---|---|
| atomId | 是 | 下述确定性哈希 |
| repositoryId / revision | 是 | Job 固定上下文 |
| filePath / language / kind | 是 | MCP 源码与 parser |
| symbolKey | SYMBOL 必填，RANGE 可空 | Java graph 或 Python 规则 |
| qualifiedName / displayName | 是 | parser |
| signature | 可选 | Java graph；Python 由 AST 参数/返回注解生成 |
| startLine / endLine | 是 | parser 完整节点 |
| parentAtomId | 可选 | class→method、symbol→subrange |
| decorators | Python 可选 | AST decorator 原文的受限规范化值 |
| routeMethod / routePath | route function 可选 | 确切 decorator |
| normalizedCodeHash | 是 | 该 Atom 完整源码规范化后的 SHA-256 |
| metadata | 可选 | 仅程序字段；不接受模型任意扩展 |

`atomId = "atom_" + sha256(repositoryId + NUL + revision + NUL + language + NUL
+ filePath + NUL + kind + NUL + stableLocalKey)[0:32]`。

`stableLocalKey`：

- SYMBOL 使用已有 symbolKey；
- Python 使用 module qualifiedName + node kind；
- RANGE 使用 parent atomId + AST node kind + start/end + normalized hash。

同 revision 内稳定；rename/move/签名变化后不承诺稳定。跨 revision 的迁移属于漂移阶段，
不在 V1。

### 10.2 V1 kind

支持：`MODULE`、`CLASS`、`INTERFACE`、`ENUM`、`RECORD`、`ANNOTATION`、`FUNCTION`、
`ASYNC_FUNCTION`、`METHOD`、`CLASS_METHOD`、`CONSTRUCTOR`、`FIELD`、`RANGE`。

延后：局部变量、lambda、每个常量、表达式、动态生成函数、TypeScript/Vue AST。

### 10.3 现有适配

- `CodeCandidate` 改为由 Atom 投影，而不是再次解析源码；
- FILE 候选仍由程序生成；
- SYMBOL Atom 映射 SYMBOL Anchor；
- 子范围 Atom 映射 RANGE Anchor；
- candidateId 改为基于 atomId 与本次 document anchor 的任务内稳定 ID；
- 正式 `CodeAnchor` 仍由现有 Binding 字段表达。

## 11. 大函数结构化子范围

1. 永远保留完整函数/方法 SYMBOL Atom。
2. 仅当函数大于等于 80 行且至少有 6 个函数体顶层可执行语句时考虑子范围。
3. 子范围只能覆盖完整 AST statement，不切表达式、字符串、decorator 或语法块。
4. 允许：
   - 6–40 行连续语句组；
   - 完整 if/elif/else；
   - 完整 for/while；
   - 完整 try/except/finally；
   - 完整 match/case；
   - 末尾 return/result construction 组。
5. 不允许跨越不相邻语句、只取半个分支、机械按字符/50 行/100 行切割。
6. 单子范围 6–80 行；每函数最多 6 个；Unit 最多 24 个；嵌套深度最多 2。
7. 只有具备独立职责信号（赋值目标、调用集合、完整控制结构或 return 构造）才生成，
   不把每个小 if 变为候选。
8. RANGE atomId 包含 parent atomId、节点种类、范围和 hash。

`review_document()` 为 71 行，低于阈值。该案例不需要强行切子范围；完整函数 SYMBOL 加
`_combined_text`、`_meaningful_text`、`_contains_any`、`_severity_order` 多辅助 Binding
已经足够。规则内三个 if 分支仍属于一个规则函数 Block。

## 12. CodeEdge V1

建议在现有 `agent-service/app/schemas/binding_plans.py` 增加：

- edgeId；
- sourceAtomId；
- targetAtomId（未解析时为空）；
- type；
- sourceLocation；
- resolutionStatus：`RESOLVED` / `PARTIALLY_RESOLVED` / `UNRESOLVED`；
- confidence：V1 只允许静态解析边为 `1.0`，其余为空；
- evidenceKind：`AST` / `JAVA_GRAPH` / `TYPE_ANNOTATION` / `DECORATOR`。

| 边 | Python AST | 现有 Java Graph | V1 |
|---|---|---|---|
| DECLARES | module/class body 可确定 | parentSymbolKey 可确定 | 做 |
| IMPORTS | 本地模块表可解析，外部 unresolved | file dependency 已有 | 做 |
| CALLS | 同模块、显式 import、`self.method` 可部分解析 | 当前没有 | Python 做；Java 不补 |
| INSTANTIATES | 调用目标可确定为 class 时 | 当前没有 | Python 仅 resolved |
| USES_SCHEMA | 参数/返回注解、FastAPI response_model | 当前没有 | Python 做 |
| CONVERTS_TO | `to_domain`/`from_domain` 的 resolved call | 当前没有 | 仅确切调用 |
| RETURNS | 返回注解可解析 | 当前 signature 不含返回类型 | Python 做 |
| HANDLES_ROUTE | FastAPI decorator | route hint 未绑定 method symbol | Python 做 |
| IMPLEMENTS / EXTENDS | Python 基类可部分解析 | 已有 symbol edge | 做 |

禁止 DeepSeek 创建或补全 Edge。动态调用保留原始调用文本和 UNRESOLVED 状态，不伪造
targetAtomId。V1 Edge 不持久化；Java 的现有图继续由 Core 持久化。

## 13. WorkflowCandidate V1

建议在现有 `agent-service/app/runtime/semantic_planner.py` 增加运行时结构：

- workflowCandidateId；
- type；
- entryAtomId；
- steps（atomId、ordinal、edgeFromPrevious、branchLabel）；
- requiredAtomIds；
- unresolvedCalls；
- sourceEvidence；
- resolutionStatus。

### 13.1 生成边界

| 类型 | 入口 | V1 状态 |
|---|---|---|
| HTTP_ENDPOINT_FLOW | 精确 route decorator 对应 function/method | Python 启用；Java 需有 method 级 annotation 后再启用 |
| APPLICATION_SERVICE_FLOW | 明确 service role + 可解析调用入口 | 仅 resolved 子集 |
| EVENT_CONSUMER_FLOW | 精确 Kafka/listener decorator/annotation | Python 可做；现有 Java 数据不足时不生成 |
| BACKGROUND_JOB_FLOW | worker role + 明确 run/execute 入口 | 仅 resolved 子集 |
| DATA_CONVERSION_FLOW | resolved `to_domain` / `from_domain` | 启用 |

规则：

- step 必须引用真实 atomId；
- 最大深度 6、最大步骤 20、每 Unit 最多 12 个 Workflow；
- visited atom 防循环，循环边保留但不再次展开；
- 标准库、第三方框架和仓库外调用停止为 UNRESOLVED；
- getter、日志调用、纯属性访问不进入主步骤；
- 分支保留 branchLabel，但不把所有分支线性伪装成必经步骤；
- 执行顺序来自调用位置和源码行号；无证据时不排序；
- 不持久化 Workflow；由它生成现有多 Binding；
- Workflow Block 的主 Anchor 是 entry route/consumer/service atom，其余步骤为 supporting。

## 14. DocumentBlockPlan V1

在现有 `agent-service/app/schemas/plans.py` 增加 Agent 内部输入模型：

- blockKey；
- title；
- purpose；
- targetKind；
- primaryCandidateIds；
- supportingCandidateIds；
- workflowCandidateId；
- allowedClaims；
- forbiddenClaims；
- teachingFocus；
- expectedRelationship；
- sortOrder。

`targetKind`：`MODULE_OVERVIEW`、`SYMBOL`、`WORKFLOW`、`DATA_CONVERSION`、
`MAINTENANCE`。

约束：

1. SYMBOL Block 只能有一个 primary symbol，可有少量直接依赖 supporting；
2. WORKFLOW Block 的 primary 是入口，required steps 为 supporting；
3. 一个 Block 只承担一个 purpose；接口、请求、转换、规则、响应不能共用一个 Block；
4. `allowedClaims` 由 route、signature、decorator、代码字面量、resolved edge 和现有文档生成；
5. `forbiddenClaims` 至少包含未出现的 auth、状态码、Cookie、网关、数据库、部署断言；
6. DeepSeek 不得修改 candidate ID、blockKey、targetKind、purpose 或 sortOrder；
7. DeepSeek V1 不得自行增加 Block；证据不足返回 `INSUFFICIENT_EVIDENCE`；
8. `blockKey` 映射为稳定 `clientOperationId`，无需修改 Core Operation Schema；
9. Plan 进入模型上下文和 Review 展示摘要，但不持久化为新表；
10. Apply 仍映射到现有 DocumentBlock 和多条 Binding。

针对基准接口，建议拆分：

| Block | targetKind | primary | supporting | 允许声明 |
|---|---|---|---|---|
| 接口职责 | WORKFLOW | `main.review` | request/response schema、conversion、rule | POST path、输入注解、response_model、真实调用顺序 |
| 请求模型 | SYMBOL | `schemas.ReviewDocumentRequest` | `ReviewBlockRequest` | 字段、默认值、blocks、Pydantic 模型 |
| 领域转换 | DATA_CONVERSION | `ReviewDocumentRequest.to_domain` | `ReviewBlockRequest.to_domain`、`DocumentReviewContext.from_blocks`、`DocumentBlock` | 参数映射、Block 转换和 sortOrder 排序 |
| 审查规则 | SYMBOL | `rules.review_document` | 4 个 helper、`ReviewIssueSuggestion`、`ReviewResult` | 三条真实规则、排序和返回结果 |
| 响应转换 | DATA_CONVERSION | `ReviewIssueSuggestionResponse.from_domain` | `ReviewDocumentResponse`、`main.review` L23–30 | suggestion 字段映射和响应组装 |
| 安全/错误 | 不生成 | 无 | 无 | 当前四个文件没有认证依赖、异常映射或显式状态码证据 |

## 15. 确定性 Validator

插入现有 `AgentPlanValidator.validate()` 和 Binding pass 之后，不修改 Apply 语义。

| 错误码 | 输入与判定 | 级别/Repair | 阻断 |
|---|---|---|---|
| PRIMARY_BINDING_LEVEL_MISMATCH | BlockPlan targetKind 与 primary Atom 不匹配；WORKFLOW primary 不是 entry | ERROR；模型只能重选候选一次 | Review |
| DOCUMENT_BLOCK_TOO_BROAD | 一个 operation 对应多个 blockKey，或 SYMBOL Block 覆盖多个互不为 parent/child 的 primary | ERROR；重新生成 Operations | Review |
| UNSUPPORTED_CLAIM | 正文中的 route/path、状态码、认证/Cookie、代码标识符不在 allowedClaims 且未用不确定措辞 | ERROR；重写正文 | Review |
| WORKFLOW_BINDING_INCOMPLETE | WORKFLOW Block 缺 entry primary，或缺 requiredAtomIds 的 supporting Binding | ERROR；补选候选 | Review |
| BINDING_RANGE_TOO_BROAD | SYMBOL/RANGE 超出对应 Atom；RANGE 跨语句边界或超过 80 行 | ERROR；程序候选错误时不可 Repair | Review/Apply 前 |
| UNKNOWN_CANDIDATE_ID | ID 不在本次候选集 | ERROR；Binding Repair 一次 | Review |
| DUPLICATE_BINDING | 同 document/block/atom/role 重复 | ERROR；去重后重验 | Review |
| INVALID_WORKFLOW_ORDER | ordinal 与 resolved CALLS/CONVERTS_TO 拓扑顺序冲突 | ERROR；程序重排；模型不得改 Edge | Review |
| INSUFFICIENT_EVIDENCE | Block 无 primary、必需 Edge unresolved，或源码被截断导致声明无法证明 | ERROR；不生成该 Block，不允许猜测 | Review |

所有 Repair 后重新执行完整 schema、BlockPlan、Binding、Evidence 校验。Apply 继续执行 Core
现有归属、版本和 Anchor 校验；不要把自然语言质量判断下沉到 Apply。

## 16. `POST /api/v1/agent/review` 真实 Fixture

### 16.1 真实执行证据

| 顺序 | Atom | 范围 | 边与证据 | 状态 |
|---|---|---|---|---|
| 1 | `main.review` | `agent-review-service/app/main.py` L21–31 | decorator `app.post('/api/v1/agent/review', response_model=ReviewDocumentResponse)` | RESOLVED |
| 2 | `ReviewDocumentRequest` | `agent-review-service/app/schemas.py` L31–47 | route 参数注解 | RESOLVED |
| 3 | `ReviewDocumentRequest.to_domain` | `agent-review-service/app/schemas.py` L39–47 | `request.to_domain()` | RESOLVED |
| 4 | `DocumentReviewContext.from_blocks` | `agent-review-service/app/domain.py` L59–76 | conversion 中的显式调用 | RESOLVED |
| 5 | `ReviewBlockRequest.to_domain` | `agent-review-service/app/schemas.py` L22–28 | generator `block.to_domain()` | RESOLVED |
| 6 | `rules.review_document` | `agent-review-service/app/rules.py` L35–105 | route 显式调用 | RESOLVED |
| 7 | 4 个 rules helper | `agent-review-service/app/rules.py` L108–127 | `review_document` 内显式调用 | RESOLVED |
| 8 | `ReviewResult` | `agent-review-service/app/domain.py` L90–94 | `review_document` L100 构造返回 | RESOLVED |
| 9 | `ReviewIssueSuggestionResponse.from_domain` | `agent-review-service/app/schemas.py` L59–67 | route list comprehension L27–29 | RESOLVED |
| 10 | `ReviewDocumentResponse` | `agent-review-service/app/schemas.py` L70–74 | response_model 与 route L23 构造 | RESOLVED |

`DocumentReviewContext`、`DocumentBlock`、`ReviewIssueSuggestion` 是上述转换和规则的领域
载体，不应被伪装成独立的线性运行步骤。

### 16.2 建议 WorkflowCandidate JSON

下面的 repository/revision/atomId 在运行时由真实 Job 和确定性公式填充；其余路径、符号、
范围和边均来自上述源码。

```json
{
  "workflowCandidateId": "workflow_http_agent_review",
  "type": "HTTP_ENDPOINT_FLOW",
  "entryAtomId": "atom(main.review)",
  "steps": [
    {"ordinal": 1, "atomId": "atom(main.review)", "edgeFromPrevious": null},
    {"ordinal": 2, "atomId": "atom(ReviewDocumentRequest)", "edgeFromPrevious": "USES_SCHEMA"},
    {"ordinal": 3, "atomId": "atom(ReviewDocumentRequest.to_domain)", "edgeFromPrevious": "CALLS"},
    {"ordinal": 4, "atomId": "atom(DocumentReviewContext.from_blocks)", "edgeFromPrevious": "CONVERTS_TO"},
    {"ordinal": 5, "atomId": "atom(rules.review_document)", "edgeFromPrevious": "CALLS"},
    {"ordinal": 6, "atomId": "atom(ReviewResult)", "edgeFromPrevious": "RETURNS"},
    {"ordinal": 7, "atomId": "atom(ReviewIssueSuggestionResponse.from_domain)", "edgeFromPrevious": "CONVERTS_TO"},
    {"ordinal": 8, "atomId": "atom(ReviewDocumentResponse)", "edgeFromPrevious": "RETURNS"}
  ],
  "requiredAtomIds": [
    "atom(main.review)",
    "atom(ReviewDocumentRequest)",
    "atom(ReviewDocumentRequest.to_domain)",
    "atom(rules.review_document)",
    "atom(ReviewResult)",
    "atom(ReviewDocumentResponse)"
  ],
  "unresolvedCalls": [],
  "resolutionStatus": "RESOLVED",
  "sourceEvidence": [
    "agent-review-service/app/main.py:L20-L30",
    "agent-review-service/app/schemas.py:L16-L74",
    "agent-review-service/app/domain.py:L42-L94",
    "agent-review-service/app/rules.py:L35-L127"
  ]
}
```

注意：`ReviewDocumentResponse.from_domain()` 不存在，不能写入 Workflow。响应由
`main.review` 直接构造；只有 suggestion 使用 `from_domain()`。

## 17. 两阶段施工范围

### Phase 1：结构化基础设施（零 DeepSeek）

- 从固定 revision 源码构建 Python CodeAtom/CodeEdge；
- 把现有 Java CodeSymbol/Dependency 适配为 Atom/Edge；
- 生成受限 WorkflowCandidate；
- 为大函数生成受限 AST 子范围；
- 生成基准 Fixture；
- 设置候选硬上限和 deterministic ordering；
- 不创建文档、Review 或 Binding。

### Phase 2：文档与 Binding（最多一次正常调用 + 一次现有 Repair/Unit）

- 程序生成 DocumentBlockPlan；
- DeepSeek 只写受约束正文并选择候选 ID/role；
- 模型输出枚举：target operation、candidate IDs、PRIMARY/SUPPORTING；
- 自由文本只允许标题、正文、reason、rationale、Evidence 描述；
- repository/revision/path/line/symbol/blockKey/sortOrder/allowedClaims 由程序生成；
- 不足时返回 `INSUFFICIENT_EVIDENCE`；
- Validator 通过后才提交 PENDING Review；
- 不自动批准。

替换点：

- `agent-service/app/prompts/document_sync_v1.md`
  后续从“自由决定 Block”改为“逐个完成给定 BlockPlan”；
- `agent-service/app/prompts/block_binding_v1.md`
  后续增加 role/ordinal，仍保持候选 ID 选择；
- `agent-service/app/prompts/project_unit_planning_v1.md`
  保留，它解决项目职责分组，不承担运行时调用图。

## 18. 逐提交施工计划

所有路径当前均存在；“新增符号”写入现有模块，避免形成新旧两套半成品。

### Commit 1：CodeAtom 基础类型与适配器

- `agent-service/app/schemas/binding_plans.py`：新增内部 `CodeAtom`、`CodeEdge`；
- `agent-service/app/planning/binding_candidates.py`：让 `CodeCandidate` 由 Atom 投影；
- `agent-service/tests/test_block_binding.py`：稳定 ID、Anchor 映射、旧 FILE fallback。

### Commit 2：Python AST Atom 与关系边

- `agent-service/app/planning/binding_candidates.py`：集中 Python AST 遍历、decorator、
  signature、parent、route、resolved call/import；
- `agent-service/app/runtime/project_unit_context.py`：在文档规划前装配 Python atoms/edges；
- `agent-service/tests/test_block_binding.py`：class/function/async/method/route/call/import；
- `agent-review-service/app/main.py`、`agent-review-service/app/schemas.py`、
  `agent-review-service/app/domain.py`、`agent-review-service/app/rules.py`：
  只作为测试 Fixture 读取，不修改。

### Commit 3：Java 现有符号适配 CodeAtom

- `agent-service/app/runtime/delegated_mcp_client.py`：复用 Core source/code-graph 接口读取
  现有 symbols/dependencies；
- `agent-service/app/runtime/project_unit_context.py`：适配 Atom/Edge；
- `agent-service/tests/test_deepseek_unit_planner.py`：固定 revision、Java parent/import/
  extends/implements；
- `devcollab-worker/src/main/java/com/devcollab/worker/git/JavaCodeGraphAnalyzer.java`
  不改，避免重写现有图。

### Commit 4：WorkflowCandidate 生成

- `agent-service/app/runtime/semantic_planner.py`：新增运行时 WorkflowCandidate 与有界遍历；
- `agent-service/app/runtime/project_unit_context.py`：把 Workflow 放入 context bundle；
- `agent-service/app/context/builder.py`、
  `agent-service/app/planning/context_serializer.py`：安全序列化；
- `agent-service/tests/test_planning.py`：真实 `POST /agent/review` Fixture、循环/动态调用降级。

### Commit 5：DocumentBlockPlan

- `agent-service/app/schemas/plans.py`：新增内部 `DocumentBlockPlan`；
- `agent-service/app/graph/document_sync_workflow.py`：先构建 BlockPlan，再调用 document pass；
- `agent-service/app/planning/context_serializer.py`：只向模型暴露计划和允许声明；
- `agent-service/tests/test_document_authoring.py`：一职责一 Block、缺证据不生成。

### Commit 6：受约束 DeepSeek Schema 与 Prompt

- `agent-service/app/schemas/plans.py`：Operation 与 blockKey/clientOperationId 对齐规则；
- `agent-service/app/providers/deepseek.py`：发送 BlockPlan；
- `agent-service/app/prompts/document_sync_v1.md`、
  `agent-service/app/prompts/block_binding_v1.md`：候选 ID、role/ordinal、禁止增加 Block；
- `agent-service/tests/test_document_authoring.py`、
  `agent-service/tests/test_block_binding.py`：结构化响应与 Repair。

### Commit 7：确定性 Validator

- `agent-service/app/planning/validator.py`：新增九类错误码；
- `agent-service/app/planning/binding_candidates.py`：Workflow 完整性和 Anchor 边界；
- `agent-service/tests/test_planning.py`、
  `agent-service/tests/test_block_binding.py`：每个错误码一个 Fixture。

### Commit 8：Review 集成与真实验证

- 若批准持久化角色：新增当前下一个 Knowledge Core Migration，并修改现有
  `DocumentChangeModel`、`DocumentChangeApplicationService`、`CodeDocumentBinding`、
  `JdbcGitKnowledgeRepository`、相关 API DTO；
- `knowledge-core/src/test/java/com/devcollab/knowledgecore/documentchange/api/PreciseBindingProposalIntegrationTests.java`：
  多文件、主辅顺序、Apply 幂等与回滚；
- `web/src/api/git.ts`、`web/src/utils/linkedWorkbenchBindings.ts`：消费 role/ordinal；
- `web/src/utils/linkedWorkbenchBindings.spec.ts`、
  `web/src/views/CodeWorkbenchBindings.spec.ts`：主辅顺序和 A→B→A；
- 最后只对基准 Unit 调用一次真实 DeepSeek，最多使用一次现有 Repair。

每个提交须独立通过受影响测试；不得以旧规则 Unit 或 Mock Binding 作为真实验收。

## 19. 测试矩阵

| 层 | 必测内容 | 现有承载测试 |
|---|---|---|
| Atom | Python/Java 字段、stable ID、parent、range、hash | `agent-service/tests/test_block_binding.py` |
| Python Edge | route、schema、conversion、call、unresolved、循环 | `agent-service/tests/test_planning.py` |
| Java Adapter | symbols、parent、IMPORTS、EXTENDS、IMPLEMENTS | `agent-service/tests/test_deepseek_unit_planner.py`；Worker 现有 analyzer test 保持 |
| Workflow | 5 类入口的支持/降级、深度/步骤上限 | `agent-service/tests/test_planning.py` |
| Subrange | 完整 AST、阈值、最大数、不机械切割 | `agent-service/tests/test_block_binding.py` |
| BlockPlan | 目的、主辅、allowed/forbidden claims、拆块 | `agent-service/tests/test_document_authoring.py` |
| Validator | 九个错误码、一次 Repair、重验 | `agent-service/tests/test_planning.py`、`agent-service/tests/test_block_binding.py` |
| Review Apply | 两文档、多 Block、多文件、角色顺序、原子回滚 | `knowledge-core/src/test/java/com/devcollab/knowledgecore/documentchange/api/PreciseBindingProposalIntegrationTests.java` |
| 前端 | 正式顺序、无 Binding 空态、双向定位 | `web/src/utils/linkedWorkbenchBindings.spec.ts`、`web/src/views/CodeWorkbenchBindings.spec.ts` |
| 真实 Fixture | `POST /api/v1/agent/review` 拆成 5 个职责 Block；无安全/状态码幻觉 | 受控一次 E2E |

## 20. 风险、硬上限与回滚

| 风险 | 预防/硬上限 | 安全降级 | 回滚 |
|---|---|---|---|
| Candidate 爆炸 | 每文件 Atom 80、Unit 240；送模型前 code candidate 80 | 只保留 Workflow required + primary，再退回 FILE | 关闭 Atom candidate feature，回现有 40 候选 |
| Prompt 增大 | Unit 仍最多 10 文件；Workflow 12、step 20、BlockPlan 12 | 去除 supporting preview，不截断 primary evidence | 只传 Atom 摘要 |
| Python 动态调用 | 不让模型补边 | UNRESOLVED，禁止相关声明 | 退回 symbol-only |
| Java 图不完整 | 只适配已有三种边 | 不生成 Java Workflow | 保留现有 CodeSymbol 候选 |
| Workflow 循环 | visited set、深度 6 | 记录循环边并停止 | 只绑定 entry |
| 子范围过多 | 每函数 6、Unit 24、深度 2 | 只保留完整函数 | 关闭 RANGE 生成 |
| Block 数增长 | 每 Unit 最多 12，Review 总 Operation 仍 50 | 合并同一 symbol 的短教学点，不合并职责 | 回到 symbol-only Block |
| Review 操作过多 | 沿用 50 上限 | 拆成多个 Unit/Review，不截断 | 不提交超限 Review |
| 旧 Binding | includeLegacy 规则不变 | 旧 FILE 显示 weak | 不迁移旧数据 |
| 前端排序变化 | role/ordinal 之后仍以 bindingId 收尾稳定排序 | 缺字段时沿用现有排序 | 移除新列消费，不改布局 |
| 旧文档 | 不自动拆分和重绑 | 只对新 Review 生效 | 无数据回滚 |
| 内存/耗时 | Atom/Edge 只在单 Unit 构建；不持久化源码 | 超限返回 INSUFFICIENT_EVIDENCE | 关闭 Workflow 生成 |

## 21. 明确非目标

- 不重新运行全仓 Planner；
- 不修改 Project Unit 的业务分组 Prompt；
- 不做 TypeScript/Vue/SQL AST；
- 不做 Tree-sitter、LSP、RAG、Embedding 或多 Agent；
- 不做跨 revision rename/drift 自动迁移；
- 不让模型生成 path、range、symbol 或 Edge；
- 不自动批准 Review；
- 不迁移旧正式文档；
- 不重构 Linked Workbench 布局；
- 不把 Workflow 另建长期权威表。

## 22. 审计结论

下一轮应先在 Agent Unit 上下文中建立可验证的 Atom/Edge/Workflow，再生成 BlockPlan；
不能继续依靠 Prompt 要求模型自行理解跨文件执行链。当前正式 Block/Binding/Review Apply
已经足以承载多 Anchor，基础设施阶段不需要数据库；只有“主辅角色与顺序必须长期保留”
这一产品语义需要一个加法 Migration。
