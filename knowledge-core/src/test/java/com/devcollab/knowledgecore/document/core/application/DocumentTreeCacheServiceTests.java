package com.devcollab.knowledgecore.document.core.application;

import com.devcollab.knowledgecore.common.cache.CacheKey;
import com.devcollab.knowledgecore.common.cache.CacheProperties;
import com.devcollab.knowledgecore.common.cache.RedisCacheService;
import com.devcollab.knowledgecore.document.tree.application.DocumentTreeCacheService.DocumentList;
import com.devcollab.knowledgecore.document.core.domain.Document;
import com.devcollab.knowledgecore.document.core.domain.DocumentRepository;
import com.devcollab.knowledgecore.document.core.domain.DocumentReviewStatus;
import com.devcollab.knowledgecore.document.core.domain.DocumentType;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentTreeCacheServiceTests {

    @Mock
    private RedisCacheService redisCache;

    @Mock
    private DocumentRepository documentRepository;

    private Cache<String, DocumentList> localCache;
    private DocumentTreeCacheService service;
    private CacheProperties properties;

    private final UUID workspaceId = UUID.randomUUID();
    private final List<Document> documents = List.of(
            new Document(
                    UUID.randomUUID(), workspaceId, null, "API 设计",
                    DocumentType.API, DocumentReviewStatus.PUBLISHED,
                    UUID.randomUUID(), Instant.now(), Instant.now()
            )
    );

    @BeforeEach
    void setUp() {
        localCache = Caffeine.newBuilder().maximumSize(100).build();
        properties = new CacheProperties(
                true,
                Duration.ofMinutes(10),
                Duration.ofMinutes(5),
                new CacheProperties.Local(
                        true,
                        Duration.ofMinutes(2),
                        Duration.ofMinutes(1),
                        100,
                        100
                )
        );
        service = new DocumentTreeCacheService(
                localCache, redisCache, documentRepository, properties
        );
    }

    @Test
    @DisplayName("Caffeine hit returns without Redis or Repository call")
    void caffeineHit() {
        String key = CacheKey.documentTree(workspaceId);
        localCache.put(key, new DocumentList(documents));

        List<Document> result = service.listTreeSource(workspaceId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).title()).isEqualTo("API 设计");
        verify(redisCache, never()).get(anyString(), any());
        verify(documentRepository, never()).findAllByWorkspaceId(any());
    }

    @Test
    @DisplayName("Caffeine miss + Redis hit → populates Caffeine, skips Repository")
    void caffeineMissRedisHit() {
        String key = CacheKey.documentTree(workspaceId);
        when(redisCache.get(eq(key), eq(DocumentList.class)))
                .thenReturn(Optional.of(new DocumentList(documents)));

        List<Document> result = service.listTreeSource(workspaceId);

        assertThat(result).hasSize(1);
        assertThat(localCache.getIfPresent(key)).isNotNull();
        verify(documentRepository, never()).findAllByWorkspaceId(any());
    }

    @Test
    @DisplayName("All miss → queries Repository, populates both caches")
    void allMissQueriesRepo() {
        String key = CacheKey.documentTree(workspaceId);
        when(redisCache.get(eq(key), eq(DocumentList.class)))
                .thenReturn(Optional.empty());
        when(documentRepository.findAllByWorkspaceId(workspaceId))
                .thenReturn(documents);

        List<Document> result = service.listTreeSource(workspaceId);

        assertThat(result).hasSize(1);
        verify(redisCache).set(eq(key), any(DocumentList.class), eq(properties.documentTreeTtl()));
        assertThat(localCache.getIfPresent(key)).isNotNull();
    }

    @Test
    @DisplayName("evictTree invalidates both Caffeine and Redis")
    void evictBothCaches() {
        String key = CacheKey.documentTree(workspaceId);
        localCache.put(key, new DocumentList(documents));

        service.evictTree(workspaceId);

        assertThat(localCache.getIfPresent(key)).isNull();
        verify(redisCache).evict(key);
    }

    @Test
    @DisplayName("Redis exception falls back to Repository")
    void redisFailureFallsBackToRepo() {
        when(redisCache.get(anyString(), any()))
                .thenThrow(new RuntimeException("Redis down"));
        when(documentRepository.findAllByWorkspaceId(workspaceId))
                .thenReturn(documents);

        List<Document> result = service.listTreeSource(workspaceId);

        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("Global cache disabled bypasses Caffeine and Redis")
    void cacheDisabledBypassesBothCaches() {
        String key = CacheKey.documentTree(workspaceId);
        localCache.put(key, new DocumentList(documents));
        properties = new CacheProperties(
                false,
                Duration.ofMinutes(10),
                Duration.ofMinutes(5),
                new CacheProperties.Local(
                        true,
                        Duration.ofMinutes(2),
                        Duration.ofMinutes(1),
                        100,
                        100
                )
        );
        service = new DocumentTreeCacheService(
                localCache, redisCache, documentRepository, properties
        );
        when(documentRepository.findAllByWorkspaceId(workspaceId))
                .thenReturn(List.of());

        List<Document> result = service.listTreeSource(workspaceId);

        assertThat(result).isEmpty();
        verify(redisCache, never()).get(anyString(), any());
        verify(redisCache, never()).set(anyString(), any(), any());
    }
}
