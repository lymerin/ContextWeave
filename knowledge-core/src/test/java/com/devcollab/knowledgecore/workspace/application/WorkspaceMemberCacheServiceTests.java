package com.devcollab.knowledgecore.workspace.application;

import com.devcollab.knowledgecore.common.cache.CacheKey;
import com.devcollab.knowledgecore.common.cache.CacheProperties;
import com.devcollab.knowledgecore.common.cache.RedisCacheService;
import com.devcollab.knowledgecore.workspace.application.WorkspaceMemberCacheService.CachedMembership;
import com.devcollab.knowledgecore.workspace.domain.WorkspaceMember;
import com.devcollab.knowledgecore.workspace.domain.WorkspaceMemberRepository;
import com.devcollab.knowledgecore.workspace.domain.WorkspaceRole;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkspaceMemberCacheServiceTests {

    @Mock
    private RedisCacheService redisCache;

    @Mock
    private WorkspaceMemberRepository memberRepository;

    private Cache<String, CachedMembership> localCache;
    private WorkspaceMemberCacheService service;
    private CacheProperties properties;

    private final UUID workspaceId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final WorkspaceMember member = new WorkspaceMember(
            workspaceId, userId, WorkspaceRole.ADMIN, Instant.now()
    );

    @BeforeEach
    void setUp() {
        localCache = Caffeine.newBuilder().maximumSize(100).build();
        properties = new CacheProperties(
                true,
                Duration.ofMinutes(10),
                Duration.ofMinutes(5),
                Duration.ofMinutes(30),
                Duration.ofHours(1),
                new CacheProperties.Local(
                        true,
                        Duration.ofMinutes(2),
                        Duration.ofMinutes(1),
                        100,
                        100
                )
        );
        service = new WorkspaceMemberCacheService(
                localCache, redisCache, memberRepository, properties
        );
    }

    @Test
    @DisplayName("Caffeine hit returns without Redis or Repository call")
    void caffeineHit() {
        String key = CacheKey.workspaceMember(workspaceId, userId);
        localCache.put(key, CachedMembership.from(member));

        Optional<WorkspaceMember> result = service.findCached(workspaceId, userId);

        assertThat(result).isPresent();
        assertThat(result.get().role()).isEqualTo(WorkspaceRole.ADMIN);
        verify(redisCache, never()).get(anyString(), any());
        verify(memberRepository, never()).findByWorkspaceIdAndUserId(any(), any());
    }

    @Test
    @DisplayName("Caffeine miss + Redis hit → populates Caffeine, skips Repository")
    void caffeineMissRedisHit() {
        String key = CacheKey.workspaceMember(workspaceId, userId);
        when(redisCache.get(eq(key), eq(CachedMembership.class)))
                .thenReturn(Optional.of(CachedMembership.from(member)));

        Optional<WorkspaceMember> result = service.findCached(workspaceId, userId);

        assertThat(result).isPresent();
        assertThat(result.get().role()).isEqualTo(WorkspaceRole.ADMIN);
        assertThat(localCache.getIfPresent(key)).isNotNull();
        verify(memberRepository, never()).findByWorkspaceIdAndUserId(any(), any());
    }

    @Test
    @DisplayName("All miss → queries Repository, populates both caches")
    void allMissQueriesRepo() {
        String key = CacheKey.workspaceMember(workspaceId, userId);
        when(redisCache.get(eq(key), eq(CachedMembership.class)))
                .thenReturn(Optional.empty());
        when(memberRepository.findByWorkspaceIdAndUserId(workspaceId, userId))
                .thenReturn(Optional.of(member));

        Optional<WorkspaceMember> result = service.findCached(workspaceId, userId);

        assertThat(result).isPresent();
        verify(redisCache).set(eq(key), any(CachedMembership.class), eq(properties.workspaceMemberTtl()));
        assertThat(localCache.getIfPresent(key)).isNotNull();
    }

    @Test
    @DisplayName("evict invalidates both Caffeine and Redis")
    void evictBothCaches() {
        String key = CacheKey.workspaceMember(workspaceId, userId);
        localCache.put(key, CachedMembership.from(member));

        service.evict(workspaceId, userId);

        assertThat(localCache.getIfPresent(key)).isNull();
        verify(redisCache).evict(key);
    }

    @Test
    @DisplayName("Redis exception falls back to Repository")
    void redisFailureFallsBackToRepo() {
        when(redisCache.get(anyString(), any()))
                .thenThrow(new RuntimeException("Redis down"));
        when(memberRepository.findByWorkspaceIdAndUserId(workspaceId, userId))
                .thenReturn(Optional.of(member));

        Optional<WorkspaceMember> result = service.findCached(workspaceId, userId);

        assertThat(result).isPresent();
        assertThat(result.get().role()).isEqualTo(WorkspaceRole.ADMIN);
    }

    @Test
    @DisplayName("Global cache disabled bypasses Caffeine and Redis")
    void cacheDisabledBypassesBothCaches() {
        String key = CacheKey.workspaceMember(workspaceId, userId);
        localCache.put(key, CachedMembership.from(member));
        properties = new CacheProperties(
                false,
                Duration.ofMinutes(10),
                Duration.ofMinutes(5),
                Duration.ofMinutes(30),
                Duration.ofHours(1),
                new CacheProperties.Local(
                        true,
                        Duration.ofMinutes(2),
                        Duration.ofMinutes(1),
                        100,
                        100
                )
        );
        service = new WorkspaceMemberCacheService(
                localCache, redisCache, memberRepository, properties
        );
        when(memberRepository.findByWorkspaceIdAndUserId(workspaceId, userId))
                .thenReturn(Optional.empty());

        Optional<WorkspaceMember> result = service.findCached(workspaceId, userId);

        assertThat(result).isEmpty();
        verify(redisCache, never()).get(anyString(), any());
        verify(redisCache, never()).set(anyString(), any(), any());
    }
}
