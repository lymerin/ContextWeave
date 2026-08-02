# DevCollab Agent Service 架构

## 数据流全景

```
源码文件
  │
  ▼
┌─ 第0层: 文件粗筛 ──────────────────────────────────────────┐
│ source_selection/file_filter.py                             │
│   RepositoryFileRef[] → SelectedSourceFileBatch (只含路径)   │
└────────────────────────────────────────────────────────────┘
  │
  ▼
┌─ 边界A: 平台MCP读写 ───────────────────────────────────────┐
│ platform_mcp/                                               │
│   workspace_reader.py    仓库上下文 + 文件目录               │
│   source_reader.py       批量读取源码 → SourceFileBatch      │
│   document_reader.py     文档结构 + 候选文档                 │
│   binding_reader.py      已有绑定关系                        │
│   plan_writer.py         内部AgentPlan → MCP提交            │
└────────────────────────────────────────────────────────────┘
  │
  ▼
┌─ 第1层: AST结构解析 ───────────────────────────────────────┐
│ source_analysis/code_ast_atom.py                            │
│   输入: SourceFileBatch (含源码文本)                         │
│   处理: ast.parse() → 遍历 ClassDef/FunctionDef             │
│   输出: AtomCatalog                                         │
│     SymbolAtom { atom_id, symbol_key, kind, name,           │
│       start_line, end_line, body_start_line, body_end_line, │
│       signature, http_method, http_path, ... }               │
│                                                             │
│ schemas/ast_atom.py        SymbolAtom, ModuleAtom,          │
│                             AtomCatalog (@dataclass frozen)  │
└────────────────────────────────────────────────────────────┘
  │
  ▼
┌─ 第2层: 语义补充 (三步) ───────────────────────────────────┐
│                                                             │
│ 2a. 关系连接                                                │
│ source_analysis/atom_relation_graph.py                      │
│   输入: AtomCatalog + 源码                                   │
│   处理: 对每个符号重新解析AST，收集:                          │
│     CALLS (调用关系)                                        │
│     PARAMETER_TYPE / RETURN_TYPE (类型引用)                  │
│     CONTAINS (类包含方法)                                    │
│     FIELD_READS / FIELD_WRITES (字段读写)                    │
│     THROWS (异常抛出)                                        │
│   分类: INTERNAL / BOUNDARY / UNRESOLVED                     │
│   输出: RepositoryCodeGraph (AtomCatalog + Relation[] + 索引)│
│                                                             │
│ schemas/repository_graph.py                                 │
│   Relation { relation_id, source_atom_id, kind,             │
│     target_atom_id | target_external, category }            │
│   RepositoryCodeGraph (@dataclass frozen)                   │
│                                                             │
│ 2b. 入口收集 + 范围圈定                                      │
│ source_analysis/graph_entry_scope.py                        │
│   输入: RepositoryCodeGraph                                  │
│   处理:                                                     │
│     检测入口: HTTP_ROUTE 装饰器                              │
│     从入口 BFS 沿 CALLS 关系扩展 (最大深度5)                  │
│     补充 PARAMETER_TYPE / RETURN_TYPE 依赖                   │
│     合并高重叠入口 (>60% 共享符号)                            │
│   输出: SemanticScope[]                                     │
│     ScopeMember { symbol_key, role, distance, entry_paths } │
│     EntryPoint { symbol_key, kind, label }                  │
│                                                             │
│ schemas/scope.py            SemanticScope, EntryPoint,      │
│                              ScopeMember (@dataclass frozen) │
│                                                             │
│ 2c. 上下文整形                                               │
│ source_analysis/scope_shape_context.py                      │
│   输入: SemanticScope + AtomCatalog + 源码                   │
│   处理:                                                     │
│     源码去重 (嵌套定义不重复展示)                             │
│     按结构距离切块 (d=0入口, d=1直接调用, d=2间接)           │
│     生成入口路径描述                                         │
│   输出: ShapedCodeContext                                   │
│     StructureBlock { atoms[], chunks[], description }       │
│     SourceChunk { file_path, start_line, end_line, source } │
│                                                             │
│ schemas/shaped_context.py                                   │
│   ShapedCodeContext, StructureBlock, SourceChunk, AtomRef   │
│   (@dataclass frozen)                                       │
└────────────────────────────────────────────────────────────┘
  │
  ▼
┌─ 边界B: 上下文快照 + MCP工具 ──────────────────────────────┐
│ model_context_mcp/                                          │
│                                                             │
│ context_freeze_snapshot.py                                  │
│   ShapedCodeContext → ContextSnapshot (不可变)               │
│   + SnapshotManifest (必须交付的ID集合)                      │
│   + structural_fingerprint (SHA-256)                        │
│                                                             │
│ snapshot_store_registry.py                                  │
│   register / acquire / release / TTL                        │
│   有活跃会话时不被TTL清理                                    │
│                                                             │
│ snapshot_read_service.py (纯查询, 无状态)                    │
│   overview() / get_block() / get_atom() / trace() / search()│
│   每次返回带 coverage: set[str]                             │
│                                                             │
│ service_mcp_tool.py (五个MCP工具暴露)                        │
│   get_context_overview       总览 (入口/原子数/结构块目录)   │
│   get_structure_block        读取结构块 (源码+原子)         │
│   get_atom_detail            读取原子详情 (源码+入边/出边)   │
│   trace_structure_path       追踪入口到目标的路径            │
│   search_context_symbols     按名称搜索符号                  │
│                                                             │
│ schemas/model_context/snapshot.py                           │
│   ContextSnapshot, SnapshotManifest (@dataclass frozen)     │
└────────────────────────────────────────────────────────────┘
  │
  ▼
┌─ DeepSeek API ─────────────────────────────────────────────┐
│ providers/deepseek.py                                       │
│   DeepSeekProvider.analyze_semantics()                      │
│                                                             │
│   流程:                                                     │
│   1. 发送 SemanticAnalysisRequest (只含引用,不含源码)        │
│   2. DeepSeek 通过 MCP 工具按需读取:                         │
│        overview → 全部 structure_blocks → atoms(按需)       │
│   3. tool_handler 回调 → orchestrator.handle_tool_call()    │
│      → 调 model_context_mcp 五个工具                         │
│   4. 循环直到 DeepSeek 输出最终结果                           │
│   5. 解析为 SemanticAnalysisResult                          │
│                                                             │
│ schemas/semantic/analysis_request.py                        │
│   SemanticAnalysisRequest (Pydantic strict)                 │
│                                                             │
│ schemas/semantic/analysis_result.py                         │
│   SemanticAnalysisResult { semantic_groups[], ... }         │
│   SemanticGroup { group_id, title, target_kind,             │
│     primary_atom_ids[], informed_by_atom_ids[],              │
│     evidence_refs[] }                                       │
│   (Pydantic strict — DeepSeek 边界数据)                     │
└────────────────────────────────────────────────────────────┘
  │
  ▼
┌─ Coverage + Repair Loop ───────────────────────────────────┐
│ semantic/                                                   │
│                                                             │
│ analysis_orchestrator.py                                    │
│   Session 状态机:                                           │
│     READING_CONTEXT → GENERATING → VALIDATING               │
│       → REPAIR_FLASH → REPAIR_PRO                           │
│       → SUCCEEDED | FAILED_REQUIRES_HUMAN                   │
│                                                             │
│ context_coverage.py                                         │
│   CoverageTracker: SnapshotManifest vs 实际交付              │
│   全部结构块读完才允许DeepSeek输出                            │
│                                                             │
│ result_validator.py                                         │
│   校验 semantic_groups:                                     │
│     primary_atom_ids 非空                                   │
│     primary_atom_ids ⊆ informed_by_atom_ids                 │
│     所有 atom_id 存在于快照                                  │
│     revision + snapshot_hash 一致                            │
│                                                             │
│ schemas/semantic/coverage.py                                │
│   ContextCoverageReport (@dataclass frozen)                 │
└────────────────────────────────────────────────────────────┘
  │
  ▼
┌─ 第3层: 文档规划 + 绑定 ───────────────────────────────────┐
│ document_planner/                                           │
│                                                             │
│ evidence_catalog_builder.py                                 │
│   ContextSnapshot → PlanningEvidenceCatalog                 │
│   atom_id → file_path, symbol_key, start_line, end_line     │
│                                                             │
│ document_composer.py                                        │
│   SemanticGroup → PlannedSection                            │
│   PlannedSection { section_ref, title, target_kind,         │
│     content_markdown, primary_atom_ids[],                    │
│     informed_by_atom_ids[] }                                │
│                                                             │
│ binding_resolver.py                                         │
│   primary_atom_ids → PRIMARY (ordinal=1)                    │
│   informed_by 其余 → SUPPORTING (ordinal 2,3,...)           │
│   所有行号从 PlanningEvidenceCatalog 查                      │
│   输出: SectionBindingSet[] (完整绑定状态,非增量)             │
│                                                             │
│ target_resolver.py                                          │
│   Section → document_id + block_id                          │
│   决策: 新建文档 vs 追加已有文档                              │
│   匹配: target_kind → 已有Block标题                          │
│                                                             │
│ plan_validator.py                                           │
│   Binding Gate: PRIMARY非空, 行号一致, 无重复atom_id          │
│   Target Gate: document_id + block_id 完整                   │
│   组装 AgentPlan                                            │
│                                                             │
│ schemas/document_planner/evidence.py                        │
│   PlanningEvidenceCatalog, EvidenceAtom (@dataclass frozen) │
│                                                             │
│ schemas/document_planner/plan.py                            │
│   AgentPlan, PlannedSection, SectionBindingSet,             │
│   SectionBinding, PlanOperation                             │
└────────────────────────────────────────────────────────────┘
  │
  ▼
┌─ 提交 ─────────────────────────────────────────────────────┐
│ platform_mcp/plan_writer.py                                 │
│   内部AgentPlan → MCP BindingProposal[] 兼容格式             │
│   缺 document_id/block_id → 拒绝提交                         │
│   → MCP submit_document_change                              │
│     → knowledge-core: removeBindingsForTarget                │
│       → createBinding → code_document_bindings 表            │
└────────────────────────────────────────────────────────────┘
```

