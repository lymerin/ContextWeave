package com.devcollab.knowledgecore.common.cache;

import com.devcollab.knowledgecore.common.outbox.application.OutboxKafkaMessage;
import com.devcollab.knowledgecore.document.tree.application.DocumentTreeCacheService.DocumentList;
import com.devcollab.knowledgecore.workspace.application.WorkspaceMemberCacheService.CachedMembership;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class CacheInvalidationKafkaConsumer {

    private static final Logger log =
            LoggerFactory.getLogger(CacheInvalidationKafkaConsumer.class);

    private final ObjectMapper objectMapper;
    private final Cache<String, CachedMembership> workspaceMemberCache;
    private final Cache<String, DocumentList> documentTreeCache;
    private final Cache<String, DocumentSchemaDescriptor> documentSchemaCache;
    private final PublishedDocumentCacheService publishedDocumentCache;
    private final ApprovedAdrCacheService approvedAdrCache;

    public CacheInvalidationKafkaConsumer(
            ObjectMapper objectMapper,
            @Qualifier("workspaceMemberLocalCache")
            Cache<String, CachedMembership> workspaceMemberCache,
            @Qualifier("documentTreeLocalCache")
            Cache<String, DocumentList> documentTreeCache,
            @Qualifier("documentSchemaLocalCache")
            Cache<String, DocumentSchemaDescriptor> documentSchemaCache,
            PublishedDocumentCacheService publishedDocumentCache,
            ApprovedAdrCacheService approvedAdrCache
    ) {
        this.objectMapper = objectMapper;
        this.workspaceMemberCache = workspaceMemberCache;
        this.documentTreeCache = documentTreeCache;
        this.documentSchemaCache = documentSchemaCache;
        this.publishedDocumentCache = publishedDocumentCache;
        this.approvedAdrCache = approvedAdrCache;
    }

    @KafkaListener(
            topics = "${devcollab.outbox.kafka.cache-topic:devcollab.cache.events}",
            groupId = "${devcollab.cache.invalidation.group-id}",
            autoStartup = "${devcollab.cache.invalidation.enabled:true}"
    )
    public void consume(String rawMessage) {
        try {
            OutboxKafkaMessage message = objectMapper.readValue(
                    rawMessage, OutboxKafkaMessage.class
            );
            JsonNode payload = objectMapper.readTree(message.payload());
            invalidate(
                    payload.path("cacheName").asText(),
                    payload.path("cacheKey").asText()
            );
        } catch (Exception exception) {
            log.warn(
                    "Ignoring invalid cache invalidation event; TTL remains fallback: {}",
                    exception.getMessage()
            );
            log.debug("Invalid cache invalidation event detail", exception);
        }
    }

    void invalidate(String cacheName, String key) {
        if (cacheName == null || cacheName.isBlank()
                || key == null || key.isBlank()) {
            throw new IllegalArgumentException(
                    "cacheName and cacheKey are required"
            );
        }
        switch (cacheName) {
            case "workspace-member" -> workspaceMemberCache.invalidate(key);
            case "workspace-documents-tree" -> documentTreeCache.invalidate(key);
            case "document-schema" -> documentSchemaCache.invalidate(key);
            case "published-document" -> publishedDocumentCache.invalidate(key);
            case "approved-adr" -> approvedAdrCache.invalidate(key);
            default -> log.warn("Ignoring unknown cache invalidation name={}", cacheName);
        }
    }
}
