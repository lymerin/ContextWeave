package com.devcollab.knowledgecore.document.version.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

// 文档发布快照的仓储接口，管理不可变的发布版本
public interface DocumentVersionRepository {

    // 保存新快照
    DocumentVersion save(DocumentVersion version);

    // 将该文档所有 CURRENT 版本置为 SUPERSEDED，新版本发布前调用
    void supersedeCurrentVersions(UUID documentId);

    // 获取该文档下一个版本号（已发布最大版本号 + 1，默认从 1 开始）
    int nextVersionNo(UUID documentId);

    // 按 ID 查询快照
    Optional<DocumentVersion> findById(UUID versionId);

    // 查询某文档的全部历史快照
    List<DocumentVersion> findAllByDocumentId(UUID documentId);
}
