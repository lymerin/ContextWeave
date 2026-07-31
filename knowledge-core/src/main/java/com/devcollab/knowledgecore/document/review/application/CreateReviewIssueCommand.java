package com.devcollab.knowledgecore.document.review.application;

import com.devcollab.knowledgecore.document.review.domain.ReviewIssueSeverity;
import com.devcollab.knowledgecore.document.review.domain.ReviewIssueType;

import java.util.UUID;

public record CreateReviewIssueCommand(
        ReviewIssueType type,
        ReviewIssueSeverity severity,
        UUID assigneeId,
        String title,
        String description
) {
}
