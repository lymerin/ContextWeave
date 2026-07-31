package com.devcollab.knowledgecore.document.core.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// DocumentBlock 仓储接口，定义持久化契约，由基础设施层用 JDBC 实现
// 通用 CRUD 不检查版本，updateContentIfVersionMatches / deleteIfVersionMatches 通过乐观锁防并发冲突
public interface DocumentBlockRepository {

    // 保存单个 Block，新增或更新，不检查版本
    DocumentBlock save(DocumentBlock block);

    // 批量保存 Block，用于导入文档等场景
    List<DocumentBlock> saveAll(List<DocumentBlock> blocks);

    // 按 ID 只读查询
    Optional<DocumentBlock> findById(UUID blockId);

    // 按 ID 查询用于后续更新，默认走乐观锁，子类可覆写为 SELECT ... FOR UPDATE
    default Optional<DocumentBlock> findByIdForUpdate(UUID blockId) {
        return findById(blockId);
    }

    // 查询某文档下全部 Block，按 sortOrder 升序排列
    List<DocumentBlock> findAllByDocumentId(UUID documentId);

    // 原子更新——仅当版本匹配才写入（WHERE id=? AND version=?），不匹配返回 empty
    // 乐观锁核心
    Optional<DocumentBlock> updateContentIfVersionMatches(
            UUID blockId,
            String text,
            int contentSchemaVersion,
            String contentJson,
            Instant updatedAt,
            long expectedVersion
    );

    // 原子删除——仅当版本匹配才执行，防止并发覆盖
    // 乐观锁删除
    boolean deleteIfVersionMatches(UUID blockId, long expectedVersion);

    // 强制删除，不检查版本
    void deleteById(UUID blockId);
}
