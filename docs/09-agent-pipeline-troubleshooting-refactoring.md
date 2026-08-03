# Agent 管线排查重构全记录：从"不干活"到链路稳健

> 编写时间：2026-08-03  
> 适用场景：实习面试 —— 展示大型系统的系统性排查能力、架构洞察、以及对 AI Agent 管线的契约设计与模型容错的深度思考

---

## 一、问题起点：Agent 检查返回"处理完成"，但什么都没做

**现象**：前端点击"Agent 检查"后，系统显示绿色提示"Agent 处理完成，未生成新的待审批变更"。用户反复触发同一个 `schemas.py` 文件，每次都在 1-3 秒内返回 `COMPLETED + NO_CHANGE`，phase 始终停在 `LOADING_CONTEXT`——证明没有进入语义分析和 MCP 提交阶段。

**数据库印证**：连续四次检查 `agent-review-service/app/schemas.py`，全部是 `status: COMPLETED, result: NO_CHANGE, phase: LOADING_CONTEXT, review_request_ids: []`。

### 1.1 定位根因链路

我沿着管线逐层追踪——完整链路约有 8 个阶段，必须确定数据在哪一步被丢弃。

**第 1 层：AST 解析**（`code_ast_atom.py`）  
✅ 正常。`schemas.py` 的 4 个 Pydantic 模型和 3 个函数被正确提取为 `SymbolAtom[]`。

**第 2a 层：关系图构建**（`atom_relation_graph.py`）  
✅ 正常。构建了 CALLS/CONTAINS/PARAMETER_TYPE 关系图。

**第 2b 层：范围发现**（`graph_entry_scope.py`）——🔴 **数据丢弃点**

```python
def _detect_entries(graph):
    entries = []
    for sym in graph.catalog.symbols:
        if sym.http_method and sym.http_path:  # ← 只认 HTTP 路由
            entries.append(...)
    return entries
```

