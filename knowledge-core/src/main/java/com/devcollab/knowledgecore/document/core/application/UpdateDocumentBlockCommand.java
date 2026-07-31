package com.devcollab.knowledgecore.document.core.application;

import com.fasterxml.jackson.databind.JsonNode;

public record UpdateDocumentBlockCommand(
        String text,
        Integer contentSchemaVersion,
        JsonNode contentDocument,
        long expectedVersion
) {
}
