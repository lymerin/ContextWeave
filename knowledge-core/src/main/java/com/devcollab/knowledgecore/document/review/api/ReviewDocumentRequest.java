package com.devcollab.knowledgecore.document.review.api;

import jakarta.validation.constraints.Size;

public record ReviewDocumentRequest(
        @Size(max = 2000, message = "评审意见不能超过 2000 个字符")
        String comment
) {
}
