package com.devcollab.knowledgecore.document.version.domain;

import java.time.Instant;
import java.util.UUID;

// 文档发布快照 —— 文档每次 PUBLISHED 时冻结一份不可变副本，用于版本回溯和审计
// snapshotPayload 存储发布时刻的完整内容（Block 序列化），status 标记 CURRENT / SUPERSEDED
public record DocumentVersion(
        UUID id,                        // 快照唯一标识
        UUID documentId,                // 所属文档 ID
        int versionNo,                  // 发布版本号，从 1 递增
        String title,                   // 发布时的文档标题
        DocumentVersionStatus status,   // CURRENT（当前版本）/ SUPERSEDED（历史版本）
        String snapshotPayload,         // 发布时刻的文档完整内容快照
        UUID publishedBy,               // 发布者用户 ID
        Instant publishedAt             // 发布时间
) {
}
