package com.devcollab.knowledgecore.document.core.api;

import com.devcollab.knowledgecore.document.core.domain.DocumentBlockType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import com.devcollab.knowledgecore.document.block.api.DocumentBlockContentRequest;

public record CreateDocumentBlockRequest(
        @NotNull(message = "Block 类型不能为空")
        DocumentBlockType type,

        @Valid
        @NotNull(message = "Block 内容不能为空")
        DocumentBlockContentRequest content
) {
}
