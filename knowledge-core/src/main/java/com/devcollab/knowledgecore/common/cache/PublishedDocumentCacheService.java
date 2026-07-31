package com.devcollab.knowledgecore.common.cache;

import com.devcollab.knowledgecore.document.version.domain.DocumentVersion;
import com.github.benmanes.caffeine.cache.Cache;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.function.Supplier;

@Component
public class PublishedDocumentCacheService {

    private final Cache<String, DocumentVersion> cache;
    private final BoundedCacheLoader loader;

    public PublishedDocumentCacheService(
            @Qualifier("publishedDocumentLocalCache")
            Cache<String, DocumentVersion> cache,
            BoundedCacheLoader loader
    ) {
        this.cache = cache;
        this.loader = loader;
    }

    public DocumentVersion get(
            UUID documentId,
            UUID versionId,
            Supplier<DocumentVersion> source
    ) {
        String key = key(documentId, versionId);
        return loader.get("published-document", cache, key, source);
    }

    public void invalidate(String keyOrPattern) {
        invalidateKeyOrPrefix(cache, keyOrPattern);
    }

    public static String key(UUID documentId, UUID versionId) {
        return documentId + ":" + versionId;
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
