package com.devcollab.knowledgecore.common.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class RedisCacheServiceTests {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final RedisCacheService cache = new RedisCacheService(
            redis,
            new ObjectMapper(),
            new CacheProperties(
                    false,
                    Duration.ofMinutes(10),
                    Duration.ofMinutes(5),
                    Duration.ofMinutes(30),
                    Duration.ofHours(1),
                    null
            )
    );

    @Test
    void shouldSkipRedisClientWhenCacheIsDisabled() {
        assertThat(cache.get("key", String.class)).isEmpty();
        cache.set("key", "value", Duration.ofMinutes(1));
        cache.evict("key");

        verifyNoInteractions(redis);
    }
}
