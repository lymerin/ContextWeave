package com.devcollab.knowledgecore.document.review.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

// ReviewIssue 仓储接口，定义持久化契约
public interface ReviewIssueRepository {

    // 保存问题（新增或更新）
    ReviewIssue save(ReviewIssue issue);

    // 按 ID 查询
    Optional<ReviewIssue> findById(UUID issueId);

    // 查询某文档版本下的全部问题
    List<ReviewIssue> findAllByDocumentVersionId(UUID documentVersionId);
}
