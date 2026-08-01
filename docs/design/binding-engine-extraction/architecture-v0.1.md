> **让文档 Block 能稳定、准确地绑定到真实代码 Anchor，为现有 Agent 生成和后续增量功能提供可靠基础。**

不再建设完整 Code Graph、通用 Workflow、LSP、跨版本漂移或复杂多 Agent。

```markdown
# DevCollab 稳健代码—文档 Binding V1

| 项目 | 内容 |
|---|---|
| 文档状态 | 施工前设计基线 |
| 审计基线 | `main@0973822` |
| 审计日期 | 2026-07-30 |
| 本轮目标 | 修复文档 Block 粒度与代码 Binding 不匹配问题 |
| 目标语言 | Python、Java |
| 重点框架 | Python / FastAPI |
| 非目标语言 | TypeScript、JavaScript、Vue、SQL继续使用现有FILE候选 |
| 核心原则 | 程序生成真实Anchor，模型只选择候选，Core负责校验和事务Apply |

本文仅设计下一轮Binding增强，不建设通用代码智能平台。

本轮不实现：

- 通用跨语言调用图；
- 完整Workflow引擎；
- LSP、Tree-sitter；
- RAG、Embedding；
- 多Agent；
- Git跨Revision漂移；
- 自动修改业务代码；
- 通用插件或Skill系统；
- 旧文档自动迁移。

---

## 1. 问题陈述

当前真实错误案例：

- 文档Block解释`POST /api/v1/agent/review`从请求到响应的完整流程；
- 正式主关联却落在
  `agent-review-service/app/rules.py`的
  `review_document()`（L35–105）；
- 文档同时描述请求Schema、Pydantic转换、领域对象、规则执行、响应转换、认证和状态码等多个职责；
- 实际代码中不存在部分认证和显式状态码证据。

该问题不是Apply、数据库或前端造成的。

首次错误发生在文档生成阶段：

1. 模型自由决定Block数量和职责；
2. 程序没有明确规定每个Block应该解释什么；
3. Binding阶段只能在合法但互不关联的候选中选择；
4. `review_document()`与宽泛文档的词义最接近，因此被错误选为主关联。

当前错误链路：

```text
文档Block职责过宽
→ Binding候选缺少职责约束
→ 模型选择语义最相近的局部函数
→ Expander复制合法Anchor
→ Core正确落库
→ 前端正确展示错误Binding
```

结论：

> 本轮重点不是提高模型自由理解能力，而是由程序明确Block职责、候选范围和主辅关系。

---

## 2. 当前生产链路

```mermaid
flowchart LR
    A["PROJECT_INITIALIZATION Job"] --> B["ProjectDiscoveryService.execute"]
    B --> C["ProjectIndex"]
    C --> D["DeepSeekUnitPlanner.plan"]
    D --> E["UnitPlan"]
    E --> F["materialize_deepseek_units"]
    F --> G["ProjectUnitContextBuilder.build"]
    G --> H["DocumentSyncWorkflow.plan_changes"]
    H --> I["AgentPlan / Document Operations"]
    I --> J["BindingCandidateBuilder.build"]
    J --> K["DeepSeekProvider.plan_block_bindings"]
    K --> L["BindingPlanExpander.expand"]
    L --> M["AgentPlanValidator.validate"]
    M --> N["review.submit_document_change"]
    N --> O["DocumentChangeApplicationService.create"]
    O --> P["人工确认"]
    P --> Q["DocumentChangeApplicationService.apply"]
    Q --> R["DocumentBlock + CodeDocumentBinding"]
    R --> S["CodeWorkbenchView"]
