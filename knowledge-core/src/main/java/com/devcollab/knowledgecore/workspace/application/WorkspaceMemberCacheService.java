package com.devcollab.knowledgecore.workspace.application;

import com.devcollab.knowledgecore.common.cache.CacheKey;
import com.devcollab.knowledgecore.common.cache.CacheProperties;
import com.devcollab.knowledgecore.common.cache.RedisCacheService;
import com.devcollab.knowledgecore.workspace.domain.WorkspaceMember;
import com.devcollab.knowledgecore.workspace.infrastructure.JdbcWorkspaceMemberRepository;
import com.github.benmanes.caffeine.cache.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
public class WorkspaceMemberCacheService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceMemberCacheService.class);

    private final Cache<String, WorkspaceMember> localCache;
    private final RedisCacheService redisCache;
    private final JdbcWorkspaceMemberRepository memberRepository;
    private final CacheProperties properties;

    public WorkspaceMemberCacheService(
            Cache<String, WorkspaceMember> localCache,
            RedisCacheService redisCache,
            JdbcWorkspaceMemberRepository memberRepository,
            CacheProperties properties
    ) {
        this.localCache = localCache;
        this.redisCache = redisCache;
        this.memberRepository = memberRepository;
        this.properties = properties;
    }

    public Optional<WorkspaceMember> findCached(UUID workspaceId, UUID userId) {
        String key = CacheKey.workspaceMember(workspaceId, userId);

        if (localCacheEnabled()) {
            WorkspaceMember local = localCache.getIfPresent(key);
            if (local != null) {
                return Optional.of(local);
            }
        }

        if (properties.enabled()) {
            Optional<WorkspaceMember> redisHit;
            try {
                redisHit = redisCache.get(key, WorkspaceMember.class);
            } catch (Exception e) {
                log.warn(
                        "Redis get failed for key={}, falling back to Repository: {}",
                        key,
                        e.getMessage()
                );
                redisHit = Optional.empty();
            }
            if (redisHit.isPresent()) {
                WorkspaceMember cached = redisHit.get();
                putLocal(key, cached);
                return Optional.of(cached);
            }
        }

        Optional<WorkspaceMember> member = memberRepository.findByWorkspaceIdAndUserId(workspaceId, userId);
        if (member.isPresent()) {
            WorkspaceMember cached = member.get();
            if (properties.enabled()) {
                redisCache.set(key, cached, properties.workspaceMemberTtl());
            }
            putLocal(key, cached);
        }
        return member;
    }

    public void evict(UUID workspaceId, UUID userId) {
        String key = CacheKey.workspaceMember(workspaceId, userId);
        if (localCacheEnabled()) {
            localCache.invalidate(key);
        }
        if (properties.enabled()) {
            redisCache.evict(key);
        }
    }

    private void putLocal(String key, WorkspaceMember cached) {
        if (localCacheEnabled()) {
            localCache.put(key, cached);
        }
    }

    private boolean localCacheEnabled() {
        return properties.enabled() && properties.local().enabled();
    }
}
