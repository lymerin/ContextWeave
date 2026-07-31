package com.devcollab.knowledgecore.document.tree.application;

import com.devcollab.knowledgecore.common.cache.CacheKey;
import com.devcollab.knowledgecore.common.cache.CacheProperties;
import com.devcollab.knowledgecore.common.cache.RedisCacheService;
import com.devcollab.knowledgecore.document.core.domain.Document;
import com.devcollab.knowledgecore.document.core.domain.DocumentRepository;
import com.github.benmanes.caffeine.cache.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class DocumentTreeCacheService {

    private static final Logger log = LoggerFactory.getLogger(DocumentTreeCacheService.class);

    private final Cache<String, DocumentList> localCache;
    private final RedisCacheService redisCache;
    private final DocumentRepository documentRepository;
    private final CacheProperties properties;

    public DocumentTreeCacheService(
            Cache<String, DocumentList> localCache,
            RedisCacheService redisCache,
            DocumentRepository documentRepository,
            CacheProperties properties
    ) {
        this.localCache = localCache;
        this.redisCache = redisCache;
        this.documentRepository = documentRepository;
        this.properties = properties;
    }

    public List<Document> listTreeSource(UUID workspaceId) {
        String key = CacheKey.documentTree(workspaceId);

        if (localCacheEnabled()) {
            DocumentList local = localCache.getIfPresent(key);
            if (local != null) {
                return local.documents();
            }
        }

        if (properties.enabled()) {
            Optional<DocumentList> redisHit;
            try {
                redisHit = redisCache.get(key, DocumentList.class);
            } catch (Exception e) {
                log.warn(
                        "Redis get failed for key={}, falling back to Repository: {}",
                        key,
                        e.getMessage()
                );
                redisHit = Optional.empty();
            }
            if (redisHit.isPresent()) {
                DocumentList cached = redisHit.get();
                putLocal(key, cached);
                return cached.documents();
            }
        }

        List<Document> documents = documentRepository.findAllByWorkspaceId(workspaceId);
        DocumentList list = new DocumentList(documents);
        if (properties.enabled()) {
            redisCache.set(key, list, properties.documentTreeTtl());
        }
        putLocal(key, list);
        return documents;
    }

    public void evictTree(UUID workspaceId) {
        String key = CacheKey.documentTree(workspaceId);
        if (localCacheEnabled()) {
            localCache.invalidate(key);
        }
        if (properties.enabled()) {
            redisCache.evict(key);
        }
    }

    private void putLocal(String key, DocumentList list) {
        if (localCacheEnabled()) {
            localCache.put(key, list);
        }
    }

    private boolean localCacheEnabled() {
        return properties.enabled() && properties.local().enabled();
    }

    public record DocumentList(List<Document> documents) {
    }
}
