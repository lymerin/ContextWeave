package com.devcollab.knowledgecore.common.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Minimal Redis cache facade.
 *
 * <p>All Redis errors are logged and swallowed — cache is a performance
 * optimisation, never the authoritative data source.
 */
@Service
public class RedisCacheService {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheService.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final CacheProperties properties;

    public RedisCacheService(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            CacheProperties properties
    ) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public <T> Optional<T> get(String key, Class<T> type) {
        if (!properties.enabled()) {
            return Optional.empty();
        }
        try {
            String json = redis.opsForValue().get(key);
            if (json == null || json.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, type));
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize cache key={}: {}", key, e.getMessage());
            evict(key);
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Redis get failed for key={}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    public <T> void set(String key, T value, Duration ttl) {
        if (!properties.enabled()) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(value);
            redis.opsForValue().set(key, json, ttl);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize value for cache key={}: {}", key, e.getMessage());
        } catch (Exception e) {
            log.warn("Redis set failed for key={}: {}", key, e.getMessage());
        }
    }

    public void evict(String key) {
        if (!properties.enabled()) {
            return;
        }
        try {
            redis.delete(key);
        } catch (Exception e) {
            log.warn("Redis evict failed for key={}: {}", key, e.getMessage());
        }
    }

    /**
     * Evicts every key matching a glob pattern (e.g. {@code published-document:ws:doc:*}).
     *
     * <p>Redis has no atomic delete-by-prefix; SCAN + DEL is best-effort and
     * keys missed here are covered by TTL expiry. Cache is a performance
     * optimisation, never the authoritative data source.
     */
    public void evictByPattern(String pattern) {
        if (!properties.enabled()) {
            return;
        }
        try {
            ScanOptions options = ScanOptions.scanOptions()
                    .match(pattern)
                    .count(100)
                    .build();
            try (Cursor<String> cursor = redis.scan(options)) {
                cursor.forEachRemaining(redis::delete);
            }
        } catch (Exception e) {
            log.warn("Redis evict-by-pattern failed for pattern={}: {}", pattern, e.getMessage());
        }
    }
}
