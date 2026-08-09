# 阶段 2 性能基线与埋点报告 v1

## 代码状态

- 基线代码：`perf-baseline` = `df57e8f`
- 实施分支：`fix/perf-baseline`
- 分支 HEAD：`14bf0e8`
- 阶段 2 提交：
  - `62ca474` 功能：增加 agent 性能埋点
  - `ea541de` 功能：增加性能基线实验脚本
  - `14bf0e8` 修复：隔离性能指标轮次

## 环境

- OS：Windows，本地 Docker Compose DevCollab 栈
- CPU：Intel Core i7-14700HX，20 cores / 28 logical processors
- 内存：本次 PowerShell 普通权限读取 Win32_ComputerSystem 被拒绝，未记录总量
- Python：3.13.12
- Docker：29.5.3
- Agent 模型：`deepseek-v4-flash`
- DeepSeek endpoint：`https://api.deepseek.com`
- 入口：`http://localhost:8088`
- Agent 容器：`devcollab-agent-service`、`devcollab-agent-worker`

## 改动文件

- `agent-service/app/perf_metrics.py`
- `agent-service/app/api/perf.py`
- `agent-service/app/main.py`
- `agent-service/app/clients/mcp_client.py`
- `agent-service/app/clients/delegation_client.py`
- `agent-service/app/providers/deepseek.py`
- `agent-service/app/execution/job_executor.py`
- `agent-service/app/worker.py`
- `agent-service/tests/test_api.py`
- `tools/perf/run-baseline.py`
- `tools/perf/baseline-small.json`
- `tools/perf/baseline-incremental.json`
- `tools/perf/REPORT-v1.md`

## 埋点范围与读取方式

已加入的指标：

- MCP tool 调用计数、按 tool 计数、耗时 P50/P95/P99/mean
- MCP `ClientSession.initialize()` 计数
- delegation exchange 计数
- 阶段耗时：`read`、`parse`、`model_call`、`submit`
- 429 计数、重试计数、退避时长合计

读取方式：

- `POST /agent-api/api/v1/perf/metrics/reset`：清空共享 JSONL 指标窗口
- `GET /agent-api/api/v1/perf/metrics`：读取全局聚合
- `GET /agent-api/api/v1/perf/metrics?jobId=<job-id>`：读取单 job 聚合，避免其他 worker job 污染轮次
- 共享事件文件：容器内 `/app/.data/perf/agent-metrics.jsonl`，由 `./.data` volume 承载

验证：

- `python -m ruff check app tests/test_api.py`：通过
- `python -m pytest tests/test_api.py -q`：8 passed，2 warnings
- `python -m ruff check tools\perf\run-baseline.py`：通过
- `python -m py_compile tools\perf\run-baseline.py`：通过
- `python -m pytest tests -q`：失败，collection 阶段缺 `tree_sitter_java`（阶段 0 已记录的环境问题）
- `python -m mypy app`：失败，86 个既有类型错误/缺 stub，包含 `jsonschema`、`tree_sitter_java`、`tree_sitter` stub 缺失和多处旧代码类型问题
- `.\mvnw.cmd test`：失败，knowledge-core 263 tests run，1 failure，4 skipped；失败用例为 `GitKnowledgeIntegrationTests.shouldCreateAndQueryLegacyRangeAndSymbolBindings`（期望 7，实际 8），reactor 后续 worker/gateway/mcp-server 未执行
- Browser tests：本批未改前端，未运行浏览器测试

## 实验结果

本轮没有伪造完整 4×20 基线。原因：

- 默认仓库回退到 `https://github.com/XMU-Linjer/devcolab.git`，实际索引约 991 文件，不满足“小仓/100 文件仓”命名。
- 项目级初始化单轮成本过高：一次全仓回退运行耗时约 1504.881s，超过 10 分钟纪律阈值。
- 后续小仓 `octocat/Hello-World` 项目级运行未能进入终态：worker 在模型返回空 UnitPlan 后触发既有 `asyncpg.exceptions.AmbiguousParameterError: inconsistent types deduced for parameter $5`，外层 30 分钟超时；未产出有效 JSON 轮次。

### 小仓（<=20 文件）

样本文件：`tools/perf/baseline-small.json`（error record，仅作阻塞证据，不计为有效指标样本）

| 指标 | P50 | P95 | 均值 | 样本数 |
|---|---:|---:|---:|---:|
| tool 调用数 | N/A | N/A | N/A | 0 |
| session init 数 | N/A | N/A | N/A | 0 |
| delegation exchange 数 | N/A | N/A | N/A | 0 |
| MCP P95 ms | N/A | N/A | N/A | 0 |
| Unit 吞吐 units/s | N/A | N/A | N/A | 0 |
| 队列等待 s | N/A | N/A | N/A | 0 |
| 峰值 RSS bytes | N/A | N/A | N/A | 0 |
| 总耗时 s | N/A | N/A | N/A | 0 |
| 输出 bytes | N/A | N/A | N/A | 0 |
| 429 次数 | N/A | N/A | N/A | 0 |
| 重试次数 | N/A | N/A | N/A | 0 |

状态：未完成。`octocat/Hello-World` 实际索引 1 个文件；单轮等待约 30 分钟后记录 error record，轮询 `GET /agent-jobs/<id>` 返回 `MCP_PERMISSION_DENIED`，worker 日志同时显示模型空 UnitPlan 后的 fail_unit 持久化错误，未形成有效终态指标样本。

### 100 文件仓

