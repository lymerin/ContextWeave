package com.devcollab.knowledgecore.document.core.domain;

import java.time.Instant;
import java.util.UUID;

// 文档内容块 —— 文档的最小内容单元，不可变领域对象
// text 存纯文本用于搜索索引，contentJson 存 Tiptap JSON 用于富文本渲染
// 通过 version 字段实现乐观锁，每次修改返回新实例并自增版本号
public record DocumentBlock(
        UUID id,                        // Block 唯一标识
        UUID documentId,                // 所属文档 ID
        DocumentBlockType type,         // 块类型：PARAGRAPH / HEADING / CODE / TODO
        String text,                    // 纯文本内容，用于搜索索引与摘要预览
        int contentSchemaVersion,       // 富文本 JSON 的格式版本号，用于未来数据迁移
        String contentJson,             // 富文本完整结构化数据，JSON 字符串形式
        int sortOrder,                  // 在文档内的排列序号，值越小越靠前
        long version,                   // 乐观锁版本号，每次修改自增 1
        UUID createdBy,                 // 创建者用户 ID
        Instant createdAt,              // 创建时间
        Instant updatedAt               // 最后更新时间
) {
    // 仅更新纯文本内容，用于简单段落编辑，不改变富文本 JSON
    public DocumentBlock updateText(String newText, Instant now) {
        return new DocumentBlock(
                id,
                documentId,
                type,
                newText,
                contentSchemaVersion,
                contentJson,
                sortOrder,
                version + 1,
                createdBy,
                createdAt,
                now
        );
    }

    // 同时更新纯文本与富文本 JSON，用于粘贴代码、插入表格等结构化内容编辑
    public DocumentBlock updateContent(
            String newText,
            int newContentSchemaVersion,
            String newContentJson,
            Instant now
    ) {
        return new DocumentBlock(
                id,
                documentId,
                type,
                newText,
                newContentSchemaVersion,
                newContentJson,
                sortOrder,
                version + 1,
                createdBy,
                createdAt,
                now
        );
    }

    // 仅调整排序位置，用于拖拽 Block 移动顺序，内容不变
    public DocumentBlock changeSortOrder(int newSortOrder, Instant now) {
        return new DocumentBlock(
                id,
                documentId,
                type,
                text,
                contentSchemaVersion,
                contentJson,
                newSortOrder,
                version + 1,
                createdBy,
                createdAt,
                now
        );
    }
}
