package com.devcollab.knowledgecore.document.core.domain;

// 文档类型，覆盖软件开发生命周期的各阶段产物
public enum DocumentType {
    REQUIREMENT,    // 需求文档：用户故事、功能需求、验收标准
    API,            // API 接口文档：REST/GraphQL/gRPC 接口定义
    ARCHITECTURE,   // 架构设计文档：系统架构图、技术选型、模块划分
    DATABASE,       // 数据库文档：表结构、ER 图、索引策略、迁移说明
    FRONTEND,       // 前端文档：组件树、路由设计、状态管理、UI 规范
    BACKEND,        // 后端文档：服务分层、业务逻辑、中间件、定时任务
    TEST,           // 测试文档：测试策略、测试用例、覆盖率报告
    DEPLOYMENT,     // 部署运维文档：CI/CD 流水线、环境配置、监控告警
    ADR             // 架构决策记录：技术决策的背景、方案对比、理由与后果
}
