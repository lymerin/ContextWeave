package com.devcollab.knowledgecore.document.review.infrastructure;

import com.devcollab.knowledgecore.document.review.domain.DocumentReviewAction;
import com.devcollab.knowledgecore.document.review.domain.DocumentReviewRecord;
import com.devcollab.knowledgecore.document.review.domain.DocumentReviewRecordRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcDocumentReviewRecordRepository
        implements DocumentReviewRecordRepository {

    private static final RowMapper<DocumentReviewRecord> ROW_MAPPER =
            (rs, rowNum) -> new DocumentReviewRecord(
                    rs.getObject("id", UUID.class),
                    rs.getObject("document_id", UUID.class),
                    DocumentReviewAction.valueOf(rs.getString("action")),
                    rs.getString("comment"),
                    rs.getObject("operator_user_id", UUID.class),
                    rs.getTimestamp("created_at").toInstant()
            );

    private final JdbcTemplate jdbcTemplate;

    public JdbcDocumentReviewRecordRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public DocumentReviewRecord save(DocumentReviewRecord record) {
        jdbcTemplate.update("""
                        INSERT INTO document_review_records
                            (id, document_id, action, comment,
                             operator_user_id, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                record.id(),
                record.documentId(),
                record.action().name(),
                record.comment(),
                record.operatorUserId(),
                Timestamp.from(record.createdAt()));
        return record;
    }

    @Override
    public List<DocumentReviewRecord> findAllByDocumentId(UUID documentId) {
        return jdbcTemplate.query("""
                        SELECT * FROM document_review_records
                         WHERE document_id = ?
                         ORDER BY created_at DESC
                        """,
                ROW_MAPPER,
                documentId
        );
    }
}
