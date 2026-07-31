package com.devcollab.knowledgecore.document.collaboration.application;

import com.devcollab.knowledgecore.document.core.domain.DocumentBlock;

import java.util.UUID;
import java.util.List;

public record DocumentCollaborationOperationResult(
        UUID clientOperationId,
        UUID blockId,
        String operationType,
        String status,
        long documentSequence,
        UUID operatorUserId,
        DocumentBlock block,
        List<DocumentBlock> blocks
) {
}
