# ContextWeave 可观测性设计与本地验收 V0.2

## 文档信息

| 项目 | 内容 |
|---|---|
| 文档类型 | 可观测性架构与本地验收基线 |
| 文档状态 | 草案 |
| 版本 | V0.2 |
| 更新日期 | 2026-07-17 |
| 适用范围 | ContextWeave 本地演示环境、架构联调、故障定位与后续生产化设计 |
| 版本库状态 | 应提交 |

## 1. 结论与边界

本阶段接入的是本地演示级可观测性闭环，目标是让 Core、Worker、Gateway、Nginx 编排链路具备可验证的指标、日志和链路追踪入口。

已确认的本地闭环：

```text
Java 服务
  -> Actuator / Prometheus 指标
  -> Prometheus 抓取
  -> Grafana 展示

Java 服务
  -> OTLP traces
  -> OpenTelemetry Collector
  -> Tempo
  -> Grafana 查询

本地服务日志文件
  -> Grafana Alloy
  -> Loki
  -> Grafana 查询
```

本阶段不声明生产级高可用，不包含告警通知渠道、长期日志留存策略、跨机器采集、Nginx 访问日志结构化、Kafka lag exporter、JVM 细粒度调优或分布式采样策略。

## 2. 技术选型

| 能力 | 组件 | 当前职责 |
|---|---|---|
| 指标采集 | Spring Boot Actuator + Micrometer Prometheus | 暴露 `/actuator/prometheus` |
| 指标存储与查询 | Prometheus | 抓取 Core、Worker、Gateway 和 Collector 指标 |
| 日志采集 | Grafana Alloy | tail 本地演示日志文件 |
| 日志存储与查询 | Loki | 保存本地演示日志 |
| 链路追踪接收 | OpenTelemetry Collector | 接收 OTLP HTTP/gRPC trace |
| 链路追踪存储 | Tempo | 保存 trace，供 Grafana 查询 |
| 可视化 | Grafana | 统一查看 Metrics、Logs、Traces |

使用 Grafana Alloy 而不是 Promtail，是因为 Promtail 已进入维护/淘汰路径，新链路应优先使用 Alloy。

## 3. 本地端口与入口

| 组件 | 本地端口 | 用途 |
|---|---:|---|
| Nginx | 8088 | 统一浏览器入口 |
| Core | 8080 | REST API 与 Actuator |
| Worker | 8082 | Worker Actuator |
| Gateway | 8090 | WebSocket 与 Actuator |
| Prometheus | 9091 | 指标查询 |
| Loki | 3100 | 日志查询 API |
| Tempo | 3200 | Trace 查询 API |
| OpenTelemetry Collector | 4317 / 4318 | OTLP gRPC / HTTP |
| Grafana | 3000 | 可视化入口 |
| Alloy | 12345 | Alloy 本地状态页 |

## 4. 本地启动策略

`tools/local-demo.ps1` 支持 `-WithObservability` 开关。不开启时，只启动业务演示依赖；开启时，额外启动 Prometheus、Loki、Tempo、OpenTelemetry Collector、Alloy、Grafana。

设计原因：

- 可观测性对普通功能开发不是强依赖，默认不增加启动成本。
- 演示、联调和排错时可以一键打开完整观测链路。
- Trace exporter 失败不应阻塞业务请求，可观测性是辅助系统，不是业务权威路径。

## 5. 验收口径

本地联调脚本需要同时验证：

- Nginx 静态资源、SPA history fallback、Core API 反代可用。
- Gateway 两个 WebSocket 客户端可以完成 presence 与 editing 广播。
- Core、Worker、Gateway 的 `/actuator/health` 和 `/actuator/prometheus` 可访问。
- Prometheus 中 `devcollab-*` targets 为 up。
- OpenTelemetry Collector 能接收 span。
- Loki 能查询到本地演示日志。
- Tempo 能查询到 Core trace。
- Grafana `/api/health` 可用。

2026-07-17 本地验收结果：

```text
nginx-e2e PASS
gateway-e2e PASS
observability-e2e PASS
```

该结果只证明本机演示环境可用，不代表生产容量、跨机器部署或长期稳定性。

## 6. 故障演练口径

可观测性不能只验证“正常时有数据”，还需要验证“故障发生时能被发现，恢复后能被确认”。

本阶段新增 Worker 停机演练：

```text
读取 logs/local-demo/processes.json
  -> 停止脚本托管的 devcollab-worker 进程树
  -> 等待 Worker actuator 端口关闭
  -> 等待 Prometheus 中 up{job="devcollab-worker"} = 0
  -> 重新启动 Worker
  -> 等待 Worker actuator 恢复 UP
  -> 等待 Prometheus 中 up{job="devcollab-worker"} = 1
  -> 再运行 observability-e2e 总验收
```

2026-07-17 本地故障演练结果：

```text
fault-drill: Prometheus detected worker DOWN
fault-drill: worker recovered
observability-e2e PASS
fault-drill PASS
```

该演练证明 Prometheus 能识别 Worker 进程级故障和恢复；它暂不覆盖 Kafka 消费积压、DLQ、Nginx 反代失败、数据库故障或跨机器网络分区。

## 7. 已知风险与后续升级

| 风险 | 当前处理 | 后续升级 |
|---|---|---|
| Collector / Alloy 配置随版本变化 | 固定镜像版本，并用 E2E 校验启动 | 增加配置兼容性说明与版本升级检查 |
| 日志来源仍是本地文件 | Alloy tail `logs/local-demo` | 为 Nginx 与 Java 服务补结构化日志字段 |
| 告警只定义规则未接通知渠道 | Prometheus rules 预留 | 接入 Alertmanager 或 Grafana Alerting |
| Kafka lag 未纳入指标 | 暂不覆盖 | 接入 Kafka exporter 或 Worker 消费位点指标 |
| Trace 采样仅适合本地 | 本地默认可提高采样 | 生产按流量和成本设置采样率 |

## 8. MCP 调用观测与脱敏

MCP Tool 调用至少记录或度量以下字段：`toolName`、`toolCallId`、`userId`、`workspaceId`、`repositoryId`、`latencyMs`、`inputSize`、`outputSize`、`truncated`、`resultStatus` 和 `errorCode`。Trace 使用稳定 Tool 名称和低基数状态属性；完整文件路径不得作为 Metric 标签。

日志与 Span 禁止记录 JWT、Authorization Header、Refresh Token、完整代码正文、完整文档正文、密钥和仓库凭证。审计只保存主体、对象 ID、结果、耗时、大小和截断状态，错误响应不得包含堆栈、SQL 或本地存储路径。

## 9. 参考文档

- `02-devcollab-system-architecture-v0.3.md`
- `03-devcollab-architecture-verification-v0.1.md`
- `90-local-architecture-integration-troubleshooting-v0.4.md`
