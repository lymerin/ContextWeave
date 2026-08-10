# Night Holding Issues

## 2026-08-10 第五批

1. `agent-service` 全量 `python -m pytest tests/ -x` 在 collection 阶段失败：
   - `tests/test_java_parser.py`
   - `ModuleNotFoundError: No module named 'tree_sitter_java'`
   - 与提示词中说明的既有环境问题一致；本批未安装依赖、未改 parser 路径。

2. `devcollab-mcp-server` 全量 `.\mvnw.cmd -pl devcollab-mcp-server test` 剩余 2 个换行断言失败：
   - `ContextBudgetPolicyTests.lineAndCharacterBudgetsReportRealOmissions`
   - `McpServerProtocolTests.authenticatedMemberCanReadWorkspaceAndCodeThroughOfficialClient`
   - 共同表现：期望内容无末尾换行，实际返回多一个末尾换行。
   - 该失败涉及既有 `code.read`/`ContextBudgetPolicy` 行截取行为；本批约束为只加骨架、不改既有业务行为，因此未修。

## 2026-08-10 第六批

1. `devcollab.code.read_batch` 暂未接入 agent hot path。
   - 原因：现有 `SourceReader` 依赖 `devcollab.code.read` 的 `startLine/endLine/totalLines/truncated` 分页续读语义；第五批新增 `read_batch` 是总字节 200KB 级截断契约，不能逐字节等价替换现有 400 行分页读取。
   - 当前处理：`AGENT_BATCH_TOOLS_ENABLED` 只接入 `devcollab.document.find_candidates_batch` 与 `devcollab.document.get_structure_batch`；`code.read_batch` 保留为可 review 的服务端契约，等待后续增强分页/范围语义后再接。

2. `StagedPipeline` 未完整替换 `JobExecutor` 的 read/parse/model/submit 控制流。
   - 原因：当前 `JobExecutor` 在每个 scope 内交织模型会话、文档候选、结构读取、binding 解析、plan 校验和提交，直接拆成四阶段队列会改变提交顺序、文档目标决策边界和错误归类。
   - 当前处理：已修复 `StagedPipeline.run` 异常取消泄漏；`AGENT_STAGED_PIPELINE_ENABLED` 采用提示词允许的降级路径，仅对模型调用启用 AIMD 429 退避，默认关闭。完整四阶段接线需单独规格化等价边界后施工。

3. 全量回归仍存在既有环境/历史失败。
   - `agent-service`: `python -m pytest tests/ -x` 在 collection 阶段失败，缺 `tree_sitter_java`。
   - `devcollab-mcp-server`: `.\mvnw.cmd -pl devcollab-mcp-server test` 为 100 run / 2 failures，仍是第五批记录的末尾换行断言差异。
