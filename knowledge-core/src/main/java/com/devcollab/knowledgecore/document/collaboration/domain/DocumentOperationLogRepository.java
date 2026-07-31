package com.devcollab.knowledgecore.document.collaboration.domain;

import java.util.List;
import java.util.UUID;

public interface DocumentOperationLogRepository {

    DocumentOperationLog save(DocumentOperationLog operationLog);

    List<DocumentOperationLog> findAllByDocumentId(UUID documentId);
}
