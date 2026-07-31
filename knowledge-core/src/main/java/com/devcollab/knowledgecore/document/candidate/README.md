# Candidate — AI 候选匹配模块

为 AI Agent 提供基于代码路径的文档候选匹配能力。

## 职责

- **候选匹配**：根据代码路径、仓库信息，匹配可能相关的现有文档
- **匹配理由**：`DocumentCandidateMatchReason` 解释为什么某份文档被认为是候选
- **MCP 工具支撑**：为 `devcollab.document.find_candidates` 提供后端逻辑

## 包结构

```
candidate/
├── application/     DocumentCandidateApplicationService, DocumentCandidateItem/Result/MatchReason
└── api/             DocumentCandidateController, Response DTO
```

## 核心类型

| 类 | 说明 |
|----|------|
| `DocumentCandidateApplicationService` | 候选匹配服务 |
| `DocumentCandidateItem` | 单个候选文档项 |
| `DocumentCandidateResult` | 候选匹配结果集 |
| `DocumentCandidateMatchReason` | 匹配理由（路径相似度、语义相关性等） |
