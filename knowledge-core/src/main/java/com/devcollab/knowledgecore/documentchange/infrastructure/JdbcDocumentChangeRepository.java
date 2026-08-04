package com.devcollab.knowledgecore.documentchange.infrastructure;

import com.devcollab.knowledgecore.documentchange.domain.DocumentChangeModel;
import com.devcollab.knowledgecore.documentchange.domain.DocumentChangeRepository;
import com.devcollab.knowledgecore.git.domain.BindingRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.devcollab.knowledgecore.documentchange.domain.DocumentChangeModel.*;

@Repository
public class JdbcDocumentChangeRepository implements DocumentChangeRepository {

    private static final RowMapper<ChangeRequest> REQUEST_MAPPER =
            (rs, rowNum) -> new ChangeRequest(
                    rs.getObject("id", UUID.class),
                    rs.getObject("workspace_id", UUID.class),
                    rs.getString("client_request_id"),
                    rs.getString("request_fingerprint"),
                    Status.valueOf(rs.getString("status")),
                    rs.getString("summary"),
                    rs.getString("rationale"),
                    SourceType.valueOf(rs.getString("source_type")),
                    rs.getObject("submitted_by", UUID.class),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getObject("reviewed_by", UUID.class),
                    instant(rs.getTimestamp("reviewed_at")),
                    rs.getString("rejection_reason")
            );

    private static final RowMapper<Operation> OPERATION_MAPPER =
            (rs, rowNum) -> new Operation(
                    rs.getObject("id", UUID.class),
                    rs.getObject("change_request_id", UUID.class),
                    rs.getString("client_operation_id"),
                    rs.getInt("sequence_number"),
                    OperationType.valueOf(rs.getString("operation_type")),
                    rs.getObject("document_id", UUID.class),
                    rs.getObject("created_document_operation_id", UUID.class),
                    rs.getObject("block_id", UUID.class),
                    rs.getObject("base_block_version", Long.class),
                    rs.getString("original_block_type"),
                    rs.getString("original_plain_text"),
                    rs.getObject("original_content_schema_version", Integer.class),
                    rs.getString("original_content_json"),
                    rs.getObject("original_sort_order", Integer.class),
                    rs.getString("proposed_document_title"),
                    rs.getString("proposed_document_type"),
                    rs.getObject("proposed_parent_document_id", UUID.class),
                    rs.getString("proposed_block_type"),
                    rs.getString("proposed_plain_text"),
                    rs.getObject("proposed_content_schema_version", Integer.class),
                    rs.getString("proposed_content_json")
            );

