package com.devcollab.knowledgecore.common.redis;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisFixedWindowLimiterTests {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final RedisFixedWindowLimiter limiter =
            new RedisFixedWindowLimiter(redis);

    @Test
    void allowsAttemptInsideLimit() {
        when(redis.execute(any(), anyList(), anyString()))
                .thenReturn(List.of(3L, 30_000L));

        var decision = limiter.acquire(
                "rate-limit:user", 5, Duration.ofMinutes(1)
        );

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.retryAfterMillis()).isEqualTo(30_000);
    }

    @Test
    void rejectsAttemptAboveLimit() {
        when(redis.execute(any(), anyList(), anyString()))
                .thenReturn(List.of(6L, 20_000L));

        var decision = limiter.acquire(
                "login-limit:identity", 5, Duration.ofMinutes(15)
        );

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterMillis()).isEqualTo(20_000);
    }

    @Test
    void redisFailureFailsOpen() {
        when(redis.execute(any(), anyList(), anyString()))
                .thenThrow(new RedisConnectionFailureException("down"));

        var decision = limiter.acquire(
                "rate-limit:user", 5, Duration.ofMinutes(1)
        );

        assertThat(decision.allowed()).isTrue();
    }
}
