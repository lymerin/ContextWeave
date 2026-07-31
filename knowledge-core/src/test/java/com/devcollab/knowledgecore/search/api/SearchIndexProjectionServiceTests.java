package com.devcollab.knowledgecore.search.projection;

import com.devcollab.knowledgecore.common.outbox.domain.OutboxEvent;
import com.devcollab.knowledgecore.document.core.domain.Document;
import com.devcollab.knowledgecore.document.core.domain.DocumentBlock;
import com.devcollab.knowledgecore.document.core.domain.DocumentBlockRepository;
import com.devcollab.knowledgecore.document.core.domain.DocumentBlockType;
import com.devcollab.knowledgecore.document.core.domain.DocumentRepository;
import com.devcollab.knowledgecore.document.core.domain.DocumentReviewStatus;
import com.devcollab.knowledgecore.document.core.domain.DocumentType;
import com.devcollab.knowledgecore.search.domain.SearchHit;
import com.devcollab.knowledgecore.search.domain.SearchScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SearchIndexProjectionServiceTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FakeSearchIndexGateway gateway = new FakeSearchIndexGateway();
    private final FakeDocumentRepository documentRepository =
            new FakeDocumentRepository();
    private final FakeDocumentBlockRepository blockRepository =
            new FakeDocumentBlockRepository();
    private final SearchIndexProjectionService projectionService =
            new SearchIndexProjectionService(
                    gateway,
                    documentRepository,
                    blockRepository,
                    objectMapper
            );

    @Test
    void shouldProjectDocumentTitleAndUpdateRelatedBlockTitles()
            throws Exception {
        UUID workspaceId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        Instant updatedAt = Instant.parse("2026-07-14T00:00:00Z");

        projectionService.project(event(
                "DOCUMENT_UPDATED",
                documentId,
                """
                        {
                          "workspaceId": "%s",
                          "documentId": "%s",
                          "title": "Order API Contract",
                          "updatedAt": "%s"
                        }
                        """.formatted(workspaceId, documentId, updatedAt)
        ));

        assertThat(gateway.upserted).containsExactly(
                SearchIndexEntry.documentTitle(
                        workspaceId,
                        documentId,
                        "Order API Contract",
                        updatedAt
                )
        );
        assertThat(gateway.updatedDocumentTitles).containsExactly(
                "Order API Contract"
        );
    }

    @Test
    void shouldProjectBlockContentByReadingAuthoritativeDatabase()
            throws Exception {
        UUID workspaceId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID blockId = UUID.randomUUID();
        Instant now = Instant.parse("2026-07-14T00:00:00Z");
        documentRepository.document = new Document(
                documentId,
                workspaceId,
                null,
                "Order API Contract",
                DocumentType.API,
                DocumentReviewStatus.DRAFT,
                UUID.randomUUID(),
                now,
                now
        );
        blockRepository.block = new DocumentBlock(
                blockId,
                documentId,
                DocumentBlockType.PARAGRAPH,
                "POST /api/orders requires idempotency key",
                1,
                null,
                0,
                1,
                UUID.randomUUID(),
                now,
                now
        );

        projectionService.project(event(
                "DOCUMENT_BLOCK_UPDATED",
                blockId,
                """
                        {
                          "workspaceId": "%s",
                          "documentId": "%s",
                          "blockId": "%s"
                        }
                        """.formatted(workspaceId, documentId, blockId)
        ));

        assertThat(gateway.upserted).containsExactly(
                SearchIndexEntry.blockContent(
                        workspaceId,
                        documentId,
                        "Order API Contract",
                        blockId,
                        "POST /api/orders requires idempotency key",
                        0,
                        now
                )
        );
    }

    @Test
    void shouldDeleteDocumentAndBlockProjections() throws Exception {
        UUID documentId = UUID.randomUUID();
        UUID blockId = UUID.randomUUID();

        projectionService.project(event(
                "DOCUMENT_DELETED",
                documentId,
                """
                        {"documentId": "%s"}
                        """.formatted(documentId)
        ));
        projectionService.project(event(
                "DOCUMENT_BLOCK_DELETED",
                blockId,
                """
                        {"blockId": "%s"}
                        """.formatted(blockId)
        ));

        assertThat(gateway.deletedDocumentIds).containsExactly(documentId);
        assertThat(gateway.deletedIndexIds).containsExactly(
                SearchIndexEntry.blockIndexId(blockId)
        );
    }

    private static OutboxEvent event(
            String eventType,
            UUID aggregateId,
            String payload
    ) {
        return OutboxEvent.pending(
                "DOCUMENT",
                aggregateId,
                eventType,
                payload,
                Instant.parse("2026-07-14T00:00:00Z")
        );
    }

    private static class FakeSearchIndexGateway implements SearchIndexGateway {

        private final List<SearchIndexEntry> upserted = new ArrayList<>();
        private final List<String> deletedIndexIds = new ArrayList<>();
        private final List<UUID> deletedDocumentIds = new ArrayList<>();
        private final List<String> updatedDocumentTitles = new ArrayList<>();

        @Override
        public void ensureIndex() {
        }

        @Override
        public void upsert(SearchIndexEntry entry) {
            upserted.add(entry);
        }

        @Override
        public void deleteByIndexId(String indexId) {
            deletedIndexIds.add(indexId);
        }

        @Override
        public void deleteByDocumentId(UUID documentId) {
            deletedDocumentIds.add(documentId);
        }

        @Override
        public void updateDocumentTitle(
                UUID documentId,
                String documentTitle,
                Instant updatedAt
        ) {
            updatedDocumentTitles.add(documentTitle);
        }

        @Override
        public List<SearchHit> searchWorkspace(
                UUID workspaceId,
                String keyword,
                SearchScope scope,
                int limit
        ) {
            return List.of();
        }
    }

    private static class FakeDocumentRepository implements DocumentRepository {

        private Document document;

        @Override
        public Document save(Document document) {
            this.document = document;
            return document;
        }

        @Override
        public Optional<Document> findById(UUID documentId) {
            return Optional.ofNullable(document)
                    .filter(found -> found.id().equals(documentId));
        }

        @Override
        public List<Document> findAllByWorkspaceId(UUID workspaceId) {
            return List.of();
        }

        @Override
        public void deleteById(UUID documentId) {
        }
    }

    private static class FakeDocumentBlockRepository
            implements DocumentBlockRepository {

        private DocumentBlock block;

        @Override
        public DocumentBlock save(DocumentBlock block) {
            this.block = block;
            return block;
        }

        @Override
        public List<DocumentBlock> saveAll(List<DocumentBlock> blocks) {
            return blocks;
        }

        @Override
        public Optional<DocumentBlock> findById(UUID blockId) {
            return Optional.ofNullable(block)
                    .filter(found -> found.id().equals(blockId));
        }

        @Override
        public List<DocumentBlock> findAllByDocumentId(UUID documentId) {
            return List.of();
        }

        @Override
        public Optional<DocumentBlock> updateContentIfVersionMatches(
                UUID blockId,
                String text,
                int contentSchemaVersion,
                String contentJson,
                Instant updatedAt,
                long expectedVersion
        ) {
            return Optional.empty();
        }

        @Override
        public boolean deleteIfVersionMatches(
                UUID blockId,
                long expectedVersion
        ) {
            if (block == null
                    || !block.id().equals(blockId)
                    || block.version() != expectedVersion) {
                return false;
            }
            block = null;
            return true;
        }

        @Override
        public void deleteById(UUID blockId) {
        }
    }
}
