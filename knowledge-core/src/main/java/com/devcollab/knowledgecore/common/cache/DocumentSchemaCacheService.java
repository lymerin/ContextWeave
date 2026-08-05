package com.devcollab.knowledgecore.common.cache;

import com.devcollab.knowledgecore.document.core.domain.DocumentBlockType;
import com.devcollab.knowledgecore.document.core.domain.DocumentType;
import com.github.benmanes.caffeine.cache.Cache;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Document schema cache, intentionally L1-only (no Redis L2).
 *
 * <p>The source of truth here is a static enum projection
 * ({@code List.of(DocumentBlockType.values())}), not a database read: all
 * instances produce identical content, there is no cross-instance staleness
 * and no DB fall-through, so a Redis layer would only add serialization and
 * network cost. Keep this L1-only; see the Redis L2 access decision in
 * {@code 99-local-redis-caffeine-architecture-learning-v0.3.md} §3.
 */
@Component
public class DocumentSchemaCacheService {

    private final Cache<String, DocumentSchemaDescriptor> cache;
    private final BoundedCacheLoader loader;

    public DocumentSchemaCacheService(
            @Qualifier("documentSchemaLocalCache")
            Cache<String, DocumentSchemaDescriptor> cache,
            BoundedCacheLoader loader
    ) {
        this.cache = cache;
        this.loader = loader;
    }

    public DocumentSchemaDescriptor get(
            DocumentType documentType,
            String schemaVersion
    ) {
        String key = documentType + ":" + schemaVersion;
        return loader.get(
                "document-schema",
                cache,
                key,
                () -> new DocumentSchemaDescriptor(
                        documentType,
                        schemaVersion,
                        List.of(DocumentBlockType.values())
                )
        );
    }

    public void invalidate(String key) {
        cache.invalidate(key);
    }
}
