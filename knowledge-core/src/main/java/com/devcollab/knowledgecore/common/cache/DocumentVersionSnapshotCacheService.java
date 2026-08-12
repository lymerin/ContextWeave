package com.devcollab.knowledgecore.common.cache;

import com.devcollab.knowledgecore.document.version.domain.DocumentVersion;
import com.github.benmanes.caffeine.cache.Cache;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * DocumentVersion snapshot cache: Caffeine L1 → Redis L2 → DB.
 *
 * <p>Shared by the published-document and approved-adr caches; the
 * constructor key prefix keeps their Redis namespaces apart (both store the
 * same {@code DocumentVersion} type). Read path: L1 hit returns immediately;
 * L1 miss checks Redis and backfills L1; both miss loads from DB and writes
 * Redis. Caffeine serializes per-key loads, so concurrent misses load once.
 * Redis errors are swallowed by {@link RedisCacheService}, so on any Redis
 * failure the read degrades to DB and TTL remains the fallback for stale
 * entries.
 */
public class DocumentVersionSnapshotCacheService {

    private final String keyPrefix;
    private final Cache<String, DocumentVersion> cache;
    private final RedisCacheService redisCache;
    private final Duration ttl;

    public DocumentVersionSnapshotCacheService(
            String keyPrefix,
            Cache<String, DocumentVersion> cache,
            RedisCacheService redisCache,
            Duration ttl
    ) {
        this.keyPrefix = keyPrefix;
        this.cache = cache;
        this.redisCache = redisCache;
        this.ttl = ttl;
    }

    public DocumentVersion get(
            UUID workspaceId,
            UUID documentId,
            UUID versionId,
            Supplier<DocumentVersion> source
    ) {
        String key = keyPrefix + ":" + workspaceId + ":" + documentId
                + ":" + versionId;
        return cache.get(key, k -> {
            Optional<DocumentVersion> redisHit =
                    redisCache.get(key, DocumentVersion.class);
            if (redisHit.isPresent()) {
                return redisHit.get();
            }
            DocumentVersion fromDb = source.get();
            redisCache.set(key, fromDb, ttl);
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
