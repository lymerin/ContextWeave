package com.devcollab.knowledgecore.document.collaboration.application;

import java.util.List;

public record DocumentCollaborationOperationPage(
        long requestedAfterSequence,
        long latestDocumentSequence,
        boolean hasMore,
        List<DocumentCollaborationOperationResult> operations
) {
}
