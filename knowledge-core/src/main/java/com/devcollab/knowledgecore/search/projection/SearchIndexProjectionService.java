package com.devcollab.knowledgecore.search.projection;

import com.devcollab.knowledgecore.common.outbox.domain.OutboxEvent;
import com.devcollab.knowledgecore.document.core.domain.Document;
import com.devcollab.knowledgecore.document.core.domain.DocumentBlock;
import com.devcollab.knowledgecore.document.core.domain.DocumentBlockRepository;
import com.devcollab.knowledgecore.document.core.domain.DocumentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
@ConditionalOnProperty(
        name = "devcollab.search.elasticsearch.enabled",
        havingValue = "true"
)
public class SearchIndexProjectionService {

    private final SearchIndexGateway searchIndexGateway;
    private final DocumentRepository documentRepository;
    private final DocumentBlockRepository blockRepository;
    private final ObjectMapper objectMapper;

    public SearchIndexProjectionService(
            SearchIndexGateway searchIndexGateway,
            DocumentRepository documentRepository,
            DocumentBlockRepository blockRepository,
            ObjectMapper objectMapper
    ) {
        this.searchIndexGateway = searchIndexGateway;
        this.documentRepository = documentRepository;
        this.blockRepository = blockRepository;
        this.objectMapper = objectMapper;
    }

    public void project(OutboxEvent event) {
        searchIndexGateway.ensureIndex();
        JsonNode payload = readPayload(event);

        switch (event.eventType()) {
            case "DOCUMENT_CREATED",
                 "DOCUMENT_UPDATED",
                 "DOCUMENT_MOVED" -> projectDocument(payload);
            case "DOCUMENT_DELETED" -> deleteDocument(payload);
            case "DOCUMENT_BLOCK_CREATED",
                 "DOCUMENT_BLOCK_UPDATED",
                 "DOCUMENT_BLOCK_MOVED" -> projectBlock(payload);
            case "DOCUMENT_BLOCK_DELETED" -> deleteBlock(payload);
            default -> {
                // Other event families are intentionally ignored by search projection.
            }
        }
    }

    private void projectDocument(JsonNode payload) {
        UUID workspaceId = uuid(payload, "workspaceId");
        UUID documentId = uuid(payload, "documentId");
        String title = text(payload, "title");
        Instant updatedAt = instant(payload, "updatedAt");

        searchIndexGateway.upsert(SearchIndexEntry.documentTitle(
                workspaceId,
                documentId,
                title,
                updatedAt
        ));
        searchIndexGateway.updateDocumentTitle(documentId, title, updatedAt);
    }

    private void deleteDocument(JsonNode payload) {
        searchIndexGateway.deleteByDocumentId(uuid(payload, "documentId"));
    }

    private void projectBlock(JsonNode payload) {
        UUID documentId = uuid(payload, "documentId");
        UUID blockId = uuid(payload, "blockId");

        Document document = documentRepository.findById(documentId)
                .orElse(null);
        DocumentBlock block = blockRepository.findById(blockId)
                .orElse(null);
        if (document == null || block == null) {
            return;
        }

        searchIndexGateway.upsert(SearchIndexEntry.blockContent(
                document.workspaceId(),
                document.id(),
                document.title(),
                block.id(),
                block.text(),
                block.sortOrder(),
                block.updatedAt()
        ));
    }

    private void deleteBlock(JsonNode payload) {
        searchIndexGateway.deleteByIndexId(
                SearchIndexEntry.blockIndexId(uuid(payload, "blockId"))
        );
    }

    private JsonNode readPayload(OutboxEvent event) {
        try {
            return objectMapper.readTree(event.payload());
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                    "Outbox event payload cannot be parsed",
                    exception
            );
        }
    }

    private static UUID uuid(JsonNode payload, String field) {
        return UUID.fromString(payload.get(field).asText());
    }

    private static String text(JsonNode payload, String field) {
        return payload.get(field).asText();
    }

    private static Instant instant(JsonNode payload, String field) {
        return Instant.parse(payload.get(field).asText());
    }
}
