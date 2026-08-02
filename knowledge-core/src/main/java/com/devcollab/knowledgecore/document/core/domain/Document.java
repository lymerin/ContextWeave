package com.devcollab.knowledgecore.document.core.domain;

import java.time.Instant;
import java.util.UUID;

// 文档聚合根 —— 保存文档元数据，实际内容由一组有序的 DocumentBlock 组成
// parentDocumentId 支持无限嵌套形成目录树，null 表示根级文档
// 生命周期：DRAFT → IN_REVIEW → PUBLISHED / REJECTED → DEPRECATED
public record Document(
        UUID id,                            // 文档唯一标识
        UUID workspaceId,                   // 所属工作空间 ID，多租户隔离
        UUID parentDocumentId,              // 父文档 ID，null 表示根级文档

        String title,                       // 文档标题
        DocumentType documentType,          // 文档分类：REQUIREMENT / API / ARCHITECTURE 等 9 种
        
        DocumentReviewStatus reviewStatus,  // 评审状态：DRAFT / IN_REVIEW / PUBLISHED / REJECTED / DEPRECATED

        UUID createdBy,                     // 创建者用户 ID
        Instant createdAt,                  // 创建时间
        Instant updatedAt                   // 最后更新时间
) {
}
