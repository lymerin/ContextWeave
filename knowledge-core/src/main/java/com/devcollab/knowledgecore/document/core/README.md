# Core — 文档核心模块

文档系统的核心聚合，管理 Document 和 DocumentBlock 的完整生命周期。

## 职责

- **Document CRUD**：创建、更新、移动、删除文档，通过 `parentDocumentId` 构建文档树
- **DocumentBlock CRUD**：块级内容管理，支持 PARAGRAPH / HEADING / CODE / TODO 四种类型
- **类型系统**：`DocumentType`（需求/API/架构等 9 种）、`DocumentBlockType`、`DocumentReviewStatus`
- **评审流转**：DRAFT → IN_REVIEW → PUBLISHED / REJECTED → DEPRECATED → SUPERSEDED
- **乐观锁**：Block 通过 `version` 字段实现并发冲突检测

## 包结构

```
core/
├── domain/          Document, DocumentBlock, 枚举, 仓储接口
├── application/     DocumentApplicationService, DocumentBlockApplicationService, Command
├── api/             DocumentController, DocumentBlockController, Request/Response DTO
└── infrastructure/  Jdbc + InMemory 仓储实现
```

## 核心类型

| 类 | 说明 |
|----|------|
| `Document` | 文档聚合根，保存元数据 |
| `DocumentBlock` | 内容块，不可变值对象，version 乐观锁 |
| `DocumentType` | 文档分类枚举 |
| `DocumentBlockType` | 块类型枚举 |
| `DocumentReviewStatus` | 评审状态枚举 |
