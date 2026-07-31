package com.devcollab.knowledgecore.document.version.domain;

// 文档版本状态
public enum DocumentVersionStatus {
    CURRENT,    // 当前发布版本，每个文档仅一个
    SUPERSEDED  // 已被新版本替代的历史快照
}
