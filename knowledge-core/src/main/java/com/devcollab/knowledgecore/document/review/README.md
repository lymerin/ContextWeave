# Review — 审核记录模块

管理文档评审流程中的审核动作记录。只保留 Agent→人 的审批链路，无 Issue 追踪。

## 职责

- **审核动作**：`DocumentReviewAction`（SUBMITTED / APPROVED / REJECTED）
- **审核命令**：`ReviewDocumentCommand` 触发文档评审状态变更
- **审计日志**：通过 `DocumentOperationLog`（timeline 端点）统一记录，无独立 ReviewRecord 表

## 包结构

```
review/
├── domain/          DocumentReviewAction
├── application/     ReviewDocumentCommand
└── api/             ReviewDocumentRequest
```

## 核心类型

| 类 | 说明 |
|----|------|
| `DocumentReviewAction` | 审核动作：SUBMITTED / APPROVED / REJECTED |
| `ReviewDocumentCommand` | 审核命令（comment） |
| `ReviewDocumentRequest` | 审核请求 DTO |
