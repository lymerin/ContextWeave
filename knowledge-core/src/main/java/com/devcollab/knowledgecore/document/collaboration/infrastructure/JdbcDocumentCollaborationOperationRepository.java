package com.devcollab.knowledgecore.document.collaboration.infrastructure;

import com.devcollab.knowledgecore.document.core.domain.DocumentBlock;
import com.devcollab.knowledgecore.document.collaboration.domain.DocumentCollaborationOperation;
import com.devcollab.knowledgecore.document.collaboration.domain.DocumentCollaborationOperationPayload;
import com.devcollab.knowledgecore.document.collaboration.domain.DocumentCollaborationOperationRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcDocumentCollaborationOperationRepository
        implements DocumentCollaborationOperationRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RowMapper<DocumentCollaborationOperation> rowMapper;

    public JdbcDocumentCollaborationOperationRepository(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.rowMapper = (rs, rowNum) -> new DocumentCollaborationOperation(
                rs.getObject("id", UUID.class),
                rs.getObject("document_id", UUID.class),
                rs.getLong("document_sequence"),
                rs.getObject("client_operation_id", UUID.class),
                rs.getString("operation_type"),
                rs.getObject("operator_user_id", UUID.class),
                rs.getString("request_fingerprint"),
                readResult(rs.getString("result_payload")),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    @Override
    public Optional<DocumentCollaborationOperation> findByClientOperationId(
            UUID documentId,
            UUID clientOperationId
    ) {
        return jdbcTemplate.query("""
                        SELECT *
                          FROM document_collaboration_operations
                         WHERE document_id = ? AND client_operation_id = ?
                        """,
                rowMapper,
                documentId,
                clientOperationId
        ).stream().findFirst();
    }

    @Override
    public void lockDocument(UUID documentId) {
        jdbcTemplate.queryForObject("""
                        SELECT collaboration_sequence
                          FROM documents
                         WHERE id = ?
                         FOR UPDATE
                        """,
                Long.class,
                documentId
        );
    }

    @Override
    public long nextDocumentSequence(UUID documentId) {
        Long current = jdbcTemplate.queryForObject("""
                        SELECT collaboration_sequence
                          FROM documents
                         WHERE id = ?
                         FOR UPDATE
                        """,
                Long.class,
                documentId
        );
        if (current == null) {
            throw new IllegalStateException("Document sequence is unavailable");
        }
        long next = current + 1;
        jdbcTemplate.update("""
                        UPDATE documents
                           SET collaboration_sequence = ?
                         WHERE id = ?
                        """,
                next,
                documentId
        );
        return next;
    }

    @Override
    public long currentDocumentSequence(UUID documentId) {
        Long current = jdbcTemplate.queryForObject("""
                        SELECT collaboration_sequence
                          FROM documents
                         WHERE id = ?
                        """,
                Long.class,
                documentId
        );
        if (current == null) {
            throw new IllegalStateException("Document sequence is unavailable");
        }
        return current;
    }

    @Override
    public List<DocumentCollaborationOperation> findAfterSequence(
            UUID documentId,
            long afterSequence,
            long throughSequence,
            int limit
    ) {
        return jdbcTemplate.query("""
                        SELECT *
                          FROM document_collaboration_operations
                         WHERE document_id = ?
                           AND document_sequence > ?
                           AND document_sequence <= ?
                         ORDER BY document_sequence ASC
                         LIMIT ?
                        """,
                rowMapper,
                documentId,
                afterSequence,
                throughSequence,
                limit
        );
    }

    @Override
    public DocumentCollaborationOperation save(
            DocumentCollaborationOperation operation
    ) {
        jdbcTemplate.update("""
                        INSERT INTO document_collaboration_operations
                            (id, document_id, document_sequence,
                             client_operation_id, operation_type,
                             operator_user_id, request_fingerprint,
                             result_payload, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                operation.id(),
                operation.documentId(),
                operation.documentSequence(),
                operation.clientOperationId(),
                operation.operationType(),
                operation.operatorUserId(),
                operation.requestFingerprint(),
                writeResult(operation.result()),
                Timestamp.from(operation.createdAt())
        );
        return operation;
    }

    private String writeResult(DocumentCollaborationOperationPayload result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Failed to serialize collaboration operation result",
                    exception
            );
        }
    }

    private DocumentCollaborationOperationPayload readResult(String payload) {
        try {
            var tree = objectMapper.readTree(payload);
            if (tree.has("block") || tree.has("blocks")) {
                return objectMapper.treeToValue(
                        tree,
                        DocumentCollaborationOperationPayload.class
                );
            }
            // V15 originally stored UPDATE_TEXT as a bare block JSON.
            return DocumentCollaborationOperationPayload.single(
                    objectMapper.treeToValue(tree, DocumentBlock.class)
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Failed to deserialize collaboration operation result",
                    exception
            );
        }
    }
}
