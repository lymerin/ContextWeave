package com.devcollab.knowledgecore.common.cache;

import com.devcollab.knowledgecore.common.outbox.application.OutboxKafkaMessage;
import com.devcollab.knowledgecore.document.tree.application.DocumentTreeCacheService.DocumentList;
import com.devcollab.knowledgecore.document.core.domain.DocumentBlockType;
import com.devcollab.knowledgecore.document.core.domain.DocumentType;
import com.devcollab.knowledgecore.document.version.domain.DocumentVersion;
import com.devcollab.knowledgecore.document.version.domain.DocumentVersionStatus;
import com.devcollab.knowledgecore.workspace.application.WorkspaceMemberCacheService.CachedMembership;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class CaffeineArchitectureTests {

    @Test
    void configurationProvidesFiveBoundedNamedCachesWithStats() {
        CaffeineCacheConfig config = new CaffeineCacheConfig(properties(
                Duration.ofSeconds(1)
        ));

        assertThat(config.workspaceMemberLocalCache()).isNotNull();
        assertThat(config.documentTreeLocalCache()).isNotNull();
        assertThat(config.documentSchemaLocalCache()).isNotNull();
        assertThat(config.publishedDocumentLocalCache()).isNotNull();
        assertThat(config.approvedAdrLocalCache()).isNotNull();

        Cache<String, DocumentVersion> weighted =
                config.publishedDocumentLocalCache();
        weighted.put("doc:1", version("x".repeat(500)));
        weighted.put("doc:2", version("y".repeat(500)));
        weighted.cleanUp();
        assertThat(weighted.estimatedSize()).isLessThanOrEqualTo(1);
        assertThat(weighted.stats().evictionCount()).isGreaterThan(0);
    }

    @Test
    void boundedLoaderUsesCaffeineSingleFlightForSameKey() throws Exception {
        CacheProperties properties = properties(Duration.ofSeconds(1));
        BoundedCacheLoader loader = new BoundedCacheLoader(properties);
        Cache<String, String> cache = Caffeine.newBuilder().build();
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch firstLoadStarted = new CountDownLatch(1);
        try {
            var first = java.util.concurrent.CompletableFuture.supplyAsync(() ->
                    loader.get("test", cache, "same", () -> {
                        loads.incrementAndGet();
                        firstLoadStarted.countDown();
                        sleep(100);
                        return "value";
                    })
            );
            assertThat(firstLoadStarted.await(1, TimeUnit.SECONDS)).isTrue();
            var second = java.util.concurrent.CompletableFuture.supplyAsync(() ->
                    loader.get("test", cache, "same", () -> {
                        loads.incrementAndGet();
                        return "other";
                    })
            );

            assertThat(first.get()).isEqualTo("value");
            assertThat(second.get()).isEqualTo("value");
            assertThat(loads).hasValue(1);
        } finally {
            loader.shutdown();
        }
    }

    @Test
    void boundedLoaderFailsFastWhenSourceExceedsTimeout() {
        BoundedCacheLoader loader = new BoundedCacheLoader(
                properties(Duration.ofMillis(20))
        );
        try {
            assertThatThrownBy(() -> loader.get(
                    "published-document",
                    Caffeine.newBuilder().build(),
                    "slow",
                    () -> {
                        sleep(200);
                        return "late";
                    }
            )).isInstanceOf(CacheLoadTimeoutException.class);
        } finally {
            loader.shutdown();
        }
    }

    @Test
    void kafkaInvalidationEvictsLocalCacheEntryAndPrefix() throws Exception {
        CacheProperties properties = properties(Duration.ofSeconds(1));
        BoundedCacheLoader loader = new BoundedCacheLoader(properties);
        Cache<String, CachedMembership> memberCache = Caffeine.newBuilder().build();
        Cache<String, DocumentList> treeCache = Caffeine.newBuilder().build();
        Cache<String, DocumentSchemaDescriptor> schemaCache = Caffeine.newBuilder().build();
        Cache<String, DocumentVersion> publishedCache = Caffeine.newBuilder().build();
        Cache<String, DocumentVersion> adrCache = Caffeine.newBuilder().build();
        PublishedDocumentCacheService published =
                new PublishedDocumentCacheService(publishedCache, loader, redisCache(properties), properties);
        ApprovedAdrCacheService approvedAdr =
                new ApprovedAdrCacheService(adrCache, loader, redisCache(properties), properties);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        CacheInvalidationKafkaConsumer consumer = new CacheInvalidationKafkaConsumer(
                mapper,
                memberCache,
                treeCache,
                schemaCache,
                published,
                approvedAdr
        );
        UUID workspaceId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID otherDocumentId = UUID.randomUUID();
        publishedCache.put(CacheKey.publishedDocument(workspaceId, documentId, UUID.randomUUID()), version("one"));
        publishedCache.put(CacheKey.publishedDocument(workspaceId, documentId, UUID.randomUUID()), version("two"));
        publishedCache.put(CacheKey.publishedDocument(workspaceId, otherDocumentId, UUID.randomUUID()), version("other"));
        String prefix = "published-document:" + workspaceId + ":" + documentId + ":";
        String payload = mapper.writeValueAsString(java.util.Map.of(
                "cacheName", "published-document",
                "cacheKey", CacheKey.publishedDocumentPrefix(workspaceId, documentId)
        ));
        String message = mapper.writeValueAsString(new OutboxKafkaMessage(
                UUID.randomUUID(),
                "CACHE",
                documentId,
                "CACHE_INVALIDATED",
                payload,
                Instant.now()
        ));
        try {
            consumer.consume(message);
            assertThat(publishedCache.asMap().keySet())
                    .noneMatch(key -> key.startsWith(prefix));
            assertThat(publishedCache.estimatedSize()).isEqualTo(1);
        } finally {
            loader.shutdown();
        }
    }

    @Test
    void malformedInvalidationDoesNotBlockConsumerBecauseTtlIsFallback() {
        CacheProperties properties = properties(Duration.ofSeconds(1));
        BoundedCacheLoader loader = new BoundedCacheLoader(properties);
        try {
            CacheInvalidationKafkaConsumer consumer = new CacheInvalidationKafkaConsumer(
                    new ObjectMapper().registerModule(new JavaTimeModule()),
                    Caffeine.newBuilder().build(),
                    Caffeine.newBuilder().build(),
                    Caffeine.newBuilder().build(),
                    new PublishedDocumentCacheService(
                            Caffeine.newBuilder().build(), loader, redisCache(properties), properties
                    ),
                    new ApprovedAdrCacheService(
                            Caffeine.newBuilder().build(), loader, redisCache(properties), properties
                    )
            );

            consumer.consume("not-json");
        } finally {
            loader.shutdown();
        }
    }

    private RedisCacheService redisCache(CacheProperties properties) {
        return new RedisCacheService(
                mock(StringRedisTemplate.class),
                new ObjectMapper(),
                properties
        );
    }

    private CacheProperties properties(Duration loadTimeout) {
        return new CacheProperties(
                true,
                Duration.ofMinutes(10),
                Duration.ofMinutes(5),
                Duration.ofMinutes(30),
                Duration.ofHours(1),
                new CacheProperties.Local(
                        true,
                        Duration.ofMinutes(2),
                        Duration.ofMinutes(1),
                        100,
                        100,
                        Duration.ofHours(1),
                        Duration.ofMinutes(10),
                        Duration.ofMinutes(30),
                        1_000,
                        300,
                        300,
                        2,
                        10,
                        loadTimeout
                )
        );
    }

    private DocumentVersion version(String payload) {
        return new DocumentVersion(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                "Title",
                DocumentVersionStatus.CURRENT,
                payload,
                UUID.randomUUID(),
                Instant.now()
        );
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
