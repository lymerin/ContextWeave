package com.devcollab.knowledgecore.document.review.api;

import com.devcollab.knowledgecore.document.review.domain.ReviewIssue;
import com.devcollab.knowledgecore.document.review.domain.ReviewIssueSeverity;
import com.devcollab.knowledgecore.document.review.domain.ReviewIssueStatus;
import com.devcollab.knowledgecore.document.review.domain.ReviewIssueType;

import java.time.Instant;
import java.util.UUID;

public record ReviewIssueResponse(
        UUID id,
        UUID documentVersionId,
        ReviewIssueType type,
        ReviewIssueSeverity severity,
        ReviewIssueStatus status,
        UUID assigneeId,
        String title,
        String description,
        UUID createdBy,
        Instant createdAt
) {
    public static ReviewIssueResponse from(ReviewIssue issue) {
        return new ReviewIssueResponse(
                issue.id(),
                issue.documentVersionId(),
                issue.type(),
                issue.severity(),
                issue.status(),
                issue.assigneeId(),
                issue.title(),
                issue.description(),
                issue.createdBy(),
                issue.createdAt()
        );
    }
}
