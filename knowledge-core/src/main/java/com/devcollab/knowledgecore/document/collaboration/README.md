# Collaboration — 协同编辑模块

管理 WebSocket 协同编辑的操作同步与操作日志。

## 职责

- **协同操作管理**：接收、存储、分发协同编辑操作（OT/CRDT 风格）
- **操作日志**：`DocumentOperationLog` 记录每次编辑的原子操作，用于审计和冲突恢复
- **版本确认**：操作应用后更新操作状态

## 包结构

```
collaboration/
├── domain/          DocumentCollaborationOperation, DocumentOperationLog, 仓储接口
├── application/     DocumentCollaborationOperationService, Command
├── api/             DocumentCollaborationOperationController, Request/Response DTO
└── infrastructure/  Jdbc + InMemory 仓储实现
```

## 核心类型

| 类 | 说明 |
|----|------|
| `DocumentCollaborationOperation` | 协同编辑操作聚合 |
| `DocumentCollaborationOperationPayload` | 操作负载（操作类型 + 数据） |
| `DocumentOperationLog` | 操作审计日志 |
