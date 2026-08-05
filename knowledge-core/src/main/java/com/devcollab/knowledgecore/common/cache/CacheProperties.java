package com.devcollab.knowledgecore.common.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "devcollab.cache")
public record CacheProperties(
        boolean enabled,
        Duration workspaceMemberTtl,
        Duration documentTreeTtl,
        Duration publishedDocumentTtl,
        Duration approvedAdrTtl,
        Local local
) {

    public CacheProperties {
        if (workspaceMemberTtl == null) {
            workspaceMemberTtl = Duration.ofMinutes(10);
        }
        if (documentTreeTtl == null) {
            documentTreeTtl = Duration.ofMinutes(5);
        }
        if (publishedDocumentTtl == null) {
            publishedDocumentTtl = Duration.ofMinutes(30);
        }
        if (approvedAdrTtl == null) {
            approvedAdrTtl = Duration.ofHours(1);
        }
        if (local == null) {
            local = new Local(true, Duration.ofMinutes(2), Duration.ofMinutes(1), 10_000, 1_000);
        }
    }

    /**
     * Caffeine L1 (instance-local) cache settings.
     * <p>These TTL values are intentionally shorter than the Redis L2 TTLs
     * so that stale entries are naturally expired at the instance level
     * while Redis serves as the shared source of cached data.
     */
    public record Local(
            boolean enabled,
            Duration workspaceMemberTtl,
            Duration documentTreeTtl,
            int workspaceMemberMaximumSize,
            int documentTreeMaximumSize,
            Duration documentSchemaTtl,
            Duration publishedDocumentTtl,
            Duration approvedAdrTtl,
            long documentSchemaMaximumWeight,
            long publishedDocumentMaximumWeight,
            long approvedAdrMaximumWeight,
            int loadingThreads,
            int loadingQueueCapacity,
            Duration loadTimeout
    ) {

        public Local(
                boolean enabled,
                Duration workspaceMemberTtl,
                Duration documentTreeTtl,
                int workspaceMemberMaximumSize,
                int documentTreeMaximumSize
        ) {
            this(
                    enabled,
                    workspaceMemberTtl,
                    documentTreeTtl,
                    workspaceMemberMaximumSize,
                    documentTreeMaximumSize,
                    Duration.ofHours(6),
                    Duration.ofMinutes(10),
                    Duration.ofMinutes(30),
                    10_000,
                    50_000_000,
                    20_000_000,
                    4,
                    200,
                    Duration.ofSeconds(2)
            );
        }

        public Local {
            if (workspaceMemberTtl == null) {
                workspaceMemberTtl = Duration.ofMinutes(2);
            }
            if (documentTreeTtl == null) {
                documentTreeTtl = Duration.ofMinutes(1);
            }
            if (workspaceMemberMaximumSize <= 0) {
                workspaceMemberMaximumSize = 10_000;
            }
            if (documentTreeMaximumSize <= 0) {
                documentTreeMaximumSize = 1_000;
            }
            if (documentSchemaTtl == null) {
                documentSchemaTtl = Duration.ofHours(6);
            }
            if (publishedDocumentTtl == null) {
                publishedDocumentTtl = Duration.ofMinutes(10);
            }
            if (approvedAdrTtl == null) {
                approvedAdrTtl = Duration.ofMinutes(30);
            }
            if (documentSchemaMaximumWeight <= 0) {
                documentSchemaMaximumWeight = 10_000;
            }
            if (publishedDocumentMaximumWeight <= 0) {
                publishedDocumentMaximumWeight = 50_000_000;
            }
            if (approvedAdrMaximumWeight <= 0) {
                approvedAdrMaximumWeight = 20_000_000;
            }
            if (loadingThreads <= 0) {
                loadingThreads = 4;
            }
            if (loadingQueueCapacity <= 0) {
                loadingQueueCapacity = 200;
            }
            if (loadTimeout == null || loadTimeout.isZero() || loadTimeout.isNegative()) {
                loadTimeout = Duration.ofSeconds(2);
            }
        }
    }
}