## 编排层

```
execution/job_executor.py   JobExecutor — 按序调用六层管线

worker.py                   AgentWorker — job调度 + 心跳
                            调 JobExecutor.execute()
                            复用 providers/deepseek.py 的 API key
```

## 类型规则

| 边界 | 类型 | 原因 |
|------|------|------|
| MCP 输入输出 | Pydantic strict (`extra="forbid"`) | 多字段/缺字段立即报错 |
| DeepSeek 返回 | Pydantic strict | 严格校验引用存在性 |
| 内部图结构 | `@dataclass(frozen=True)` | 不可变, `tuple`/`frozenset`/`MappingProxyType` |

## 从旧架构迁移

| 旧文件 | 新位置 |
|--------|--------|
| `code_atom.py` | `source_analysis/code_ast_atom.py` |
| `planning/binding_candidates.py` | `source_analysis/` + `document_planner/` |
| `planning/document_block_plans.py` | `source_analysis/graph_entry_scope.py` |
| `planning/program_document_plan.py` | `document_planner/` |
| `planning/context_serializer.py` | 删除 (类型自带序列化) |
| `planning/validator.py` | `document_planner/plan_validator.py` + `semantic/result_validator.py` |
| `graph/document_sync_workflow.py` | `execution/job_executor.py` |
| `runtime/binding_only.py` | `execution/job_executor.py` (同一编排器) |
| `context/builder.py` | `platform_mcp/` readers |
| `mcp_context/` | `model_context_mcp/` |

