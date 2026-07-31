package com.devcollab.knowledgecore.document.collaboration.api;

import com.devcollab.knowledgecore.document.collaboration.application.DocumentCollaborationOperationPage;
import com.devcollab.knowledgecore.document.block.application.DocumentBlockContentCodec;

import java.util.List;

public record DocumentCollaborationOperationPageResponse(
        long requestedAfterSequence,
        long latestDocumentSequence,
        boolean hasMore,
        List<DocumentCollaborationOperationResponse> operations
) {
    public static DocumentCollaborationOperationPageResponse from(
            DocumentCollaborationOperationPage page,
            DocumentBlockContentCodec contentCodec
    ) {
        return new DocumentCollaborationOperationPageResponse(
                page.requestedAfterSequence(),
                page.latestDocumentSequence(),
                page.hasMore(),
                page.operations().stream()
                        .map(operation -> DocumentCollaborationOperationResponse.from(
                                operation,
                                contentCodec
                        ))
                        .toList()
        );
    }
}
