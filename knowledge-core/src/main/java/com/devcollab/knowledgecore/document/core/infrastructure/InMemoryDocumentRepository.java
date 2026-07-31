package com.devcollab.knowledgecore.document.core.infrastructure;

import com.devcollab.knowledgecore.document.core.domain.Document;
import com.devcollab.knowledgecore.document.core.domain.DocumentRepository;
import org.springframework.stereotype.Repository;
import org.springframework.context.annotation.Profile;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@Profile("in-memory")
public class InMemoryDocumentRepository implements DocumentRepository {

    private final Map<UUID, Document> documents = new ConcurrentHashMap<>();

    @Override
    public Document save(Document document) {
        documents.put(document.id(), document);
        return document;
    }

    @Override
    public Optional<Document> findById(UUID documentId) {
        return Optional.ofNullable(documents.get(documentId));
    }

    @Override
    public List<Document> findAllByWorkspaceId(UUID workspaceId) {
        return documents.values().stream()
                .filter(document -> document.workspaceId().equals(workspaceId))
                .sorted(Comparator.comparing(Document::createdAt))
                .toList();
    }

    @Override
    public void deleteById(UUID documentId) {
        List<UUID> children = documents.values().stream()
                .filter(document -> documentId.equals(document.parentDocumentId()))
                .map(Document::id)
                .toList();
        children.forEach(this::deleteById);
        documents.remove(documentId);
    }
}