## 支撑模块

以下模块不属于绑定管线，但 agent-service 仍在使用。此处只注记职责，不展开设计。

### 基础设施（稳定）

| 目录 | 职责 | 说明 |
|------|------|------|
| `clients/` | MCP 协议 + 委托鉴权 | 所有 MCP 工具调用的底层实现 |
| `providers/` | DeepSeek API 封装 | `base.py` 协议 + `deepseek.py` 实现 |
| `persistence/` | 数据库层 | job/unit 的创建、认领、心跳、完成/失败 |
| `profiling/` | 内存分析 | 记录 agent 运行时的内存消耗 |
| `tracing/` | 链路追踪 | 每次 MCP 工具调用和模型调用的耗时日志 |
| `api/` | FastAPI 路由 | `agent_jobs.py`（job CRUD）、`agent_runs.py`（运行状态查询） |
| `config.py` | 配置 | 从 `.env` 读取所有 agent 设置 |

### PROJECT_DISCOVERY（独立于绑定管线）

项目发现是**绑定之前的独立阶段**——扫描仓库文件，用 DeepSeek 规划语义单元，把结果写入 DB 供后续 agent 消费。

| 文件 | 职责 |
|------|------|
| `runtime/project_discovery.py` | 扫描仓库 → 文件分类 → 调 DeepSeek → 写 DB |
| `runtime/file_classification.py` | 文件分类（代码/文本/二进制/跳过） |
| `runtime/semantic_planner.py` | DeepSeek 单元规划结果物化为 job unit |
| `runtime/unit_grouping.py` | 语义单元分组 |
| `planning/deepseek_unit_planner.py` | 调 DeepSeek 规划语义单元，含修复循环 |
| `planning/unit_plan_validator.py` | 校验 DeepSeek 单元规划输出 |
| `schemas/unit_plans.py` | UnitPlan 数据模型 |
| `prompts/project_unit_planning_v1.md` | DeepSeek 单元规划的 prompt 模板 |

