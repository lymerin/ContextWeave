package com.devcollab.knowledgecore.document.review.domain;

import java.util.List;
import java.util.UUID;

// 审核记录仓储接口，定义持久化契约
public interface DocumentReviewRecordRepository {

    // 保存审核记录
    DocumentReviewRecord save(DocumentReviewRecord record);

    // 查询某文档的全部审核历史
    List<DocumentReviewRecord> findAllByDocumentId(UUID documentId);
}
