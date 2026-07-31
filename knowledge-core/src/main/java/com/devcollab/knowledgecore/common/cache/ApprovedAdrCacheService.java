package com.devcollab.knowledgecore.common.cache;

import com.devcollab.knowledgecore.document.version.domain.DocumentVersion;
import com.github.benmanes.caffeine.cache.Cache;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.function.Supplier;

@Component
public class ApprovedAdrCacheService {

    private final Cache<String, DocumentVersion> cache;
    private final BoundedCacheLoader loader;

    public ApprovedAdrCacheService(
            @Qualifier("approvedAdrLocalCache")
            Cache<String, DocumentVersion> cache,
            BoundedCacheLoader loader
    ) {
        this.cache = cache;
        this.loader = loader;
    }

    public DocumentVersion get(
            UUID adrId,
            UUID versionId,
            Supplier<DocumentVersion> source
    ) {
        String key = PublishedDocumentCacheService.key(adrId, versionId);
        return loader.get("approved-adr", cache, key, source);
    }

    public void invalidate(String keyOrPattern) {
        PublishedDocumentCacheService.invalidateKeyOrPrefix(
                cache, keyOrPattern
        );
    }
}
