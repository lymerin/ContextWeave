package com.devcollab.knowledgecore.document.core.application;

import com.devcollab.knowledgecore.document.core.domain.DocumentType;

import java.util.UUID;

public record CreateDocumentCommand(
        UUID parentDocumentId,
        String title,
        DocumentType documentType
) {
}
