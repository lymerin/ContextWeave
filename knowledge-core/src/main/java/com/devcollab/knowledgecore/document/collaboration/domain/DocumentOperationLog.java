package com.devcollab.knowledgecore.document.collaboration.domain;

import java.time.Instant;
import java.util.UUID;

public record DocumentOperationLog(
        UUID id,
        UUID workspaceId,
        UUID documentId,
        String action,
        String message,
        UUID operatorUserId,
        String targetType,
        UUID targetId,
        Instant createdAt
) {
}
