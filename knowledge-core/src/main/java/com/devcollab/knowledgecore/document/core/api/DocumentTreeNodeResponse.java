package com.devcollab.knowledgecore.document.core.api;

import com.devcollab.knowledgecore.document.core.domain.Document;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record DocumentTreeNodeResponse(
        UUID id,
        String title,
        List<DocumentTreeNodeResponse> children
) {
    public static List<DocumentTreeNodeResponse> from(
            List<Document> documents
    ) {
        return documents.stream()
                .filter(document -> document.parentDocumentId() == null)
                .map(document -> buildNode(document, documents))
                .toList();
    }

    private static DocumentTreeNodeResponse buildNode(
            Document document,
            List<Document> documents
    ) {
        List<DocumentTreeNodeResponse> children = documents.stream()
                .filter(candidate -> Objects.equals(
                        candidate.parentDocumentId(),
                        document.id()
                ))
                .map(child -> buildNode(child, documents))
                .toList();

        return new DocumentTreeNodeResponse(
                document.id(),
                document.title(),
                children
        );
    }
}