```

### 2.1 当前可直接复用的能力

现有系统已经具备：

- 一个`DocumentBlock`关联多条`CodeDocumentBinding`；
- FILE、SYMBOL、RANGE三类Anchor；
- Binding固定到`repositoryId + revision`；
- Python使用AST生成部分SYMBOL候选；
- Java复用现有Code Graph中的Symbol；
- DeepSeek只能返回候选ID；
- 模型不能生成路径、Symbol、UUID或行号；
- `BindingPlanExpander`从候选复制正式Anchor；
- Core重新校验Workspace、Repository、Revision、Document和Block；
- 文档Operation与Binding Proposal在同一事务Apply；
- Apply支持幂等和STALE检测；
- 前端支持多Binding切换和双向定位。

因此本轮不重写Proposal、Apply、数据库事务和前端工作台。

---

## 3. 本轮目标

Binding V1必须保证以下五点。

### 3.1 Anchor真实

模型不得自行生成：

- repositoryId；
- revision；
- filePath；
- symbolKey；
- startLine；
- endLine；
- documentId；
- blockId。

以上字段全部由程序产生。

### 3.2 Anchor精确

候选优先使用：

```text
SYMBOL
→ 已有合法RANGE
→ FILE降级
```

FILE只用于：

- 不支持的语言；
- 解析失败；
- 文件整体职责文档；
- 无法生成稳定Symbol的情况。

本轮不自动生成函数内部RANGE。

### 3.3 Block职责明确

一个Block只承担一个主要职责，例如：

- 接口入口；
- 请求模型；
- 领域转换；
- 业务规则；
- 响应构造。

模型不得将上述职责全部合并成一个Block。

### 3.4 主辅关系明确

每个需要代码关联的Block必须具有：

- 一个`PRIMARY` Binding；
- 零个或多个`SUPPORTING` Binding。

示例：

```text
接口职责Block
├─ PRIMARY：main.review
├─ SUPPORTING：ReviewDocumentRequest
├─ SUPPORTING：ReviewDocumentRequest.to_domain
├─ SUPPORTING：review_document
└─ SUPPORTING：ReviewDocumentResponse
```

### 3.5 Apply可靠

正式链路保持：

```text
程序生成候选
→ 模型选择候选ID
→ Validator校验
→ Binding Proposal
→ 人工确认
→ Core事务Apply
```

模型不得直接写正式Binding。

---

## 4. 当前主要缺口

### 4.1 Python结构来源不统一

当前存在两套Python识别：

1. `CodeMetadataInspector.inspectPython()`使用正则；
2. `BindingCandidateBuilder._python_candidates()`使用标准库`ast`。

两者输出能力不一致：

| 能力 | 正则Metadata | AST Binding |
|---|---:|---:|
| class/function名称 | 支持 | 支持 |
| 精确行号 | 不支持 | 支持 |
| class→method父子关系 | 不支持 | 临时可推断但未保存 |
| decorator | 不支持 | AST可读但当前未使用 |
| FastAPI method/path | 仅route hint | 当前未提取 |
| signature | 不支持 | 当前未生成 |
| 调用关系 | 不支持 | 不支持 |

本轮应将Python AST提取集中为单一实现，Binding候选直接复用该结果。

### 4.2 FastAPI Route没有正式语义

当前：

```python
@app.post(
    "/api/v1/agent/review",
    response_model=ReviewDocumentResponse
)
async def review(...):
```

只被视为普通函数。

这导致模型无法明确区分：

- HTTP入口；
- 内部规则函数；
- 请求转换函数；
- 响应转换函数。

本轮必须提取：

```text
routeMethod
routePath
responseModel
```

但正式Anchor仍使用现有SYMBOL类型，不新增数据库Anchor类型。

### 4.3 文档Block完全由模型自由拆分

当前`DocumentSyncWorkflow.plan_changes()`允许模型自由决定：

- Block数量；
- 每块标题；
- 每块职责；
- 每块正文。

Prompt中的“每个主要符号独立Block”只是自然语言建议，不是程序约束。

本轮需要在模型调用前生成轻量的`DocumentBlockPlan`。

### 4.4 Binding没有正式主辅语义

当前多条Binding在Apply后都处于平级状态。

系统无法确定：

- 默认应该打开哪条代码；
- 哪条代码代表Block核心职责；
- 哪些只是辅助证据；
- 后续Agent应优先检查哪条Binding。

本轮需要引入：

```text
PRIMARY
SUPPORTING
```

以及同一Block内的稳定顺序。

### 4.5 Validator只校验合法性，不校验职责

现有Validator可以检查：

- 候选ID是否存在；
- 文件是否属于目标Repository；
- Revision是否正确；
- Document和Block是否归属正确；
- Binding是否重复；
- Operation顺序是否合法；
- 文档版本是否过期。

但不能检查：

- 接口Block是否绑定到Route；
- 一个Block是否承担过多职责；
- 主Binding是否与Block类型匹配；
- 必需的辅助Binding是否缺失。

---

## 5. 最小内部数据结构

本轮只新增四个轻量运行时结构。

这些对象默认不新增数据库表。

---

### 5.1 CodeAtom

`CodeAtom`表示程序能够真实定位的代码单元。

```text
CodeAtom
├─ atomId
├─ repositoryId
├─ revision
├─ filePath
├─ language
├─ kind
├─ symbolKey
├─ qualifiedName
├─ displayName
├─ signature
├─ startLine
├─ endLine
├─ parentAtomId
├─ routeMethod
├─ routePath
└─ responseModel
```

建议字段：

| 字段 | 必填 | 说明 |
|---|---:|---|
| atomId | 是 | 同一任务和Revision内稳定 |
| repositoryId | 是 | 当前仓库 |
| revision | 是 | 固定Git Revision |
| filePath | 是 | 仓库相对路径 |
| language | 是 | python/java等 |
| kind | 是 | MODULE、CLASS、FUNCTION等 |
| symbolKey | SYMBOL必填 | 正式Anchor使用的Symbol |
| qualifiedName | 是 | 如`ReviewDocumentRequest.to_domain` |
| displayName | 是 | 前端和Prompt显示 |
| signature | 否 | 参数和返回注解摘要 |
| startLine/endLine | 是 | 完整声明范围 |
| parentAtomId | 否 | class→method |
| routeMethod | 否 | 如POST |
| routePath | 否 | 如`/api/v1/agent/review` |
| responseModel | 否 | FastAPI response_model |

V1支持的`kind`：

```text
MODULE
CLASS
INTERFACE
ENUM
RECORD
ANNOTATION
FUNCTION
ASYNC_FUNCTION
METHOD
CLASS_METHOD
CONSTRUCTOR
FIELD
HTTP_ROUTE
```

暂不支持：

- 局部变量；
- lambda；
- 每个表达式；
- 自动函数内RANGE；
- 动态生成Symbol；
- TypeScript和Vue AST。

---

### 5.2 BindingCandidate

`BindingCandidate`是CodeAtom面向本次模型任务的只读投影。

```text
BindingCandidate
├─ candidateId
├─ atomId
├─ anchorKind
├─ displayName
├─ filePath
├─ symbolKey
├─ startLine
├─ endLine
├─ language
├─ atomKind
├─ routeMethod
├─ routePath
└─ preview
```

约束：

- `candidateId`由程序确定性生成；
- candidateId仅在当前任务或Repair中使用；
- 模型只能返回candidateId；
- FILE候选继续作为降级路径；
- preview只提供必要代码摘要；
- 正式字段由Expander从候选复制。

建议ID：

```text
candidateId =
"candidate_" + sha256(
    taskId + "\0" +
    repositoryId + "\0" +
    revision + "\0" +
    atomId + "\0" +
    anchorKind
)[0:24]
```

无需跨Revision稳定。

---

### 5.3 DocumentBlockPlan

`DocumentBlockPlan`由程序生成，限制模型创建的Block。

```text
DocumentBlockPlan
├─ blockKey
├─ title
├─ purpose
├─ targetKind
├─ primaryCandidateIds
├─ supportingCandidateIds
├─ requiredCandidateIds
├─ allowedClaims
├─ forbiddenClaims
└─ sortOrder
```

`targetKind`只支持：

```text
MODULE_OVERVIEW
HTTP_ENDPOINT
SYMBOL
DATA_CONVERSION
BUSINESS_RULE
RESPONSE_CONSTRUCTION
```

约束：

1. 一个Plan对应一个DocumentBlock；
2. 每个Plan只能有一个主要职责；
3. 模型不得新增、删除或合并Plan；
4. 模型不得修改blockKey和sortOrder；
5. 证据不足时返回`INSUFFICIENT_EVIDENCE`；
6. `blockKey`映射为稳定`clientOperationId`；
7. Plan只作为Agent运行时对象，不新增DocumentBlock字段。

---

### 5.4 BindingSelection

模型输出：

```text
BindingSelection
├─ blockKey
├─ codeCandidateId
├─ role
├─ ordinal
├─ reason
└─ confidence
```

其中：

```text
role = PRIMARY | SUPPORTING
```

约束：

- 每个Block恰好一个PRIMARY；
- PRIMARY ordinal固定为1；
- SUPPORTING从2开始连续排序；
- 模型不得返回候选外ID；
- 模型不得返回路径、行号或Symbol；
- 同一Block和候选不得重复。

---

## 6. Python AST提取

### 6.1 统一提取入口

将当前`BindingCandidateBuilder._python_candidates()`中的AST逻辑抽取为统一组件，例如：

```text
PythonCodeAtomExtractor
```

该组件负责：

- 解析Python源码；
- 生成CodeAtom；
- 生成稳定Symbol Key；
- 生成父子关系；
- 提取FastAPI Route信息；
- 生成BindingCandidate所需数据。

`BindingCandidateBuilder`不再自行重复遍历AST，只消费CodeAtom。

### 6.2 必须识别的声明

Python V1识别：

- module；
- class；
- 顶层function；
- 顶层async function；
- class直接子级method；
- classmethod；
- staticmethod；
- Pydantic class；
- FastAPI route function。

### 6.3 qualifiedName规则

```text
顶层函数：
review

