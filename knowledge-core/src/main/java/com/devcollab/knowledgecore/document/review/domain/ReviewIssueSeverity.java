package com.devcollab.knowledgecore.document.review.domain;

// 评审问题严重程度
public enum ReviewIssueSeverity {
    LOW,        // 轻微：格式、措辞等不影响内容正确性的问题
    MEDIUM,     // 中等：表述不清、信息不完整
    HIGH,       // 严重：内容错误或与设计不一致
    BLOCKER     // 阻断：必须修复才能通过评审
}
