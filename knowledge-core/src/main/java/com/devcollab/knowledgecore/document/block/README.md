# Block — 块内容管理模块

管理 DocumentBlock 的结构化内容编解码、格式转换与 Markdown 导入。

## 职责

- **Tiptap JSON 编解码**：序列化/反序列化富文本内容，提取纯文本用于搜索
- **结构化 DTO**：`DocumentBlockStructureDto` / `DocumentStructureDto` 用于 MCP 工具返回文档结构
- **Markdown 转换**：将 Markdown 文本转换为 Tiptap 文档模型 JSON

## 包结构

```
block/
├── application/     DocumentBlockContentCodec, DocumentBlockContentFormat, MarkdownToTiptapConverter, DTO
└── api/             DocumentBlockContentRequest/Response, DocumentBlockStructureResponse
```

## 核心类型

| 类 | 说明 |
|----|------|
| `DocumentBlockContentCodec` | 编解码 Tiptap JSON ↔ 纯文本 |
| `DocumentBlockContentFormat` | Tiptap 格式版本定义（当前 V1） |
| `MarkdownToTiptapConverter` | Markdown → Tiptap JSON 转换 |
| `DocumentStructureDto` | 文档结构摘要 DTO |
