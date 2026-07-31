package com.devcollab.knowledgecore.document.review.domain;

// 评审问题类型
public enum ReviewIssueType {
    REQUIREMENT_GAP,    // 需求缺失：文档未覆盖必要的功能需求
    API_CONTRACT,       // API 契约：接口定义与实现不一致
    SECURITY,           // 安全性：权限、认证、数据保护相关问题
    PERFORMANCE,        // 性能：响应时间、吞吐量、资源使用
    CONSISTENCY,        // 一致性：与其他文档或模块存在矛盾
    STYLE,              // 风格：格式、措辞、命名等规范问题
    OTHER               // 其他未分类问题
}
