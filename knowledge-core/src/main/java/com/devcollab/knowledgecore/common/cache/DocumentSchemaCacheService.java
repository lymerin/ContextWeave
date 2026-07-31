package com.devcollab.knowledgecore.common.cache;

import com.devcollab.knowledgecore.document.core.domain.DocumentBlockType;
import com.devcollab.knowledgecore.document.core.domain.DocumentType;
import com.github.benmanes.caffeine.cache.Cache;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

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
