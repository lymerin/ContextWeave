package com.devcollab.knowledgecore.document.review.domain;

import java.time.Instant;
import java.util.UUID;

// 评审问题 —— 文档评审过程中发现的具体问题，不可变领域对象
// 与 DocumentVersion 关联（评审在特定版本上执行），assigneeId 为问题负责人
public record ReviewIssue(
        UUID id,                        // 问题唯一标识
        UUID documentVersionId,         // 相关联的文档版本 ID
        ReviewIssueType type,           // 问题类型：需求缺失 / API 契约 / 安全 / 性能 / 一致性 / 风格 / 其他
        ReviewIssueSeverity severity,   // 严重程度：LOW / MEDIUM / HIGH / BLOCKER
        ReviewIssueStatus status,       // 问题状态：OPEN → RESOLVED / ACCEPTED / REJECTED
        UUID assigneeId,                // 问题负责人用户 ID
        String title,                   // 问题标题
        String description,             // 问题详细描述
        UUID createdBy,                 // 提出者用户 ID
        Instant createdAt               // 创建时间
) {
}
