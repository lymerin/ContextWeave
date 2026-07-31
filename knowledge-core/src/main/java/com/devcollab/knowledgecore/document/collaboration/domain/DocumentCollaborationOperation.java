package com.devcollab.knowledgecore.document.collaboration.domain;

import java.time.Instant;
import java.util.UUID;

public record DocumentCollaborationOperation(
        UUID id,
        UUID documentId,
        long documentSequence,
        UUID clientOperationId,
        String operationType,
        UUID operatorUserId,
        String requestFingerprint,
        DocumentCollaborationOperationPayload result,
        Instant createdAt
) {
}
