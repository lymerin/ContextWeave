package com.devcollab.knowledgecore.document.collaboration.api;

import com.devcollab.knowledgecore.document.collaboration.domain.DocumentOperationLog;

import java.time.Instant;
import java.util.UUID;

public record DocumentOperationLogResponse(
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
    public static DocumentOperationLogResponse from(
            DocumentOperationLog operationLog
    ) {
        return new DocumentOperationLogResponse(
                operationLog.id(),
                operationLog.workspaceId(),
                operationLog.documentId(),
                operationLog.action(),
                operationLog.message(),
                operationLog.operatorUserId(),
                operationLog.targetType(),
                operationLog.targetId(),
                operationLog.createdAt()
        );
    }
}
