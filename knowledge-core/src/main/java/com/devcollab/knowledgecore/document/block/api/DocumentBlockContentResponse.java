package com.devcollab.knowledgecore.document.block.api;

import com.fasterxml.jackson.databind.JsonNode;

public record DocumentBlockContentResponse(
        String text,
        int schemaVersion,
        JsonNode document
) {
}
