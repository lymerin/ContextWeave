package com.devcollab.knowledgecore.document.collaboration.domain;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface DocumentCollaborationOperationRepository {

    Optional<DocumentCollaborationOperation> findByClientOperationId(
            UUID documentId,
            UUID clientOperationId
    );

    void lockDocument(UUID documentId);

    long nextDocumentSequence(UUID documentId);

    long currentDocumentSequence(UUID documentId);

    List<DocumentCollaborationOperation> findAfterSequence(
            UUID documentId,
            long afterSequence,
            long throughSequence,
            int limit
    );

    DocumentCollaborationOperation save(
            DocumentCollaborationOperation operation
    );
}
