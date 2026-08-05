package com.devcollab.knowledgecore.common.cache;

import com.devcollab.knowledgecore.document.version.domain.DocumentVersion;
import com.github.benmanes.caffeine.cache.Cache;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Approved ADR version snapshot cache: Caffeine L1 → Redis L2 → DB.
 *
 * <p>Mirror of {@link PublishedDocumentCacheService} with a distinct Redis
 * namespace ({@code approved-adr:...}); the two caches store the same
 * {@code DocumentVersion} type, so the semantic key prefix is what prevents
 * them from overwriting each other's Redis keys.
 */
@Component
public class ApprovedAdrCacheService {

    private final Cache<String, DocumentVersion> cache;
    private final BoundedCacheLoader loader;
    private final RedisCacheService redisCache;
    private final CacheProperties properties;

    public ApprovedAdrCacheService(
            @Qualifier("approvedAdrLocalCache")
            Cache<String, DocumentVersion> cache,
            BoundedCacheLoader loader,
            RedisCacheService redisCache,
            CacheProperties properties
    ) {
        this.cache = cache;
        this.loader = loader;
        this.redisCache = redisCache;
        this.properties = properties;
    }

    public DocumentVersion get(
            UUID workspaceId,
            UUID adrId,
            UUID versionId,
            Supplier<DocumentVersion> source
    ) {
        String key = CacheKey.approvedAdr(workspaceId, adrId, versionId);
        return loader.get("approved-adr", cache, key, () -> {
            Optional<DocumentVersion> redisHit =
                    redisCache.get(key, DocumentVersion.class);
            if (redisHit.isPresent()) {
                return redisHit.get();
            }
            DocumentVersion fromDb = source.get();
            redisCache.set(key, fromDb, properties.approvedAdrTtl());
            return fromDb;
        });
    }

    /**
     * Invalidates L1 (exact key or prefix pattern) and best-effort evicts the
     * same keys from Redis. Keys missed by SCAN are covered by Redis TTL.
     */
    public void invalidate(String keyOrPattern) {
        PublishedDocumentCacheService.invalidateKeyOrPrefix(cache, keyOrPattern);
        redisCache.evictByPattern(keyOrPattern);
    }
}
