package com.devcollab.knowledgecore.common.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
public class RedisFixedWindowLimiter {

    private static final Logger log =
            LoggerFactory.getLogger(RedisFixedWindowLimiter.class);

    private static final DefaultRedisScript<List> ACQUIRE_SCRIPT =
            new DefaultRedisScript<>("""
                    local current = redis.call('INCR', KEYS[1])
                    if current == 1 then
                        redis.call('PEXPIRE', KEYS[1], ARGV[1])
                    end
                    local ttl = redis.call('PTTL', KEYS[1])
                    return {current, ttl}
                    """, List.class);

    private final StringRedisTemplate redisTemplate;

    public RedisFixedWindowLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public LimitDecision acquire(String key, int limit, Duration window) {
        try {
            List<?> result = redisTemplate.execute(
                    ACQUIRE_SCRIPT,
                    List.of(key),
                    Long.toString(window.toMillis())
            );
            if (result == null || result.size() < 2) {
                log.warn("Redis limiter returned no result for key={}, allowing request", key);
                return LimitDecision.allowedWithoutRedis();
            }
            long count = ((Number) result.get(0)).longValue();
            long ttlMillis = Math.max(0, ((Number) result.get(1)).longValue());
            return new LimitDecision(count <= limit, ttlMillis);
        } catch (RuntimeException exception) {
            log.warn(
                    "Redis limiter unavailable for key={}, allowing request: {}",
                    key,
                    exception.getMessage()
            );
            log.debug("Redis limiter failure detail", exception);
            return LimitDecision.allowedWithoutRedis();
        }
    }

    public void reset(String key) {
        try {
            redisTemplate.delete(key);
        } catch (RuntimeException exception) {
            log.warn("Redis limiter reset failed for key={}: {}", key, exception.getMessage());
            log.debug("Redis limiter reset failure detail", exception);
        }
    }

    public record LimitDecision(
            boolean allowed,
            long retryAfterMillis
    ) {
        static LimitDecision allowedWithoutRedis() {
            return new LimitDecision(true, 0);
        }
    }
}