`schemas.py` 包含 Pydantic 模型类、工具函数——**没有 HTTP 路由装饰器**。`_detect_entries()` 返回空列表 → `discover_scopes()` 返回 0 个 SemanticScope → [job_executor.py:108](agent-service/app/execution/job_executor.py#L108) 提前返回 `NO_CHANGE`。

**完整错误链**：
```
_detect_entries() 只认 http_method + http_path
  → schemas.py 没有路由 → entries = []
  → discover_scopes() → 0 scopes
  → JobExecutor.execute() → 提前 return NO_CHANGE
  → Worker 只检查 change_request_id → outcome = "NO_CHANGE"
  → 前端显示 "未生成新的待审批变更"
```

### 1.2 修复：入口回退检测

**思路**：HTTP 路由不是唯一入口形式。当没有 HTTP 路由时，顶层类和函数本身就是语义入口——文件边界已经划好了，不需要"发现"。

在 `_detect_entries()` 增加了**三级回退**：HTTP 路由（优先级 1）→ 顶层公开类/函数（优先级 2）→ 公开方法（优先级 3）。Pydantic 模型入口 label 加 `model:` 前缀。

### 1.3 连锁修复的两个掩蔽点

**掩蔽点 1**：Worker 只检查 `change_request_id`，不检查 `result["status"]`

```python
# 修复前
outcome = "REVIEW_SUBMITTED" if review_id else "NO_CHANGE"

# 修复后：三条持久化路径
if exec_status == "FAILED":         # → fail_unit()
elif review_id:                     # → complete_unit("REVIEW_SUBMITTED")
else:                               # → complete_unit("NO_CHANGE")
```

**掩蔽点 2**：语义校验失败/Provider 失败被静默标记为 NO_CHANGE。修复为单独计入 failed 计数。

### 1.4 思考：为什么会有"HTTP 路由入口"这个设计？

`discover_scopes` 的设计源自一个**合理的图算法直觉**：从 HTTP 路由出发 → BFS 沿调用链扩展 → 收集所有可达符号 → 这是一个"语义范围"。设计模型是"API 服务的路由 → 调用 → 模型/工具"的单向推理。

但触发机制不配合——用户在 IDE 里打开**任意文件**（路由文件、模型文件、工具文件均可触发 `CURRENT_FILE_ANALYSIS`），而入口检测只认 HTTP 路由。同时关系图构建在**单文件分析**场景下是冗余的——单文件不需要跨文件调用图。

**教训**：触发机制和语义模型必须配合——如果触发源不限于路由文件，入口检测也不能限于路由。

---

## 二、深层重构之一：让模型不碰 ID

### 2.1 问题

Agent 管线让 DeepSeek 模型去"记"和"复述" hash 型 atom_id（`sym_<24位hex>`）。LLM 做不到精确复述，于是产生一连串问题：

- **缺陷 A**：DeepSeek 响应被 markdown 代码块包裹 → 解析失败
- **缺陷 B**：模型返回 symbol_key 而非 atom_id → 校验器全部拒绝
- **缺陷 D**：mcp-server 的 schema 声明与实际返回不一致

每次失败都触发 Bounded Repair 循环（最多 3 次重试），最终数据被丢弃。

### 2.2 根因分析

核心架构错误：**让生成模型去记 hash 型主键**。

- `atom_id` = `sym_<sha256(revision+file+kind+qualified_name)>`（24 位 hex，对 LLM 无任何语义）
- `symbol_key` = `PYTHON:app/schemas.py:ReviewDocumentRequest:CLASS`（可读、唯一、包含路径和类名）

模型看到的是 MCP 工具返回的 symbol_key，但系统提示和 schema 却要求它**翻译成 atom_id 再输出**。LLM 记不住 hash，于是：
1. 回填 symbol_key 代替 atom_id → 校验器报 `atom_id not in snapshot`
2. 包裹 markdown 围栏 → `json.loads` 报 `char 0`
3. 记错 hash → 下游找不到引用

### 2.3 治本方案：模型只引可读锚点，代码层绑定

**核心转变**：给模型可见的锚点改为 symbol_key；模型只引用它能"看到并照抄"的可读标识；代码层在入口一次性绑定回 atom_id。

具体改动：
1. **ContextSnapshot 加权威映射**：`atom_by_symbol` 索引（symbol_key → atom_id），成为全链路唯一映射源，替代 4 处独立构建的映射
2. **MCP 工具统一暴露 symbol_key**：`get_structure_block` 字段改为 `symbol_keys`，`search_context_symbols` 按 symbol_key 匹配
3. **系统提示改写**："从工具返回里照抄 symbol_key，不要自行发明 ID"
4. **入口绑定函数**：`_bind_result_atoms` 用权威索引在 document 规划前一次性绑定回 atom_id
5. **校验器宽容化**：去掉 group_id 去重、primary 非空、primary⊆informed 等格式硬约束，只验证"引用存在于快照"

**设计原则**：模型输出 → 程序消化绑定 → 下游用 atom_id 不变。binding/plan 层零改动。

---

## 三、逐层排查 MCP 契约漂移（缺陷 C/D/E/...）

管线走通到语义分析后，在 MCP 提交阶段连续遭遇契约不一致。这暴露了一个深层问题：agent 和 mcp-server 各写一套协议、靠人工同步，必然漂移。

### 3.1 缺陷 C：CURRENT_FILE 缺 `binding.list_batch` 权限

**现象**：`McpClientError: Delegated Agent token is outside its allowed scope`

**排查**：agent 的 `BindingReader.read_batch()` 调用 `devcollab.binding.list_batch`，但 knowledge-core 签发 delegation 时，`CURRENT_FILE_TOOLS` 列表只含单文件版本 `binding.list`，不含批量版本 `binding.list_batch`。

**修复**：`AgentDelegationService.java` 的 `CURRENT_FILE_TOOLS` 加 `binding.list_batch`。重新编译 knowledge-core 并重启。

### 3.2 缺陷 D：`list_batch` 返回 vs schema 声明不符

**现象**：`Tool output validation failed: 未定义属性 fileHasBindings / truncated / omittedBindingCount`

**排查**：mcp-server 的 `BindingListBatchApplicationService` 在返回 `files[]` 里加了这三个字段，但 `McpToolSchemas.java` 的 `bindingListBatchOutput` schema 只声明了 `filePath + bindings`。`additionalProperties: false` 下 MCP SDK 拒绝。

**修复**：`McpToolSchemas.java` 的 file schema 补上三个字段。这是 agent↔mcp 在同一工具上各写一套结构的经典例子。

### 3.3 缺陷 E：提交顶层多带字段

**现象**：`Tool input validation failed: 未定义属性 repositoryId / revision / sourceType`

**排查**：agent 的 `plan_writer.py` 在 `submit_document_change` 顶层 payload 带了这几个字段，但 mcp-server 的 `submitDocumentChangeInput` schema 顶层只允许 `workspaceId, clientRequestId, summary, rationale` + 数组。这些字段应在子对象（bindingProposals/evidence）里。

**修复**：`plan_writer.py` 移除顶层 `repositoryId / revision / sourceType`。

### 3.4 缺陷 F-G-H：提交业务校验层

后续又连续发现 3 个 business validation 拒绝：

| 缺陷 | knowledge-core 错误 | 根因 | 修复 |
|------|---------------------|------|------|
| F | `CREATE_DOCUMENT must include proposedDocumentTitle` | `PlanOperation` 缺 title 字段 | 加 `proposed_document_title` + plan_validator 填充 + plan_writer 映射 |
| G | `Binding must include documentId or createdDocumentClientOperationId` | `SectionBinding` 无文档关联 | 加 `document_id/created_document_op_id` + binding_resolver 填充 + plan_writer 映射 |
| H | `IDEMPOTENCY_CONFLICT: clientRequestId already used` | `run_id` 确定性重复（`exec-<repo>-<rev[:8]>`） | execute 接收唯一的 `run_id` 参数（worker 传 `unit_id`） |

### 3.5 排查方法论总结

这些缺陷的排查有共同模式：
1. **不看抽象错误信息**——"Document change request was rejected"毫无信息量
2. **透传真实 body**：修改 `HttpKnowledgeCoreGateway.java` 把 400/409 response body 透传进异常消息（这是关键一步）
3. **逐层推：这次到了 MCP 提交 → 过了 input 校验 → 过了 output 校验 → 到了 knowledge-core 业务校验 → 业务校验又一个一个修**

**教训**：抽象错误 = 信息丢失。端到端排查必须保证每一层的拒绝原因可追溯。透传真实 response body 是排查契约不一致的命门。

---

## 四、深层次重构之二：MCP 契约单一权威源

### 4.1 问题本质

缺陷 C/D/E/F/G 全都指向同一根源：**agent 和 mcp-server 各写一套契约，没有单一权威源**。

- mcp-server 用 826 行手写 Java Map 定义 schema（`McpToolSchemas.java`）
- agent 侧用手写 dict 拼 payload + 手写 `.get()` 解析返回（`plan_writer.py` 等）
- 同一工具协议在两处各写一遍，靠人工同步

### 4.2 治本方案：共享 JSON Schema

**核心设计**：

```
        ┌─────────────────────────────┐
        │  共享契约（单一权威源）         │
        │  contracts/mcp/*.schema.json  │
        └───────┬───────────────┬──────┘
                │               │
      ┌─────────▼────┐   ┌─────▼─────────┐
      │ mcp-server    │   │ agent-service  │
      │ (Java)        │   │ (Python)       │
      │ 从JSON加载schema│   │ 契约测试校验    │
      │ 替代手写Map    │   │ payload/返回    │
      └───────────────┘   └───────────────┘
```

**关键设计决策**：
1. **放独立 `contracts/mcp/` 目录**（非 Java 模块），两侧用各自语言读取
2. **外部引用无副本**：mcp-server 的 maven-resources 直接从 `../contracts/mcp` 拷贝到 classpath，杜绝"两个副本漂移"
3. **结构契约不含尺寸**：只表达字段/类型/必填/嵌套，maxLength 等配置级限制留在代码里（契约对齐的核心是结构，不是尺寸数字）
4. **agent 测试严格/运行宽松**：契约测试对 payload 做严格校验防漂移，运行时不做（避免过度严格破坏模型创作自由）

### 4.3 实施

1. **抽取 10 个工具的 input/output 契约**为 18 个 JSON Schema 文件
2. **mcp-server**：删除 826 行手写 `McpToolSchemas.java`，新增 `ContractSchemaLoader` 从 classpath 加载 JSON，11 个 ToolContributor 全部改用 loader
3. **agent**：加 `jsonschema` + `contract_validator` 模块 + 契约测试（12 项）
4. **切换中自动暴露的契约漂移**：
   - `binding.list` output 漏 `repositoryId`（MCP 返回有，契约没声明）
   - `submit_document_change` output 漏 `workspaceId/repositoryId/documentId/operationCount/bindingProposalCount`
   - `$ref` 引用在 networknt 下解析问题（改为内联）
   - `$schema/$id` 元字段与 MCP SDK 不兼容（移除）
   - `DocumentStructure.version` 与 MCP 契约不符

**最终状态**：mcp-server 84 测试全过、agent 契约测试全过、端到端生成待审批变更。

---

## 五、深层次重构之三：语义结果的模型格式容错

### 5.1 问题

`rules.py` 的 DeepSeek 返回的 `execution_flow` 结构使用了 camelCase 键名（`stepOrder/atomId`），被 `SemanticAnalysisResult` 的 `strict=True + extra="forbid"` 拒绝，导致 Bounded Repair 3 次全失败。

### 5.2 核心洞察

"大模型做的是解读代码，输出格式必然有各种变化。不能靠严格 schema 逼模型精确——高要求配脆解析，必然处处炸。"（用户原话）

这不是模型的问题，是设计的假设错了——**把 LLM 当成确定性序列化程序**。

### 5.3 治本方案：三层容错

| 层 | 改动 | 作用 |
|----|------|------|
| **Schema 层** | 去 `strict=True`、改 `extra="ignore"` | 模型多写字段不拒绝、类型轻微偏移可接受 |
| **解析层** | 加 `_normalize_payload`（递归 camelCase→snake_case 归一化） | 程序消化格式差异，不依赖模型输出一致性 |
| **校验层** | 已有宽容校验器（只查存在性） | 模型专注语义，不因格式触发 repair |

**设计原则**：模型输出 → 程序消化格式 → 只做语义检查。格式问题全部由程序吸收，模型专注解读代码内容。

**验证**：schemas.py / rules.py / main.py 三个不同类型的文件全部稳定生成待审批变更。

---

## 六、经验总结：排查大型系统的方法论

### 6.1 逐层推，不跳跃
管线的每一层都可能是丢弃点。从输入到输出逐层确认数据在哪一步消失。用数据库 + 日志 + 代码走读三重验证。

### 6.2 不怕查到底
"Document change request was rejected" 不够——必须看到 knowledge-core 的 400/409 body。临时加透传日志（改 mcp-server 的 gateway 把 response body 放进异常消息）是排查命门。

### 6.3 补丁 vs 治本
最初的修复（加回退入口、规范化 atom_id、补 schema 字段）都是"打补丁"。真正的价值在于**识别模式**——四个缺陷指向同一根源（两侧各写一套契约）→ 治本重构（共享 schema）。识别到模式的时刻，就是该停下来重构的信号。

### 6.4 LLM 管线的特殊约束
- **不要让 LLM 记任何 hash 或内部 ID**——它做不到，只给它能"看到并照抄"的可读标识
- **不要用 strict schema 逼 LLM 精确**——用程序消化格式，让模型专注内容
- **Bounded Repair 治标不治本**——如果 repair 循环在修正格式而非内容，说明格式契约对模型不友好

### 6.5 契约管理
- 跨语言/跨服务的协议必须有**单一权威源**
- 抽象错误 = 信息丢失——端到端排查必须保证每层拒绝原因可追溯
- 契约测试在构建期捕获漂移，比运行期炸好得多

---

## 附录：修改文件清单

### 治本重构 1：模型不碰 ID
| 文件 | 改动 |
|------|------|
| `app/schemas/model_context/snapshot.py` | 加 `atom_by_symbol` 权威索引 |
| `app/model_context_mcp/snapshot_read_service.py` | MCP 工具暴露 symbol_key |
| `app/model_context_mcp/service_mcp_tool.py` | `symbol_keys` 字段名对齐 |
| `app/providers/deepseek.py` | 系统提示改 symbol_key + `_extract_json_object` |
| `app/schemas/semantic/analysis_request.py` | output_contract 改 symbol_key |
| `app/semantic/analysis_orchestrator.py` | entry_point_ids 改 symbol_key |
| `app/execution/job_executor.py` | `_bind_result_atoms` 正式绑定 |
| `app/semantic/result_validator.py` | 宽容校验 |
| `app/graph_entry_scope.py` | `build_file_scopes()` 直接范围构造 |

### MCP 契约共享 schema
| 文件 | 改动 |
|------|------|
| `contracts/mcp/*.json` | 新建 18 个契约文件（单一权威源） |
| `devcollab-mcp-server/pom.xml` | maven-resources 外部引用 |
| `devcollab-mcp-server/.../McpToolSchemas.java` | **删除**（826 行手写） |
| `devcollab-mcp-server/.../ContractSchemaLoader.java` | 新建，从 JSON 加载 |
| `devcollab-mcp-server/.../*ToolContributor.java` | 全部 11 个接入 loader |

### 语义结果容错
| 文件 | 改动 |
|------|------|
| `app/schemas/semantic/analysis_result.py` | 去 strict/extra_forbid，改 extra="ignore" |
| `app/providers/deepseek.py` | 加 `_normalize_payload` camelCase→snake_case |

### 具体缺陷修复
| 文件 | 修复 |
|------|------|
| `knowledge-core/.../AgentDelegationService.java` | CURRENT_FILE_TOOLS 加 list_batch |
| `devcollab-mcp-server/.../McpToolSchemas.java` | binding.list_batch schema 补字段 |
| `app/platform_mcp/plan_writer.py` | 移除顶层多余字段/补 document title/binding 文档关联 |
| `app/schemas/document_planner/plan.py` | PlanOperation 加 proposed_document_title |
| `app/document_planner/plan_validator.py` | fill title |
| `app/document_planner/binding_resolver.py` | 加 document 关联 + 单 primary |
| `app/schemas/platform_mcp/document.py` | DocumentBlock.type→block_type, content→str, 移除 version |
| `app/platform_mcp/document_reader.py` | 读 blockType, 移除 version 构造 |
| `app/execution/job_executor.py` | run_id 改为唯一（幂等修复） |
| `app/worker.py` | Worker 状态映射三分支 + 传 unit_id |
| `devcollab-mcp-server/.../HttpKnowledgeCoreGateway.java` | 透传 body 错误 |

### 测试
| 文件 | 内容 |
|------|------|
| `tests/test_mcp_contracts.py` | 契约测试 12 项 |
| `tests/test_atom_id_normalization.py` | `_bind_result_atoms` 测试 |
| `tests/test_binding_resolver.py` | 单 primary 测试 |
| `tests/test_semantic_result_tolerance.py` | 三层容错测试 |
| `tests/test_deepseek_extract.py` | `_extract_json_object` 测试 |
| `McpToolSchemasTests.java` | 更新为 loader 测试 |