### 过渡文件（不便重构，等待协议升级）

这些文件不含绑定逻辑，只是 MCP 协议层的序列化格式。等 knowledge-core 的 MCP 协议升级后自然淘汰。

| 文件 | 谁在用 | 为什么不动 |
|------|--------|-----------|
| `graph/workflow.py` | `api/agent_runs.py` | 独立的 agent 运行查询，和绑定无关 |
| `graph/state.py` | `graph/workflow.py` | 旧 AgentState 类型，同上 |
| `schemas/plans.py` | `clients/mcp_client.py` | 旧 AgentPlan，MCP 协议兼容层 |
| `schemas/context.py` | `schemas/runs.py` | 旧 ContextBundle，同上 |
| `schemas/runs.py` | `api/agent_runs.py` | 运行状态查询的数据模型 |
| `runtime/delegated_mcp_client.py` | `worker.py` | MCP 鉴权包装，基础设施 |

## 目录总览

```
agent-service/app/
│
├── execution/              编排层 (新)
├── platform_mcp/           边界A: MCP读写 (新)
├── source_selection/       文件粗筛 (新)
├── source_analysis/        阶段1: 代码结构分析 (新)
├── model_context_mcp/      边界B: DeepSeek 读快照 (新)
├── semantic/               阶段2: 语义补充 (新)
├── document_planner/       阶段3: 文档规划+绑定 (新)
│
├── clients/                🔧 MCP协议 (基础设施)
├── providers/              🔧 DeepSeek API (基础设施)
├── persistence/            🔧 数据库 (基础设施)
├── profiling/              🔧 内存分析 (基础设施)
├── tracing/                🔧 链路追踪 (基础设施)
├── api/                    🔧 HTTP路由 (基础设施)
│
├── planning/               📦 PROJECT_DISCOVERY (独立于绑定)
├── runtime/                📦 项目发现 + 委托 + 文件分类
├── prompts/                📦 DeepSeek prompt 模板
│
├── graph/                  🔄 过渡 (agent_runs用)
├── schemas/                🔄 过渡 (plans/context等MCP兼容层)
│
└── config.py               ⚙️ 配置入口
```
