package com.devcollab.knowledgecore.document.core.domain;

// 文档评审状态，Agent 提交 → 人审批 → 发布/驳回
public enum DocumentReviewStatus {
    DRAFT,      // 草稿（可编辑）
    IN_REVIEW,  // Agent 已提交，等待人工审核
    PUBLISHED,  // 审核通过，已发布
    REJECTED,   // 审核驳回，回退到草稿
    DEPRECATED  // 已废弃（不可再编辑）
}
