package com.devcollab.knowledgecore.document.core.application;

import com.devcollab.knowledgecore.document.core.domain.DocumentBlockType;
import com.fasterxml.jackson.databind.JsonNode;

public record CreateDocumentBlockCommand(
        DocumentBlockType type,
        String text,
        Integer contentSchemaVersion,
        JsonNode contentDocument
) {
}
