# ContextWeave 运行时内存剖析与链路监控

## 用途与边界

本工具用同一个 `runId` 对齐三类数据：主机与 Docker 容器样本；Knowledge Core、Java Worker、Agent Service、Agent Worker 运行时样本；仓库同步、项目发现、规划、文档/Binding Proposal 和 Review 构建阶段事件。

它帮助回答“哪个服务达到峰值”“哪个阶段出现明显增量”“任务结束后是否短期回落”和“连续任务基线是否增长”。它不采集源代码、Prompt、模型响应、文档正文、令牌或认证头，不自动诊断内存泄漏，也不代替 JFR、Heap Dump、Python allocation snapshot 或正式 APM。

监控默认关闭，不写数据库、Redis 或 Kafka，不改变 Job、Binding、Document、Review、重试和事务语义。

## 配置

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `DEVCOLLAB_MEMORY_PROFILE_ENABLED` | `false` | 只有设为 `true` 才创建线程和文件 |
| `DEVCOLLAB_MEMORY_PROFILE_RUN_ID` | 空 | 同一轮所有服务使用同一值；启用且为空时生成 UTC 时间 ID |
| `DEVCOLLAB_MEMORY_PROFILE_OUTPUT_DIR` | `.data/memory-profile` | 输出根目录 |
| `DEVCOLLAB_MEMORY_PROFILE_INTERVAL_MS` | `1000` | 采样间隔，最小 500ms |
| `DEVCOLLAB_MEMORY_PROFILE_QUEUE_CAPACITY` | `1024` | Java/Python 有界写队列容量 |

`runId` 仅允许字母、数字、点、短横线和下划线，最长 64 个字符。第一版约定一个部署实例同一时间只进行一个 profiling run。

## 启动采集

工具依赖独立安装，不属于前端或业务启动依赖：

```powershell
py -m pip install -r tools/memory-profile/requirements.txt
```

Windows PowerShell：

```powershell
$env:DEVCOLLAB_MEMORY_PROFILE_ENABLED="true"
$env:DEVCOLLAB_MEMORY_PROFILE_RUN_ID="devcollab-self-001"
$env:DEVCOLLAB_MEMORY_PROFILE_OUTPUT_DIR=".data/memory-profile"
$env:DEVCOLLAB_MEMORY_PROFILE_INTERVAL_MS="1000"

py tools/memory-profile/profile_run.py collect `
  --run-id devcollab-self-001 `
  --output .data/memory-profile `
  --interval-ms 1000
```

Linux/macOS：

```bash
export DEVCOLLAB_MEMORY_PROFILE_ENABLED=true
export DEVCOLLAB_MEMORY_PROFILE_RUN_ID=devcollab-self-001
export DEVCOLLAB_MEMORY_PROFILE_OUTPUT_DIR=.data/memory-profile
export DEVCOLLAB_MEMORY_PROFILE_INTERVAL_MS=1000

python tools/memory-profile/profile_run.py collect \
  --run-id devcollab-self-001 \
  --output .data/memory-profile \
  --interval-ms 1000