| 指标 | P50 | P95 | 均值 | 样本数 |
|---|---:|---:|---:|---:|
| tool 调用数 | N/A | N/A | N/A | 0 |
| session init 数 | N/A | N/A | N/A | 0 |
| delegation exchange 数 | N/A | N/A | N/A | 0 |
| MCP P95 ms | N/A | N/A | N/A | 0 |
| Unit 吞吐 units/s | N/A | N/A | N/A | 0 |
| 队列等待 s | N/A | N/A | N/A | 0 |
| 峰值 RSS bytes | N/A | N/A | N/A | 0 |
| 总耗时 s | N/A | N/A | N/A | 0 |
| 输出 bytes | N/A | N/A | N/A | 0 |
| 429 次数 | N/A | N/A | N/A | 0 |
| 重试次数 | N/A | N/A | N/A | 0 |

状态：未运行。未配置 `DEVCOLLAB_PERF_HUNDRED_REPO_URL`，且默认回退仓库不满足 100 文件场景。

### 压力仓（约 479 文件）

| 指标 | P50 | P95 | 均值 | 样本数 |
|---|---:|---:|---:|---:|
| tool 调用数 | N/A | N/A | N/A | 0 |
| session init 数 | N/A | N/A | N/A | 0 |
| delegation exchange 数 | N/A | N/A | N/A | 0 |
| MCP P95 ms | N/A | N/A | N/A | 0 |
| Unit 吞吐 units/s | N/A | N/A | N/A | 0 |
| 队列等待 s | N/A | N/A | N/A | 0 |
| 峰值 RSS bytes | N/A | N/A | N/A | 0 |
| 总耗时 s | N/A | N/A | N/A | 0 |
| 输出 bytes | N/A | N/A | N/A | 0 |
| 429 次数 | N/A | N/A | N/A | 0 |
| 重试次数 | N/A | N/A | N/A | 0 |

状态：未形成有效场景样本。一次默认仓库项目级回退运行产生了单轮观察值：tool 调用 1411、session init 1411、delegation exchange 3139、MCP P95 110.449ms、总耗时 1504.881s、660 units、576 failed units、重试 1152、退避合计 345600000ms。该轮超过 10 分钟且标签为默认回退，不计入正式压力仓表。

### 1% 文件变化增量场景

样本文件：`tools/perf/baseline-incremental.json`

| 指标 | P50 | P95 | 均值 | 样本数 |
|---|---:|---:|---:|---:|
| tool 调用数 | 1.000 | 1.000 | 1.000 | 1 |
| session init 数 | 1.000 | 1.000 | 1.000 | 1 |
| delegation exchange 数 | 1.000 | 1.000 | 1.000 | 1 |
| MCP P95 ms | 60.244 | 60.244 | 60.244 | 1 |
| Unit 吞吐 units/s | 0.012 | 0.012 | 0.012 | 1 |
| 队列等待 s | 1.252 | 1.252 | 1.252 | 1 |
| 峰值 RSS bytes | 174986362 | 174986362 | 174986362 | 1 |
| 总耗时 s | 84.330 | 84.330 | 84.330 | 1 |
| 输出 bytes | 1306 | 1306 | 1306 | 1 |
| 429 次数 | 0.000 | 0.000 | 0.000 | 1 |
| 重试次数 | 0.000 | 0.000 | 0.000 | 1 |
| 重试退避 ms | 0.000 | 0.000 | 0.000 | 1 |

说明：该样本为 `CURRENT_FILE` on `README.md`，实际索引仓库文件数 991。它验证了 jobId 指标隔离和外部读取链路，但不是完整 20-30 轮基线。

## 可复现步骤

前置条件：

- 本地 DevCollab 栈通过 `http://localhost:8088` 可访问
- agent-service/agent-worker 使用本分支镜像重建
- `agent-service/.env` 配置 `DEEPSEEK_BASE_URL`、`DEEPSEEK_MODEL`、DeepSeek API key
- 对应场景仓库 URL 通过环境变量显式配置，避免默认回退污染场景

命令：

```powershell
docker compose up -d --build agent-service agent-worker

$env:DEVCOLLAB_PERF_SMALL_REPO_URL="https://..."
$env:DEVCOLLAB_PERF_SMALL_REPO_BRANCH="main"
$env:DEVCOLLAB_PERF_HUNDRED_REPO_URL="https://..."
$env:DEVCOLLAB_PERF_HUNDRED_REPO_BRANCH="main"
$env:DEVCOLLAB_PERF_STRESS_REPO_URL="https://..."
$env:DEVCOLLAB_PERF_STRESS_REPO_BRANCH="main"
$env:DEVCOLLAB_PERF_INCREMENTAL_REPO_URL="https://..."
$env:DEVCOLLAB_PERF_INCREMENTAL_REPO_BRANCH="main"
$env:DEVCOLLAB_PERF_INCREMENTAL_FILE_PATH="README.md"

python tools\perf\run-baseline.py
```

单场景低成本验证：

```powershell
$env:DEVCOLLAB_PERF_SCENARIOS="incremental"
$env:DEVCOLLAB_PERF_WARMUP="0"
$env:DEVCOLLAB_PERF_ROUNDS="1"
$env:DEVCOLLAB_PERF_INCREMENTAL_FILE_PATH="README.md"
python tools\perf\run-baseline.py
```

## 问题与决策

- 决策：埋点只加不重构；未引入 Prometheus 依赖，使用轻量计数器 + JSONL 汇聚 + HTTP 读取。
- 决策：因为 service/worker 分进程，指标事件落到共享 `.data/perf/agent-metrics.jsonl`，并以 `jobId` 过滤避免并发污染。
- 问题：默认仓库回退不满足四场景定义，正式报告不能把回退数据伪装成小仓/100 文件仓。
- 问题：项目级单轮 >10 分钟，按实验纪律不强行跑满 20-30 轮。
- 问题：worker 在 fail_unit 路径存在 asyncpg 参数类型推断错误，导致失败 job 无法终态化；本阶段不修业务/持久化行为，只记录为实验阻塞。
