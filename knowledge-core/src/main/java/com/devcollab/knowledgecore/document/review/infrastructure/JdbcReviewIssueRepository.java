package com.devcollab.knowledgecore.document.review.infrastructure;

import com.devcollab.knowledgecore.document.review.domain.ReviewIssue;
import com.devcollab.knowledgecore.document.review.domain.ReviewIssueRepository;
import com.devcollab.knowledgecore.document.review.domain.ReviewIssueSeverity;
import com.devcollab.knowledgecore.document.review.domain.ReviewIssueStatus;
import com.devcollab.knowledgecore.document.review.domain.ReviewIssueType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcReviewIssueRepository implements ReviewIssueRepository {

    private static final RowMapper<ReviewIssue> ROW_MAPPER =
            (rs, rowNum) -> new ReviewIssue(
                    rs.getObject("id", UUID.class),
                    rs.getObject("document_version_id", UUID.class),
                    ReviewIssueType.valueOf(rs.getString("type")),
                    ReviewIssueSeverity.valueOf(rs.getString("severity")),
                    ReviewIssueStatus.valueOf(rs.getString("status")),
                    rs.getObject("assignee_id", UUID.class),
                    rs.getString("title"),
                    rs.getString("description"),
                    rs.getObject("created_by", UUID.class),
                    rs.getTimestamp("created_at").toInstant()
            );

    private final JdbcTemplate jdbcTemplate;

    public JdbcReviewIssueRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public ReviewIssue save(ReviewIssue issue) {
        int updated = jdbcTemplate.update("""
                        UPDATE review_issues
                           SET type = ?,
                               severity = ?,
                               status = ?,
                               assignee_id = ?,
                               title = ?,
                               description = ?
                         WHERE id = ?
                        """,
                issue.type().name(),
                issue.severity().name(),
                issue.status().name(),
                issue.assigneeId(),
                issue.title(),
                issue.description(),
                issue.id());

        if (updated == 0) {
            jdbcTemplate.update("""
                            INSERT INTO review_issues
                                (id, document_version_id, type, severity,
                                 status, assignee_id, title, description,
                                 created_by, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    issue.id(),
                    issue.documentVersionId(),
                    issue.type().name(),
                    issue.severity().name(),
                    issue.status().name(),
                    issue.assigneeId(),
                    issue.title(),
                    issue.description(),
                    issue.createdBy(),
                    Timestamp.from(issue.createdAt()));
        }
        return issue;
    }

    @Override
    public Optional<ReviewIssue> findById(UUID issueId) {
        return jdbcTemplate.query(
                "SELECT * FROM review_issues WHERE id = ?",
                ROW_MAPPER,
                issueId
        ).stream().findFirst();
    }

    @Override
    public List<ReviewIssue> findAllByDocumentVersionId(
            UUID documentVersionId
    ) {
        return jdbcTemplate.query("""
                        SELECT * FROM review_issues
                         WHERE document_version_id = ?
                         ORDER BY created_at DESC
                        """,
                ROW_MAPPER,
                documentVersionId
        );
    }
}