```

保持这些环境变量后，用项目既有方式启动 ContextWeave。Docker 中的 `agent-service` 和 `agent-worker` 已将宿主 `.data` 挂载到 `/app/.data`，因此与宿主采集器写入同一个 run 目录。Java 服务在宿主启动时直接读取相同变量。

可使用 `--duration-seconds 300` 自动结束；未设置时按 `Ctrl+C` 优雅停止。Docker 不可用不会中止主机采样，错误只有限次写入 `collector-errors.jsonl`。

## 阶段映射

| 记录阶段 | 当前真实边界 |
|---|---|
| `JOB` | Java Worker 接收并处理 Git 仓库 Kafka 事件 |
| `REPOSITORY_SYNC` | JGit clone/fetch 到投影完成 |
| `FILE_SCAN` | Git TreeWalk 文件扫描 |
| `CODE_GRAPH` | Java 代码符号和依赖图分析 |
| `PROJECT_INDEX` | Agent Worker 项目文件发现与索引构建 |
| `CANDIDATE_BUILD` | 语义 Unit 上下文与候选文档构建 |
| `PLANNER` | DeepSeek 项目 Unit Planner 调用（本轮未实际调用） |
| `UNIT_EXECUTION` | 单个 Agent Unit 工作流 |
| `DOCUMENT_PROPOSAL` | 文档变更方案生成 |
| `BINDING_PROPOSAL` | Block Binding 方案生成/修复 |
| `REVIEW_BUILD` | Review 请求构建并提交 |

全局关联键是 `runId`；现有 `jobId`、`repositoryId`、`revision` 和适用时的 `unitId` 用于业务关联，没有增加数据库或协议字段。嵌套阶段使用 `stageExecutionId` 与可获得的 `parentStageExecutionId`。

## 输出与 JSONL

```text
.data/memory-profile/<runId>/
├── host-samples.jsonl
├── docker-samples.jsonl
├── knowledge-core-<pid>-samples.jsonl
├── devcollab-worker-<pid>-samples.jsonl
├── devcollab-worker-<pid>-events.jsonl
├── agent-service-<pid>-samples.jsonl
├── agent-service-<pid>-events.jsonl
├── agent-worker-<pid>-samples.jsonl
├── agent-worker-<pid>-events.jsonl
├── summary.json
└── report.md
```

文件是一行一个 JSON 对象的 JSONL。公共字段包括 `schemaVersion=1`、`recordType`、UTC 时间、单调时间、`runId`、服务、实例和 PID。无法取得的平台指标写 `null`，不伪造为 0。阶段失败只写异常类型，完整堆栈仍进入既有日志。

业务线程只执行有界队列的非阻塞入队。队列满时丢弃监控记录并累计 `droppedRecords`，不阻塞业务。writer 使用 daemon 线程缓冲写入；写入失败后自动禁用本轮 recorder，避免无限错误日志。

## 生成和阅读报告

```powershell
py tools/memory-profile/profile_run.py summarize `
  --run-id devcollab-self-001 `
  --output .data/memory-profile
```

`summary.json` 包含 Run 时间范围、样本/丢弃数、缺失数据源、主机、容器、每个进程的 baseline/peak/end、Heap/Direct/Python allocation 峰值、GC 增量、线程峰值、阶段内 RSS 峰值和数据质量。

`report.md` 提供服务和阶段表：

- `baseline`：该序列第一个有效样本；
- `peak`：观察窗口最高样本；
- `end`：观察窗口最后一个有效样本；
- `peak delta`：阶段峰值相对阶段开始附近样本的变化；
- `short-term drop`：阶段峰值到阶段结束附近样本的回落。

一次任务结束后 RSS 没有立即回到起点，不等于内存泄漏。JVM/Python allocator、缓存、线程栈、native library 和操作系统工作集都可能保留内存。应在相同输入下连续执行多轮，确认基线持续增长，再使用 JFR、Heap Dump 或更细粒度分配分析验证。

## 下一轮压测模板（本轮不执行）

- 场景 A：冷启动后空闲 15 分钟；
- 场景 B：浏览已有代码和文档 20 分钟；
- 场景 C：对 ContextWeave 自身执行冷缓存仓库扫描与索引；
- 场景 D：使用固定 Provider 执行完整 Agent 链路；
- 场景 E：执行一次真实 DeepSeek 链路；
- 场景 F：相同大型任务连续执行 3 次，比较每次开始前和结束后的基线。

每个场景独立使用 runId，记录输入规模并避免同时运行其他高负载程序。报告只能表述“持续增长迹象”“任务后未完全回落”“需要 JFR/Heap Dump 进一步验证”，不能凭单次运行认定泄漏。

## 彻底关闭与数据安全

将 `DEVCOLLAB_MEMORY_PROFILE_ENABLED=false` 或删除该变量并重启服务即可关闭。关闭时不创建采样线程、writer 或文件；独立 collector 也需要停止。

记录可能含非敏感标识（runId、jobId、repositoryId、revision、unitId、服务/容器名、文件数量和字节数），分享前仍应按内部规范检查。`.data/` 已被 Git 忽略，`.data/memory-profile` 中的采样与报告不得提交。
