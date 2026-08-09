# ContextWeave Git 工程知识设计 V0.3

## 文档信息

| 项目 | 内容 |
|---|---|
| 文档类型 | Git 工程知识专项设计 |
| 文档状态 | 已被 V0.4 替代 |
| 版本 | V0.3 |
| 日期 | 2026-07-21 |
| 替代版本 | `12-devcollab-git-knowledge-design-v0.2.md` |
| 适用范围 | Knowledge Core、Worker、Kafka、PostgreSQL、Web、本地 Git 数据目录 |

> 本版本已由 `12-devcollab-git-knowledge-design-v0.4.md` 替代。

## 1. 结论与边界

工作区可以异步同步公开 GitHub 仓库，展示文件树、Commit 身份和文件级行数统计，并查看受限长度的行级 Patch。Git 数据用于代码—文档追溯与后续影响分析，不把 ContextWeave 变成代码编辑器。

首期只接受公开 HTTPS GitHub 仓库。Core 不在 HTTP 线程执行 Git 操作；JGit 克隆、提交遍历和 Diff 计算位于 Worker。私有凭证、GitHub 账号匹配、Webhook、完整大型 Patch 和多父合并比较仍不在本期范围。

## 2. 同步链路

```mermaid
flowchart LR
    A[管理员登记或同步仓库] --> B[Core 元数据与 Outbox]
    B --> C[Kafka git.events]
    C --> D[Worker JGit clone/fetch]
    D --> E[Commit 与第一父提交比较]
    E --> F[身份、文件状态、Edit、Patch 投影]
    F --> G[PostgreSQL]
    G --> H[Core API]
    H --> I[Web Git Log 与 Diff 弹窗]
```

已投影过的 Commit 在再次同步时更新身份和时间字段，并删除后重建该 Commit 的文件 Diff，保证旧的占位 `+0/-0` 可以被真实数据替换。

## 3. Commit 身份模型

| 字段 | 含义 |
|---|---|
| `author_name/email/authored_at` | 原始改动作者及其署名时间 |
| `committer_name/email/occurred_at` | 创建当前 Commit 对象的人及提交时间 |
| `parent_commit_sha` | 当前 Diff 使用的第一父提交 |
| `commit_sha` | 当前 Commit 内容标识 |

Author 与 Committer 在 rebase、cherry-pick、合并或机器人提交中可能不同。姓名和邮箱是 Git Commit 自带元数据，不能据此证明其 GitHub 账号身份；账号头像与主页需要后续 GitHub API 映射。

## 4. Diff 语义

- 文本文件：JGit `FileHeader.toEditList()` 计算新增与删除行数。
- 行级内容：JGit `DiffFormatter.format()` 生成统一 Diff，数据库最多保存前 8,000 字符。
- 二进制文件：标记 `binary_file=true`，不伪造行数和 Patch。
- 新增、修改、删除：分别使用新路径或旧路径投影。
- 重命名：启用相似度检测，同时保存 `old_path` 与新 `path`；结果属于 Git 启发式判断。
- Merge Commit：首期只与第一父提交比较，并通过 `parent_commit_sha` 明示基准。

行数不是业务代码量的绝对真相。换行符、格式化、编码变化和重命名相似度都可能放大或改变统计，因此 UI 必须允许查看 Patch 和比较基准。

## 5. 数据与接口

V19 为 `git_changes` 增加作者邮箱、作者时间、提交者身份和父 Commit；为 `git_file_diffs` 增加二进制标记。既有仓库、文件索引、Change、Diff 和代码—文档绑定表继续使用。

`GET /api/v1/workspaces/{workspaceId}/git/repositories/{repositoryId}/changes` 返回完整身份与 Diff 字段。调试录入接口保持兼容并允许传入扩展字段；自动同步由 Worker 生成可信投影。

## 6. 安全与容量

- Patch 只来自已克隆仓库对象，不执行仓库代码。
- 公共邮箱属于仓库历史元数据，但前端只在成员权限内展示。
- Patch 单文件截断为 8,000 字符，避免列表接口和数据库无限膨胀。
- 本地仓库保存在 `.data/git-repositories/{workspaceId}/{repositoryId}`，不提交 Git。
- 大仓库的 Commit 数、文件数、网络时间和仓库体积继续受 Worker 配置限制。

## 7. 验收条件

- Flyway V19 可在 H2 测试与本地 PostgreSQL 执行。
- API 能区分 Author 与 Committer，并返回父 Commit。
- 真实 Git 测试覆盖文本新增、修改、删除、重命名和二进制修改。
- 文本行数与 Patch 一致，二进制文件不显示伪造 `+0/-0`。
- 再次同步可重建已有 Commit 的 Diff。
- 前端能够查看身份、增删行、原路径、比较基准和 Patch。

## 8. 后续升级

1. GitHub App/Webhook 增量同步与账号资料映射。
2. Patch 分页或对象存储，支持大 Diff 按需读取。
3. Merge Commit 多父视角选择和 PR aggregate diff。
4. 将变更文件与文档路径绑定结合，生成确定性的受影响文档清单。
