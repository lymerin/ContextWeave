package com.devcollab.knowledgecore.document.core.infrastructure;

import com.devcollab.knowledgecore.document.core.domain.Document;
import com.devcollab.knowledgecore.document.core.domain.DocumentRepository;
import com.devcollab.knowledgecore.document.core.domain.DocumentReviewStatus;
import com.devcollab.knowledgecore.document.core.domain.DocumentType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcDocumentRepository implements DocumentRepository {

    private static final RowMapper<Document> DOCUMENT_ROW_MAPPER =
            (rs, rowNum) -> new Document(
                    rs.getObject("id", UUID.class),
                    rs.getObject("workspace_id", UUID.class),
                    rs.getObject("parent_document_id", UUID.class),
                    rs.getString("title"),
                    DocumentType.valueOf(rs.getString("document_type")),
                    DocumentReviewStatus.valueOf(rs.getString("review_status")),
                    rs.getObject("created_by", UUID.class),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant()
            );

    private final JdbcTemplate jdbcTemplate;

    public JdbcDocumentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Document save(Document document) {
        int updated = jdbcTemplate.update("""
                        UPDATE documents
                           SET parent_document_id = ?,
                               title = ?,
                               document_type = ?,
                               review_status = ?,
                               updated_at = ?
                         WHERE id = ?
                        """,
                document.parentDocumentId(), document.title(),
                document.documentType().name(),
                document.reviewStatus().name(),
                Timestamp.from(document.updatedAt()), document.id());

        if (updated == 0) {
            jdbcTemplate.update("""
                            INSERT INTO documents
                                (id, workspace_id, parent_document_id, title,
                                 document_type, review_status, created_by,
                                 created_at, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    document.id(), document.workspaceId(),
                    document.parentDocumentId(), document.title(),
                    document.documentType().name(),
                    document.reviewStatus().name(),
                    document.createdBy(), Timestamp.from(document.createdAt()),
                    Timestamp.from(document.updatedAt()));
        }
        return document;
    }

    @Override
    public Optional<Document> findById(UUID documentId) {
        return jdbcTemplate.query(
                "SELECT * FROM documents WHERE id = ?",
                DOCUMENT_ROW_MAPPER,
                documentId
        ).stream().findFirst();
    }

    @Override
    public Optional<Document> findByIdForUpdate(UUID documentId) {
        return jdbcTemplate.query(
                "SELECT * FROM documents WHERE id = ? FOR UPDATE",
                DOCUMENT_ROW_MAPPER,
                documentId
        ).stream().findFirst();
    }

    @Override
    public List<Document> findAllByWorkspaceId(UUID workspaceId) {
        return jdbcTemplate.query("""
                        SELECT * FROM documents
                         WHERE workspace_id = ?
                         ORDER BY created_at, id
                        """,
                DOCUMENT_ROW_MAPPER,
                workspaceId
        );
    }

    @Override
    public void deleteById(UUID documentId) {
        jdbcTemplate.update(
                "DELETE FROM documents WHERE id = ?",
                documentId
        );
    }
}
