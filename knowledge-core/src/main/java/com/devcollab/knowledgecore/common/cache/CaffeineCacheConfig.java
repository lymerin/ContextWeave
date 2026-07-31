package com.devcollab.knowledgecore.common.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.devcollab.knowledgecore.document.tree.application.DocumentTreeCacheService.DocumentList;
import com.devcollab.knowledgecore.document.version.domain.DocumentVersion;
import com.devcollab.knowledgecore.workspace.application.WorkspaceMemberCacheService.CachedMembership;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Caffeine L1 (instance-local) cache configuration.
 *
 * <p>L1 TTL values are intentionally shorter than L2 (Redis) TTLs.
 * This means stale entries naturally expire at the instance level
 * while Redis serves as the shared source of cached data.
 * Multi-instance environments should additionally use Kafka-based
 * cache invalidation events; for single-instance MVP, TTL-based
 * expiry is sufficient.
 */
@Configuration
@EnableConfigurationProperties(CacheProperties.class)
public class CaffeineCacheConfig {

    private static final Logger log = LoggerFactory.getLogger(CaffeineCacheConfig.class);

    private final CacheProperties properties;

    public CaffeineCacheConfig(CacheProperties properties) {
        this.properties = properties;
    }

    @Bean
    public Cache<String, CachedMembership> workspaceMemberLocalCache() {
        CacheProperties.Local local = properties.local();
        return Caffeine.newBuilder()
                .expireAfterWrite(local.workspaceMemberTtl())
                .maximumSize(local.workspaceMemberMaximumSize())
                .recordStats()
                .removalListener((String key, CachedMembership value, com.github.benmanes.caffeine.cache.RemovalCause cause) ->
                        log.debug("Cache workspace-member removed key={} cause={}", key, cause))
                .build();
    }

    @Bean
    public Cache<String, DocumentList> documentTreeLocalCache() {
        CacheProperties.Local local = properties.local();
        return Caffeine.newBuilder()
                .expireAfterWrite(local.documentTreeTtl())
                .maximumSize(local.documentTreeMaximumSize())
                .recordStats()
                .removalListener((String key, DocumentList value, com.github.benmanes.caffeine.cache.RemovalCause cause) ->
                        log.debug("Cache document-tree removed key={} cause={}", key, cause))
                .build();
    }

    @Bean("documentSchemaLocalCache")
    public Cache<String, DocumentSchemaDescriptor> documentSchemaLocalCache() {
        CacheProperties.Local local = properties.local();
        return Caffeine.newBuilder()
                .expireAfterAccess(local.documentSchemaTtl())
                .maximumWeight(local.documentSchemaMaximumWeight())
                .weigher((String key, DocumentSchemaDescriptor value) -> value.estimatedWeight())
                .recordStats()
                .removalListener((String key, DocumentSchemaDescriptor value, com.github.benmanes.caffeine.cache.RemovalCause cause) ->
                        log.debug("Cache document-schema removed key={} cause={}", key, cause))
                .build();
    }

    @Bean("publishedDocumentLocalCache")
    public Cache<String, DocumentVersion> publishedDocumentLocalCache() {
        return versionCache(
                "published-document",
                properties.local().publishedDocumentTtl(),
                properties.local().publishedDocumentMaximumWeight()
        );
    }

    @Bean("approvedAdrLocalCache")
    public Cache<String, DocumentVersion> approvedAdrLocalCache() {
        return versionCache(
                "approved-adr",
                properties.local().approvedAdrTtl(),
                properties.local().approvedAdrMaximumWeight()
        );
    }

    private Cache<String, DocumentVersion> versionCache(
            String name,
            java.time.Duration ttl,
            long maximumWeight
    ) {
        return Caffeine.newBuilder()
                .expireAfterAccess(ttl)
                .maximumWeight(maximumWeight)
                .weigher((String key, DocumentVersion value) ->
                        Math.max(1, key.length() * 2
                                + value.title().length() * 2
                                + value.snapshotPayload().length() * 2))
                .recordStats()
                .removalListener((String key, DocumentVersion value, com.github.benmanes.caffeine.cache.RemovalCause cause) ->
                        log.debug("Cache {} removed key={} cause={}", name, key, cause))
                .build();
    }
}
