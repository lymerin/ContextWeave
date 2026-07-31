package com.devcollab.knowledgecore.document.review.domain;

// 评审问题状态流转
public enum ReviewIssueStatus {
    OPEN,       // 已提出，待处理
    RESOLVED,   // 已解决，待确认
    ACCEPTED,   // 已确认解决
    REJECTED    // 已驳回（问题不成立）
}
