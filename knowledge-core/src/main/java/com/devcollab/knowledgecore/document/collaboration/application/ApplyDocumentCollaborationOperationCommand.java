package com.devcollab.knowledgecore.document.collaboration.application;

import com.devcollab.knowledgecore.document.core.domain.DocumentBlockType;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

public record ApplyDocumentCollaborationOperationCommand(
        UUID clientOperationId,
        UUID blockId,
        String operationType,
        Long expectedVersion,
        DocumentBlockType blockType,
        Integer targetIndex,
        String text,
        Integer contentSchemaVersion,
        JsonNode contentDocument
) {
}
