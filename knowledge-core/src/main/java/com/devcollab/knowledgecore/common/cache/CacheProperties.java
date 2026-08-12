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
            local = new Local(
                    true,
                    Duration.ofMinutes(2),
                    Duration.ofMinutes(1),
                    10_000,
                    1_000,
                    Duration.ofMinutes(10),
                    Duration.ofMinutes(30),
                    50_000_000,
                    20_000_000
            );
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
            Duration publishedDocumentTtl,
            Duration approvedAdrTtl,
            long publishedDocumentMaximumWeight,
            long approvedAdrMaximumWeight
    ) {

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
            if (publishedDocumentTtl == null) {
                publishedDocumentTtl = Duration.ofMinutes(10);
            }
            if (approvedAdrTtl == null) {
                approvedAdrTtl = Duration.ofMinutes(30);
            }
            if (publishedDocumentMaximumWeight <= 0) {
                publishedDocumentMaximumWeight = 50_000_000;
            }
            if (approvedAdrMaximumWeight <= 0) {
                approvedAdrMaximumWeight = 20_000_000;
            }
        }
    }
}
