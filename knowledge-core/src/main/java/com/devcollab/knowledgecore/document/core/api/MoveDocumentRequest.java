package com.devcollab.knowledgecore.document.core.api;

import java.util.UUID;

public record MoveDocumentRequest(
        UUID parentDocumentId
) {
}
