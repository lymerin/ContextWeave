package com.devcollab.knowledgecore.document.collaboration.infrastructure;

import com.devcollab.knowledgecore.document.collaboration.domain.DocumentOperationLog;
import com.devcollab.knowledgecore.document.collaboration.domain.DocumentOperationLogRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcDocumentOperationLogRepository
        implements DocumentOperationLogRepository {

    private static final RowMapper<DocumentOperationLog> ROW_MAPPER =
            (rs, rowNum) -> new DocumentOperationLog(
                    rs.getObject("id", UUID.class),
                    rs.getObject("workspace_id", UUID.class),
                    rs.getObject("document_id", UUID.class),
                    rs.getString("action"),
                    rs.getString("message"),
                    rs.getObject("operator_user_id", UUID.class),
                    rs.getString("target_type"),
                    rs.getObject("target_id", UUID.class),
                    rs.getTimestamp("created_at").toInstant()
            );

    private final JdbcTemplate jdbcTemplate;

    public JdbcDocumentOperationLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public DocumentOperationLog save(DocumentOperationLog operationLog) {
        jdbcTemplate.update("""
                        INSERT INTO operation_logs
                            (id, workspace_id, document_id, action, message,
                             operator_user_id, target_type, target_id,
                             created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                operationLog.id(),
                operationLog.workspaceId(),
                operationLog.documentId(),
                operationLog.action(),
                operationLog.message(),
                operationLog.operatorUserId(),
                operationLog.targetType(),
                operationLog.targetId(),
                Timestamp.from(operationLog.createdAt()));
        return operationLog;
    }

    @Override
    public List<DocumentOperationLog> findAllByDocumentId(UUID documentId) {
        return jdbcTemplate.query("""
                        SELECT * FROM operation_logs
                         WHERE document_id = ?
                         ORDER BY created_at DESC
                        """,
                ROW_MAPPER,
                documentId
        );
    }
}
