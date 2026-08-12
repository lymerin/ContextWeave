package com.devcollab.knowledgecore.common.redis;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "devcollab.redis.coordination")
public record RedisCoordinationProperties(
        LoginLimit loginLimit,
        RateLimit rateLimit
) {

    public record LoginLimit(int maxAttempts, Duration window) {
        public LoginLimit {
            if (maxAttempts <= 0) {
                maxAttempts = 5;
            }
            if (window == null || window.isZero() || window.isNegative()) {
                window = Duration.ofMinutes(15);
            }
        }
    }

    public record RateLimit(int maxOperations, Duration window) {
        public RateLimit {
            if (maxOperations <= 0) {
                maxOperations = 120;
            }
            if (window == null || window.isZero() || window.isNegative()) {
                window = Duration.ofMinutes(1);
            }
        }
    }
}
