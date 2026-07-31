# Version — 版本管理模块

管理文档的发布快照，实现版本回溯与历史查看。

## 职责

- **发布快照**：文档每次 PUBLISHED 时冻结一份不可变副本
- **版本号管理**：自动分配递增版本号（`nextVersionNo`）
- **状态切换**：新版本发布时将旧 CURRENT 置为 SUPERSEDED

## 包结构

```
version/
├── domain/          DocumentVersion, DocumentVersionStatus, DocumentVersionRepository
├── api/             DocumentVersionResponse
└── infrastructure/  Jdbc + InMemory 仓储实现
```

## 核心类型

| 类 | 说明 |
|----|------|
| `DocumentVersion` | 发布快照聚合，含 snapshotPayload |
| `DocumentVersionStatus` | CURRENT（当前）/ SUPERSEDED（历史） |
| `DocumentVersionRepository` | 快照持久化接口 |
