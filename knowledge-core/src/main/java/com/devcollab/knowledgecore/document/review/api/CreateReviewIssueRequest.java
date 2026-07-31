package com.devcollab.knowledgecore.document.review.api;

import com.devcollab.knowledgecore.document.review.domain.ReviewIssueSeverity;
import com.devcollab.knowledgecore.document.review.domain.ReviewIssueType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateReviewIssueRequest(
        @NotNull(message = "问题类型不能为空")
        ReviewIssueType type,

        @NotNull(message = "严重级别不能为空")
        ReviewIssueSeverity severity,

        UUID assigneeId,

        @NotBlank(message = "问题标题不能为空")
        @Size(max = 200, message = "问题标题不能超过 200 个字符")
        String title,

        @Size(max = 5000, message = "问题描述不能超过 5000 个字符")
        String description
) {
}
