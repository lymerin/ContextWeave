package com.devcollab.knowledgecore.document.review.api;

import com.devcollab.knowledgecore.document.review.domain.ReviewIssueStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateReviewIssueStatusRequest(
        @NotNull(message = "问题状态不能为空")
        ReviewIssueStatus status
) {
}