类：
ReviewDocumentRequest

方法：
ReviewDocumentRequest.to_domain
```

暂时不强制包含Python模块名，保持与现有Symbol Key兼容。

### 6.4 FastAPI Decorator规则

只解析明确形式：

```python
@app.get(...)
@app.post(...)
@app.put(...)
@app.patch(...)
@app.delete(...)
@app.api_route(...)
```

提取：

- HTTP method；
- 字面量route path；
- `response_model`中的名称。

无法静态解析时：

- 保留普通FUNCTION/ASYNC_FUNCTION；
- 不伪造route信息；
- 不让模型补全。

### 6.5 不实现调用图

本轮不构建通用`CodeEdge`。

只通过明确规则为目标Fixture组织候选，例如：

- Route参数注解对应Request Model；
- Route中的直接函数调用；
- `to_domain()`和`from_domain()`命名方法；
- `response_model`对应Response Model。

这些关系只用于生成`DocumentBlockPlan`，不建立通用长期图模型。

---

## 7. Java候选适配

Java继续复用：

```text
devcollab-worker/.../JavaCodeGraphAnalyzer.java
```

当前已支持：

- class；
- interface；
- enum；
- record；
- annotation；
- method；
- constructor；
- field；
- parentSymbolKey；
- startLine/endLine；
- IMPORTS；
- EXTENDS；
- IMPLEMENTS。

本轮不修改Java解析器，不补：

- method call；
- Controller route；
- DTO conversion；
- Bean关系；
- 执行顺序。

Java侧只完成：

```text
现有CodeSymbol
→ CodeAtom
→ BindingCandidate
```

缺失信息时继续使用SYMBOL或FILE候选。

本轮主要真实验收使用Python/FastAPI项目。

---

## 8. DocumentBlockPlan生成

### 8.1 生成原则

程序根据明确的代码类型生成BlockPlan，而不是让模型自由设计文档结构。

对FastAPI接口，默认生成以下职责：

```text
接口职责
请求模型
领域转换
业务规则
响应构造
```

只有具备真实候选时才生成对应Block。

没有证据时不生成：

- 安全认证；
- Cookie；
- 网关；
- 数据库事务；
- 显式状态码；
- 部署行为；
- 性能结论。

### 8.2 基准接口计划

针对：

```text
POST /api/v1/agent/review
```

生成：

| Block | targetKind | PRIMARY | SUPPORTING |
|---|---|---|---|
| 接口职责 | HTTP_ENDPOINT | `main.review` | Request、转换、规则、Response |
| 请求模型 | SYMBOL | `ReviewDocumentRequest` | `ReviewBlockRequest` |
| 领域转换 | DATA_CONVERSION | `ReviewDocumentRequest.to_domain` | `ReviewBlockRequest.to_domain`、`DocumentReviewContext.from_blocks` |
| 审查规则 | BUSINESS_RULE | `rules.review_document` | 四个helper、`ReviewIssueSuggestion`、`ReviewResult` |
| 响应构造 | RESPONSE_CONSTRUCTION | `ReviewIssueSuggestionResponse.from_domain` | `ReviewDocumentResponse`、`main.review`响应组装范围 |

不生成“安全与错误处理”Block，因为当前Fixture没有足够证据。

### 8.3 模型权限

DeepSeek只负责：

- 按给定Plan生成正文；
- 选择PRIMARY和SUPPORTING候选；
- 返回reason和confidence；
- 证据不足时返回`INSUFFICIENT_EVIDENCE`。

DeepSeek不能：

- 新增Block；
- 合并Block；
- 修改Block职责；
- 修改候选集合；
- 修改sortOrder；
- 生成路径、行号或Symbol；
- 声明代码中不存在的认证、状态码或数据库行为。

---

## 9. Binding Validator

本轮只新增五类核心校验，不建设完整通用Validator体系。

### 9.1 UNKNOWN_CANDIDATE_ID

模型返回的candidateId不在当前任务候选集中。

处理：

```text
ERROR
→ 允许一次Binding Repair
→ 仍失败则不创建Proposal
```

### 9.2 MISSING_PRIMARY_BINDING

每个需要代码关联的Block必须有且只有一个PRIMARY。

处理：

```text
ERROR
→ 补选或重新选择一次
→ 仍失败则不创建该Block
```

### 9.3 PRIMARY_BINDING_LEVEL_MISMATCH

PRIMARY必须符合Block的`targetKind`。

示例：

| targetKind | 合法PRIMARY |
|---|---|
| HTTP_ENDPOINT | FastAPI Route函数 |
| SYMBOL | 对应class/function/method |
| DATA_CONVERSION | `to_domain`、`from_domain`等转换方法 |
| BUSINESS_RULE | 规则或Service函数 |
| RESPONSE_CONSTRUCTION | Response转换或组装函数 |

接口职责Block不能以内部规则函数作为PRIMARY。

### 9.4 BINDING_COVERAGE_INCOMPLETE

如果Plan标记了`requiredCandidateIds`，最终Binding必须覆盖这些候选。

例如接口职责Block至少应包含：

- Route PRIMARY；
- Request Model；
- 核心业务函数；
- Response Model或构造逻辑。

缺失时阻断Proposal。

### 9.5 DUPLICATE_BINDING

同一Block中不得出现：

```text
相同candidateId
+
相同role
```

重复结果由程序去重后重新校验。

---

## 10. PRIMARY与SUPPORTING持久化

### 10.1 必要性

主辅关系不仅用于前端排序，还会影响：

- 默认打开哪条代码；
- Agent以后优先检查哪条Binding；
- 一个辅助Symbol变化是否影响Block；
- 主Anchor失效时是否需要高优先级处理；
- 多Binding解释和面试展示。

因此正式Binding最好保留：

```text
binding_role
binding_ordinal
```

### 10.2 最小数据库变更

历史Flyway Migration不得修改。

新增Migration，为：

```text
document_change_binding_proposals
code_document_bindings
```

增加：

```sql
binding_role VARCHAR(20) NOT NULL DEFAULT 'PRIMARY',
binding_ordinal INTEGER NOT NULL DEFAULT 1
```

约束：

```text
binding_role ∈ PRIMARY / SUPPORTING
binding_ordinal >= 1
```

同一Block：

- 一个PRIMARY；
- PRIMARY ordinal为1；
- SUPPORTING ordinal大于1。

旧Binding默认：

```text
PRIMARY
ordinal = 1
```

旧数据不自动重新判断主辅语义。

### 10.3 暂不增加的字段

本轮不增加：

- relationship；
- workflowId；
- atomId；
- purpose；
- evidence metadata；
- drift status；
- structural fingerprint。

正式Binding仍使用现有：

```text
repository
revision
filePath
anchorKind
symbolKey
startLine
endLine
document
block
role
ordinal
```

---

## 11. Proposal与Apply

现有`DocumentChangeApplicationService`继续作为正式写入边界。

### 11.1 Proposal创建前

Agent侧验证：

- candidateId存在；
- 每个Block只有一个PRIMARY；
- PRIMARY与targetKind匹配；
- requiredCandidateIds完整；
- 没有重复Binding；
- DocumentOperation和BlockPlan一一对应。

### 11.2 Core创建Proposal时

继续验证：

- workspace归属；
- repository归属；
- revision存在；
- filePath存在；
- Symbol/Range Anchor结构；
- document归属；
- block归属；
- clientOperationId合法；
- operation sequence合法。

### 11.3 Apply时

必须保证：

```text
Document Operation
+
所有Binding Proposal
```

在同一PostgreSQL事务中：

- 全部成功；
- 或全部回滚。

任一Binding非法时，不得留下已经创建的DocumentBlock。

### 11.4 幂等

重复Apply：

- 不重复创建Block；
- 不重复创建Binding；
- 返回已有APPLIED结果或幂等成功。

基础DocumentVersion过期时：

```text
PENDING → STALE
```

不得应用到错误版本。

---

## 12. 前端行为

前端继续使用现有Linked Workbench，不重构布局。

只增加：

- PRIMARY优先展示；
- SUPPORTING按ordinal排序；
- 显示“主要代码”和“辅助代码”；
- N/M切换保持稳定；
- 缺少role字段的旧数据沿用当前排序。

推荐排序：

```text
1. PRIMARY
2. SUPPORTING ordinal
3. Anchor精度
4. startLine
5. bindingId
```

不新增：

- Workflow图；
- Agent步骤面板；
-复杂关系图；
-新的文档编辑器；
- VS Code插件。

---

## 13. 真实Fixture

测试对象：

```text
agent-review-service
```

目标接口：

```text
POST /api/v1/agent/review
```

### 13.1 真实代码证据

| 代码单元 | 文件与范围 | 作用 |
|---|---|---|
| `main.review` | `app/main.py` L21–31 | HTTP入口 |
| `ReviewDocumentRequest` | `app/schemas.py` L31–47 | 请求模型 |
| `ReviewDocumentRequest.to_domain` | `app/schemas.py` L39–47 | 请求转换 |
| `ReviewBlockRequest.to_domain` | `app/schemas.py` L22–28 | 子Block转换 |
| `DocumentReviewContext.from_blocks` | `app/domain.py` L59–76 | 领域上下文构建 |
| `rules.review_document` | `app/rules.py` L35–105 | 审查规则 |
| 四个rules helper | `app/rules.py` L108–127 | 规则辅助 |
| `ReviewResult` | `app/domain.py` L90–94 | 规则结果 |
| `ReviewIssueSuggestionResponse.from_domain` | `app/schemas.py` L59–67 | suggestion响应转换 |
| `ReviewDocumentResponse` | `app/schemas.py` L70–74 | 最终响应模型 |

注意：

- `ReviewDocumentResponse.from_domain()`不存在；
- 最终Response由`main.review`直接构造；
- 当前代码没有明确认证流程；
- 当前代码没有显式自定义状态码；
- 不得生成上述不存在的描述。

### 13.2 期望Binding

#### 接口职责

```text
PRIMARY
main.review

