package com.devcollab.knowledgecore.document.block.api;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record DocumentBlockContentRequest(
        @Size(max = 20000, message = "Block content must not exceed 20000 characters")
        String text,
        @Positive(message = "content schemaVersion must be positive")
        Integer schemaVersion,
        JsonNode document
) {
}