    private static final RowMapper<BindingProposal> BINDING_PROPOSAL_MAPPER =
            (rs, rowNum) -> new BindingProposal(
                    rs.getObject("id", UUID.class),
                    rs.getObject("change_request_id", UUID.class),
                    rs.getString("client_binding_proposal_id"),
                    rs.getInt("sequence_number"),
                    BindingAction.valueOf(rs.getString("action")),
                    rs.getObject("repository_id", UUID.class),
                    rs.getString("revision"),
                    rs.getString("file_path"),
                    com.devcollab.knowledgecore.git.domain.CodeAnchorKind.valueOf(
                            rs.getString("anchor_kind")
                    ),
                    rs.getString("symbol_key"),
                    rs.getObject("start_line", Integer.class),
                    rs.getObject("end_line", Integer.class),
                    rs.getObject("document_id", UUID.class),
                    rs.getObject("created_document_operation_id", UUID.class),
                    rs.getObject("block_id", UUID.class),
                    rs.getObject("created_block_operation_id", UUID.class),
                    rs.getObject("binding_id", UUID.class),
                    rs.getString("candidate_id"),
                    rs.getString("document_anchor_candidate_id"),
                    rs.getString("reason"),
                    nullableDouble(rs, "confidence"),
                    BindingRole.valueOf(rs.getString("binding_role")),
                    rs.getInt("binding_ordinal"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getString("bound_signature")
            );

    private static final RowMapper<Evidence> EVIDENCE_MAPPER =
            (rs, rowNum) -> new Evidence(
                    rs.getObject("id", UUID.class),
                    rs.getObject("change_request_id", UUID.class),
                    rs.getObject("operation_id", UUID.class),
                    rs.getObject("repository_id", UUID.class),
                    rs.getString("commit_hash"),
                    rs.getString("file_path"),
                    rs.getObject("start_line", Integer.class),
                    rs.getObject("end_line", Integer.class),
                    rs.getString("description"),
                    rs.getString("blob_sha"),
                    rs.getString("excerpt_text"),
                    rs.getString("excerpt_hash")
            );

    private final JdbcTemplate jdbcTemplate;

    public JdbcDocumentChangeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static Double nullableDouble(ResultSet resultSet, String column)
            throws SQLException {
        BigDecimal value = resultSet.getBigDecimal(column);
        return value == null ? null : value.doubleValue();
    }

    @Override
    public ChangeRequest saveRequest(ChangeRequest value) {
        jdbcTemplate.update("""
                INSERT INTO document_change_requests (
                    id, workspace_id, client_request_id, request_fingerprint,
                    status, summary, rationale, source_type, submitted_by,
                    created_at, reviewed_by, reviewed_at, rejection_reason
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                value.id(), value.workspaceId(), value.clientRequestId(),
                value.requestFingerprint(), value.status().name(),
                value.summary(), value.rationale(), value.sourceType().name(),
                value.submittedBy(), Timestamp.from(value.createdAt()),
                value.reviewedBy(), timestamp(value.reviewedAt()),
                value.rejectionReason());
        return value;
    }

    @Override
    public Operation saveOperation(Operation value) {
        jdbcTemplate.update("""
                INSERT INTO document_change_operations (
                    id, change_request_id, client_operation_id, sequence_number,
                    operation_type, document_id, created_document_operation_id,
                    block_id, base_block_version, original_block_type,
                    original_plain_text, original_content_schema_version,
                    original_content_json, original_sort_order,
                    proposed_document_title, proposed_document_type,
                    proposed_parent_document_id, proposed_block_type,
                    proposed_plain_text, proposed_content_schema_version,
                    proposed_content_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                value.id(), value.changeRequestId(),
                value.clientOperationId(), value.sequenceNumber(),
                value.operationType().name(), value.documentId(),
                value.createdDocumentOperationId(), value.blockId(),
                value.baseBlockVersion(), value.originalBlockType(),
                value.originalPlainText(), value.originalContentSchemaVersion(),
                value.originalContentJson(), value.originalSortOrder(),
                value.proposedDocumentTitle(), value.proposedDocumentType(),
                value.proposedParentDocumentId(), value.proposedBlockType(),
                value.proposedPlainText(), value.proposedContentSchemaVersion(),
                value.proposedContentJson());
        return value;
    }

    @Override
    public BindingProposal saveBindingProposal(BindingProposal value) {
        jdbcTemplate.update("""
                INSERT INTO document_change_binding_proposals (
                    id, change_request_id, client_binding_proposal_id, sequence_number,
                    action, repository_id, file_path, document_id,
                    created_document_operation_id, binding_id, reason, created_at,
                    revision, anchor_kind, symbol_key, start_line, end_line,
                    block_id, created_block_operation_id, candidate_id,
                    document_anchor_candidate_id, confidence, binding_role,
                    binding_ordinal, bound_signature
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                value.id(), value.changeRequestId(), value.clientBindingProposalId(),
                value.sequenceNumber(), value.action().name(), value.repositoryId(),
                value.filePath(), value.documentId(), value.createdDocumentOperationId(),
                value.bindingId(), value.reason(), Timestamp.from(value.createdAt()),
                value.revision(), value.anchorKind().name(), value.symbolKey(),
                value.startLine(), value.endLine(), value.blockId(),
                value.createdBlockOperationId(), value.candidateId(),
                value.documentAnchorCandidateId(), value.confidence(),
                value.bindingRole().name(), value.bindingOrdinal(),
                value.boundSignature());
        return value;
    }

    @Override
    public Evidence saveEvidence(Evidence value) {
        jdbcTemplate.update("""
                INSERT INTO document_change_evidence (
                    id, change_request_id, operation_id, repository_id,
                    commit_hash, file_path, start_line, end_line, description,
                    blob_sha, excerpt_text, excerpt_hash
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                value.id(), value.changeRequestId(), value.operationId(),
                value.repositoryId(), value.commitHash(), value.filePath(),
                value.startLine(), value.endLine(), value.description(),
                value.blobSha(), value.excerptText(), value.excerptHash());
        return value;
    }

    @Override
    public Optional<ChangeRequest> findRequest(UUID workspaceId, UUID requestId) {
        return jdbcTemplate.query("""
                SELECT * FROM document_change_requests
                 WHERE workspace_id = ? AND id = ?
                """, REQUEST_MAPPER, workspaceId, requestId).stream().findFirst();
    }

    @Override
    public Optional<ChangeRequest> findRequestForUpdate(
            UUID workspaceId,
            UUID requestId
    ) {
        return jdbcTemplate.query("""
                SELECT * FROM document_change_requests
                 WHERE workspace_id = ? AND id = ?
                 FOR UPDATE
                """, REQUEST_MAPPER, workspaceId, requestId).stream().findFirst();
    }

    @Override
    public Optional<ChangeRequest> findByClientRequestId(
            UUID workspaceId,
            UUID submittedBy,
            String clientRequestId
    ) {
        return jdbcTemplate.query("""
                SELECT * FROM document_change_requests
                 WHERE workspace_id = ? AND submitted_by = ?
                   AND client_request_id = ?
                """, REQUEST_MAPPER, workspaceId, submittedBy, clientRequestId)
                .stream().findFirst();
    }

    @Override
    public List<Operation> findOperations(UUID requestId) {
        return jdbcTemplate.query("""
                SELECT * FROM document_change_operations
                 WHERE change_request_id = ?
                 ORDER BY sequence_number, id
                """, OPERATION_MAPPER, requestId);
    }

    @Override
    public List<BindingProposal> findBindingProposals(UUID requestId) {
        return jdbcTemplate.query("""
                SELECT * FROM document_change_binding_proposals
                 WHERE change_request_id = ?
                 ORDER BY sequence_number, id
                """, BINDING_PROPOSAL_MAPPER, requestId);
    }

    @Override
    public List<Evidence> findEvidence(UUID requestId) {
        return jdbcTemplate.query("""
                SELECT * FROM document_change_evidence
                 WHERE change_request_id = ?
                 ORDER BY operation_id NULLS FIRST, id
                """, EVIDENCE_MAPPER, requestId);
    }

    @Override
    public long count(UUID workspaceId, Status status) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM document_change_requests
                 WHERE workspace_id = ? AND status = ?
                """, Long.class, workspaceId, status.name());
        return count == null ? 0 : count;
    }

    @Override
    public long countAll(UUID workspaceId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM document_change_requests
                 WHERE workspace_id = ?
                """, Long.class, workspaceId);
        return count == null ? 0 : count;
    }

    @Override
    public List<ListItem> findPage(
            UUID workspaceId,
            Status status,
            int offset,
            int size,
            boolean ascending
    ) {
        String direction = ascending ? "ASC" : "DESC";
        return jdbcTemplate.query("""
                SELECT r.id, r.summary, r.status, r.source_type,
                       r.submitted_by, u.display_name, r.created_at,
                       r.reviewed_at,
                       (SELECT COUNT(*) FROM document_change_operations o
                         WHERE o.change_request_id = r.id) operation_count,
                       (SELECT COUNT(*) FROM document_change_binding_proposals bp
                         WHERE bp.change_request_id = r.id) binding_proposal_count,
                       (SELECT COUNT(*) FROM document_change_evidence e
                         WHERE e.change_request_id = r.id) evidence_count
                  FROM document_change_requests r
                  JOIN user_accounts u ON u.id = r.submitted_by
                 WHERE r.workspace_id = ? AND r.status = ?
                 ORDER BY r.created_at %s, r.id %s
                 LIMIT ? OFFSET ?
                """.formatted(direction, direction),
                (rs, rowNum) -> new ListItem(
                        rs.getObject("id", UUID.class),
                        rs.getString("summary"),
                        Status.valueOf(rs.getString("status")),
                        SourceType.valueOf(rs.getString("source_type")),
                        rs.getObject("submitted_by", UUID.class),
                        rs.getString("display_name"),
                        rs.getTimestamp("created_at").toInstant(),
                        instant(rs.getTimestamp("reviewed_at")),
                        rs.getLong("operation_count"),
                        rs.getLong("binding_proposal_count"),
                        rs.getLong("evidence_count")
                ), workspaceId, status.name(), size, offset);
    }

    @Override
    public ChangeRequest decide(
            ChangeRequest request,
            Status status,
            UUID reviewedBy,
            Instant reviewedAt,
            String rejectionReason
    ) {
        jdbcTemplate.update("""
                UPDATE document_change_requests
                   SET status = ?, reviewed_by = ?, reviewed_at = ?,
                       rejection_reason = ?
                 WHERE id = ?
                """, status.name(), reviewedBy, Timestamp.from(reviewedAt),
                rejectionReason, request.id());
        return new ChangeRequest(
                request.id(), request.workspaceId(), request.clientRequestId(),
                request.requestFingerprint(), status, request.summary(),
                request.rationale(), request.sourceType(), request.submittedBy(),
                request.createdAt(), reviewedBy, reviewedAt, rejectionReason
        );
    }

    @Override
    public List<ChangeRequest> findRequestsAffectingDocument(UUID documentId, int limit) {
        return jdbcTemplate.query("""
                SELECT DISTINCT r.*
                  FROM document_change_requests r
                  JOIN document_change_operations o
                    ON o.change_request_id = r.id
                 WHERE o.document_id = ?
                 ORDER BY r.created_at DESC
                 LIMIT ?
                """, REQUEST_MAPPER, documentId, limit);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
