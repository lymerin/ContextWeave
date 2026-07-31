package com.devcollab.knowledgecore.document.core.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import com.devcollab.knowledgecore.document.block.api.DocumentBlockContentRequest;

public record UpdateDocumentBlockRequest(
        @Valid
        @NotNull(message = "Block content must not be null")
        DocumentBlockContentRequest content,

        @NotNull(message = "expectedVersion must not be null")
        @PositiveOrZero(message = "expectedVersion must be zero or greater")
        Long expectedVersion
) {
}
