package com.devcollab.knowledgecore.document.review.application;

import com.devcollab.knowledgecore.document.review.domain.ReviewIssueStatus;

public record UpdateReviewIssueStatusCommand(ReviewIssueStatus status) {
}