SUPPORTING
ReviewDocumentRequest
ReviewDocumentRequest.to_domain
rules.review_document
ReviewDocumentResponse
```

#### 请求模型

```text
PRIMARY
ReviewDocumentRequest

SUPPORTING
ReviewBlockRequest
```

#### 领域转换

```text
PRIMARY
ReviewDocumentRequest.to_domain

SUPPORTING
ReviewBlockRequest.to_domain
DocumentReviewContext.from_blocks
```

#### 审查规则

```text
PRIMARY
rules.review_document

SUPPORTING
_combined_text
_meaningful_text
_contains_any
_severity_order
ReviewResult
```

#### 响应构造

```text
PRIMARY
ReviewIssueSuggestionResponse.from_domain

SUPPORTING
ReviewDocumentResponse
main.review
```

---

## 14. 验收标准

本轮完成必须通过以下五个案例。

### 案例一：普通函数Block

解释`review_document()`的Block：

- PRIMARY为`rules.review_document`；
- 可以绑定四个helper作为SUPPORTING；
- 不得只绑定整个文件。

### 案例二：HTTP接口Block

解释`POST /api/v1/agent/review`：

- PRIMARY必须为`main.review`；
- `review_document()`只能作为SUPPORTING；
- 必须关联请求和响应模型；
- 不得生成认证和不存在的状态码。

### 案例三：转换Block

解释请求到领域对象转换：

- PRIMARY为`ReviewDocumentRequest.to_domain`；
- SUPPORTING包括子Block转换和Context构建；
- Route不能成为PRIMARY。

### 案例四：非法模型输出

模型返回未知candidateId：

- Validator拒绝；
- 最多Repair一次；
- Repair仍失败时不提交Proposal。

### 案例五：事务Apply

一个Proposal包含：

- 多个DocumentBlock Operation；
- 每个Block一个PRIMARY；
- 多个SUPPORTING Binding。

任一Binding非法时：

- 所有Block和Binding均不落库；
- 数据库保持原状态。

---

## 15. 施工计划

### Commit 1：统一CodeAtom与候选ID

修改：

```text
agent-service/app/schemas/binding_plans.py
agent-service/app/planning/binding_candidates.py
agent-service/tests/test_block_binding.py
```

实现：

- 精简CodeAtom；
- BindingCandidate由Atom投影；
- task-stable candidateId；
- 保留FILE fallback；
- 不修改Prompt和数据库。

验收：

- 同一task重复构建候选ID一致；
- Python和Java Symbol正确映射；
- 解析失败时仍有FILE候选。

---

### Commit 2：Python AST与FastAPI Route

修改：

```text
agent-service/app/planning/binding_candidates.py
agent-service/app/runtime/project_unit_context.py
agent-service/tests/test_block_binding.py
```

实现：

- 统一Python AST提取；
- class/function/async/method；
- parent；
- signature；
- FastAPI method/path/response_model；
- 不实现通用CALLS Edge。

验收：

- `main.review`被识别为HTTP Route；
- 请求和响应模型可正确定位；
- 精确行号正确。

---

### Commit 3：DocumentBlockPlan

修改：

```text
agent-service/app/schemas/plans.py
agent-service/app/graph/document_sync_workflow.py
agent-service/app/planning/context_serializer.py
agent-service/tests/test_document_authoring.py
```

实现：

- 程序生成BlockPlan；
- 一个职责一个Block；
- 固定blockKey和sortOrder；
- 给定PRIMARY/SUPPORTING候选范围；
- 证据不足不生成Block。

验收：

- 基准接口拆成五个Block；
- 不生成安全/认证Block；
- 模型不能增加第六个Block。

---

### Commit 4：模型选择与核心Validator

修改：

```text
agent-service/app/providers/deepseek.py
agent-service/app/prompts/document_sync_v1.md
agent-service/app/prompts/block_binding_v1.md
agent-service/app/planning/validator.py
agent-service/tests/test_document_authoring.py
agent-service/tests/test_block_binding.py
```

实现：

- DeepSeek按BlockPlan生成正文；
- 只返回candidateId、role、ordinal；
- 五类核心Validator；
- 最多一次Repair。

验收：

- HTTP接口Block不会以规则函数作为PRIMARY；
- 未知ID、缺PRIMARY、覆盖不完整均被拒绝。

---

### Commit 5：角色持久化与E2E

修改：

```text
knowledge-core Flyway Migration
DocumentChangeModel
DocumentChangeApplicationService
CodeDocumentBinding
JdbcGitKnowledgeRepository
相关API DTO
web/src/api/git.ts
web/src/utils/linkedWorkbenchBindings.ts
相关Java与前端测试
```

实现：

- `binding_role`；
- `binding_ordinal`；
- Proposal和正式Binding均保留角色；
- 前端PRIMARY优先；
- Apply原子性测试。

验收：

```text
真实DeepSeek调用
→ 五个职责Block
→ 正确PRIMARY/SUPPORTING
→ Proposal
→ 人工Apply
→ 刷新页面后角色和顺序不丢失
```

---

## 16. 测试矩阵

| 层 | 必测内容 |
|---|---|
| CodeAtom | Python/Java字段、parent、range、Route、稳定ID |
| Candidate | FILE fallback、SYMBOL映射、确定性candidateId |
| BlockPlan | 一个职责一个Block、固定顺序、证据不足不生成 |
| Selection | PRIMARY唯一、SUPPORTING顺序、未知ID拒绝 |
| Validator | 五类错误逐一覆盖 |
| Proposal | created document/block client reference正确 |
| Apply | 多Block、多Binding、幂等、STALE、事务回滚 |
| 前端 | PRIMARY优先、SUPPORTING顺序、N/M切换 |
| E2E | `POST /api/v1/agent/review`五Block真实案例 |

---

## 17. 风险与降级

| 风险 | 处理 |
|---|---|
| Python AST解析失败 | 退回FILE候选 |
| Route decorator无法静态解析 | 保留普通FUNCTION候选 |
| Java图信息不足 | 使用现有SYMBOL，不生成额外语义 |
| 候选过多 | 每个Block最多16个候选 |
| Preview过长 | PRIMARY最多600字符，SUPPORTING最多300字符 |
| DeepSeek返回未知ID | Repair一次，仍失败则停止 |
| Block缺少可靠PRIMARY | 不生成该Block |
| 角色Migration出现问题 | 回退代码消费，旧Binding按默认PRIMARY读取 |
| 真实模型效果不稳定 | 保留确定性Fixture和Mock响应，不自动Apply |
| 旧文档Binding质量低 | 不迁移旧数据，仅对新Proposal生效 |

---

## 18. 明确非目标

本轮不做：

- CodeEdge通用模型；
- 全仓调用图；
- 五类WorkflowCandidate；
- 大函数自动AST子范围；
- Java Controller和调用链增强；
- Git Diff影响分析；
- Binding跨Revision漂移；
- LSP；
- Tree-sitter；
- Skill系统；
- Subagent；
- Model Router；
- RAG或Embedding；
- Workflow数据库表；
- 旧Binding自动修复；
- Web或VS Code插件重构。

---

## 19. 审计结论

现有Binding系统的正式写入链路已经较为稳健：

```text
程序候选
→ 模型选择ID
→ Expander复制真实Anchor
→ Core归属和Revision校验
→ 人工确认
→ 事务Apply
```

当前主要问题集中在候选和Block职责层：

1. Python结构识别不统一；
2. FastAPI Route未成为明确语义候选；
3. Block职责由模型自由决定；
4. 多Binding没有PRIMARY/SUPPORTING语义；
5. 缺少主Anchor层级和覆盖完整性校验。

下一轮只修复上述五点。

最终目标：

> 对一个真实FastAPI接口，程序能够生成职责明确的文档Block计划，DeepSeek只能在真实候选中选择主辅Binding，Validator能够阻止错误粒度和缺失关联，Core能够将DocumentBlock与多条Binding原子落库。

该能力完成后，DevCollab便具备足够稳定的代码—文档双向Binding基础，可以作为简历中的核心Agent与Java后端功能进行展示和讲解。
```