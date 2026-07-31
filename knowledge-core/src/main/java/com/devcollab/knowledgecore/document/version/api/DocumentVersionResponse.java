package com.devcollab.knowledgecore.document.version.api;

import com.devcollab.knowledgecore.document.version.domain.DocumentVersion;
import com.devcollab.knowledgecore.document.version.domain.DocumentVersionStatus;

import java.time.Instant;
import java.util.UUID;

public record DocumentVersionResponse(
        UUID id,
        UUID documentId,
        int versionNo,
        String title,
        DocumentVersionStatus status,
        String snapshotPayload,
        UUID publishedBy,
        Instant publishedAt
) {
    public static DocumentVersionResponse from(DocumentVersion version) {
        return new DocumentVersionResponse(
                version.id(),
                version.documentId(),
                version.versionNo(),
                version.title(),
                version.status(),
                version.snapshotPayload(),
                version.publishedBy(),
                version.publishedAt()
        );
    }
}
