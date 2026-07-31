package com.devcollab.knowledgecore.document.core.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Document 聚合根的仓储接口，定义持久化契约，由基础设施层用 JDBC 实现
// findByIdForUpdate 默认走乐观锁，子类可覆写为 SELECT ... FOR UPDATE 加悲观锁
public interface DocumentRepository {

    // 保存文档，ID 已存在则更新，否则新增
    Document save(Document document);

    // 按 ID 只读查询
    Optional<Document> findById(UUID documentId);

    // 按 ID 查询用于后续更新，默认走乐观锁，子类可覆写为 SELECT ... FOR UPDATE
    default Optional<Document> findByIdForUpdate(UUID documentId) {
        return findById(documentId);
    }

    // 按工作空间查询全部文档
    List<Document> findAllByWorkspaceId(UUID workspaceId);

    // 按 ID 删除文档
    void deleteById(UUID documentId);
}
