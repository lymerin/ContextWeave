# 给 Codex 的开工提示词（批次 4——阶段 2 基线补全，2026-08-10）

## 背景同步（务必先读）

你的上一批施工（a2c88c1）已审查通过。但 small 场景 403 的**真正根因**已被我方（Claude）定位并修复，不是你在报告中判断的 `asyncpg AmbiguousParameterError`：

**根因**：`tools/perf/run-baseline.py` 登录一次拿到 access token（TTL = 30 分钟）后全程复用。small 轮从 17:42:05 跑到 18:12:36（30 分 31 秒）时 token 恰好过期，轮询 `GET /api/v1/agent-jobs/{id}` 时 agent-service 向 knowledge-core 做 authorize 校验失败，401/403 被统一映射成 `MCP_PERMISSION_DENIED`，实验中断。incremental 轮 84 秒就跑完，token 未过期所以成功——这解释了"一轮成一轮败"。

**修复**：提交 `ca5b3b7`（已在 fix/perf-baseline 分支上）——ApiClient 记住注册时生成的账号密码，收到 401/403 自动重新登录换发 token 并重试一次。请先 `git pull` 或 `git reset --hard ca5b3b7` 同步（注意：ca5b3b7 在当前分支最新，位于你的 a2c88c1 之后）。

## 本批任务

1. **同步修复**：拉取 ca5b3b7 后快速目测 diff（`git show ca5b3b7`），确认无冲突。

2. **白天补跑基线**（不要熬夜跑，分次执行，每场景单独跑）：
   - `python tools/perf/run-baseline.py --scenarios small --rounds 20 --warmup 0`（预期每轮 ~85s，全程约 30 分钟）
   - 同上，依次补 `hundred`、`stress` 场景
   - `incremental` 已有 1 轮有效样本，可补到 20 轮
   - 每轮输出一行 JSON（脚本已实现），不要打印 DEBUG 日志
   - 如果某轮超过 10 分钟未结束，记录实际完成轮次并在 REPORT 中标注，不要无限等待

3. **更新 `tools/perf/REPORT-v1.md`**：
   - 用真实补全后的数据重写；未跑完的场景明确标注"未完成/未运行"，严禁伪造样本
   - 在报告"已知问题"中记录：403 根因 = access token 过期 + 修复方式（ca5b3b7）
   - 如果 small 重跑时**仍**出现 `AmbiguousParameterError`，那才是真实存在的 worker bug——单独定位并把日志与根因分析写进报告，不要与 403 混淆

4. **验证纪律**：
   - 每场景跑完后：`python -m py_compile` 相关文件 + `ruff` 检查
   - 不提交杂物（.data、日志、缓存、.env、baseline-*.json 的失败轮次可以保留作为证据，但 REPORT 中如实描述）

## 验收标准

- 4 个场景各有 ≥1 轮有效样本（优先补齐 small/hundred/stress）
- REPORT-v1.md 数据与 baseline-*.json 一致，阻塞项如实标注
- 提交消息中文，前缀 `功能：`/`修复：`

## 提醒

- 实验运行依赖 DeepSeek key（agent 调用），不消耗你的 token——跑实验是低 token 高墙钟时间，请合理规划，不要在用户休息时挂机等待批准。
- 涉及"运行长时间实验/构建镜像/重启容器"等操作，一次请求批准即可连续执行，不要每步都打断用户。
