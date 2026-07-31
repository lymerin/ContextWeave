package com.devcollab.knowledgecore.document.review.domain;

import java.time.Instant;
import java.util.UUID;

// 文档审核记录 —— 记录每次审核动作（提交/通过/驳回），不可变领域对象
// 每条记录绑定一次 action + comment + operatorUserId，按时间排列形成审核历史
public record DocumentReviewRecord(
        UUID id,                        // 记录唯一标识
        UUID documentId,                // 被审核的文档 ID
        DocumentReviewAction action,    // 审核动作：SUBMITTED / APPROVED / REJECTED
        String comment,                 // 审核意见
        UUID operatorUserId,            // 操作者用户 ID
        Instant createdAt               // 操作时间
) {
}
