# Tree — 文档树缓存模块

管理文档树的缓存与结构 DTO 构建。

## 职责

- **文档树缓存**：缓存工作空间下的文档层级树，加速前端渲染
- **结构组装**：配合 `DocumentStructureDto` 构建文档完整结构

## 包结构

```
tree/
└── application/     DocumentTreeCacheService
```

## 核心类型

| 类 | 说明 |
|----|------|
| `DocumentTreeCacheService` | 文档树缓存服务，封装 Redis/Caffeine 缓存逻辑 |
