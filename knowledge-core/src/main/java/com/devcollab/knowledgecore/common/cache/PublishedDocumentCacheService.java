package com.devcollab.knowledgecore.common.cache;

import com.devcollab.knowledgecore.document.version.domain.DocumentVersion;
import com.github.benmanes.caffeine.cache.Cache;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Published document version snapshot cache: Caffeine L1 → Redis L2 → DB.
 *
 * <p>Read path follows the pattern already validated by
 * {@code WorkspaceMemberCacheService}: L1 hit returns immediately; L1 miss
 * checks Redis and backfills L1; both miss loads from DB and writes Redis.
 * Redis errors are already swallowed by {@link RedisCacheService}, so this
 * class does not re-guard every call — on any Redis failure the read
 * degrades to DB and TTL remains the fallback for stale entries.
 */
@Component
public class PublishedDocumentCacheService {

    private final Cache<String, DocumentVersion> cache;
    private final BoundedCacheLoader loader;
    private final RedisCacheService redisCache;
    private final CacheProperties properties;

    public PublishedDocumentCacheService(
            @Qualifier("publishedDocumentLocalCache")
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
            UUID documentId,
            UUID versionId,
            Supplier<DocumentVersion> source
    ) {
        String key = CacheKey.publishedDocument(workspaceId, documentId, versionId);
        return loader.get("published-document", cache, key, () -> {
            Optional<DocumentVersion> redisHit =
                    redisCache.get(key, DocumentVersion.class);
            if (redisHit.isPresent()) {
                return redisHit.get();
            }
            DocumentVersion fromDb = source.get();
            redisCache.set(key, fromDb, properties.publishedDocumentTtl());
            return fromDb;
        });
    }

    /**
     * Invalidates L1 (exact key or prefix pattern) and best-effort evicts the
     * same keys from Redis. Keys missed by SCAN are covered by Redis TTL.
     */
    public void invalidate(String keyOrPattern) {
        invalidateKeyOrPrefix(cache, keyOrPattern);
        redisCache.evictByPattern(keyOrPattern);
    }

    static <T> void invalidateKeyOrPrefix(
            Cache<String, T> cache,
            String keyOrPattern
    ) {
        if (keyOrPattern.endsWith("*")) {
            String prefix = keyOrPattern.substring(0, keyOrPattern.length() - 1);
            cache.asMap().keySet().removeIf(key -> key.startsWith(prefix));
            return;
        }
        cache.invalidate(keyOrPattern);
    }
}
