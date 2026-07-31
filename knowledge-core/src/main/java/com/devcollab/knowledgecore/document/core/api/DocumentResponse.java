package com.devcollab.knowledgecore.document.core.api;

import com.devcollab.knowledgecore.document.core.domain.Document;
import com.devcollab.knowledgecore.document.core.domain.DocumentReviewStatus;
import com.devcollab.knowledgecore.document.core.domain.DocumentType;

import java.time.Instant;
import java.util.UUID;

public record DocumentResponse(
        UUID id,
        UUID workspaceId,
        UUID parentDocumentId,
        String title,
        DocumentType documentType,
        DocumentReviewStatus reviewStatus,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt
) {
    public static DocumentResponse from(Document document) {
        return new DocumentResponse(
                document.id(),
                document.workspaceId(),
                document.parentDocumentId(),
                document.title(),
                document.documentType(),
                document.reviewStatus(),
                document.createdBy(),
                document.createdAt(),
                document.updatedAt()
        );
    }
}
