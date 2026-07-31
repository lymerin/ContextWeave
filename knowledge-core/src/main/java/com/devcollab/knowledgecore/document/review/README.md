# Review — 审核管理模块

管理文档评审流程中的 Issue 提出、审核记录与状态流转。

## 职责

- **Review Issue**：在文档评审中发现的问题（类型、严重程度、状态流转）
- **审核记录**：`DocumentReviewRecord` 记录每次审核动作（通过/驳回/请求修改）
- **审核命令**：`ReviewDocumentCommand` 触发文档评审状态变更

## 包结构

```
review/
├── domain/          ReviewIssue, DocumentReviewRecord, 枚举（Type/Severity/Status/Action）
├── application/     ReviewIssueApplicationService, ReviewDocumentCommand
├── api/             ReviewIssueController, DocumentReviewRecordResponse
└── infrastructure/  Jdbc + InMemory 仓储实现
```

## 核心类型

| 类 | 说明 |
|----|------|
| `ReviewIssue` | 评审问题聚合 |
| `ReviewIssueType` | 问题类型（正确性、风格、安全等） |
| `ReviewIssueSeverity` | 严重程度（BLOCKER / MAJOR / MINOR） |
| `ReviewIssueStatus` | OPEN → RESOLVED / DISMISSED |
| `DocumentReviewAction` | 审核动作枚举 |
| `DocumentReviewRecord` | 审核记录 |
