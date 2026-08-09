package com.devcollab.knowledgecore.auth.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "devcollab.security.jwt")
public record JwtProperties(
        String issuer,
        String audience,
        String secret,
        Duration accessTokenTtl
) {

    public JwtProperties {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("JWT issuer 不能为空");
        }

        if (audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("JWT audience 不能为空");
        }

        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException("JWT secret 长度不能少于 32 个字符");
        }

        if (accessTokenTtl == null
                || accessTokenTtl.isZero()
                || accessTokenTtl.isNegative()) {
            throw new IllegalArgumentException("JWT access-token-ttl 必须大于 0");
        }
    }
}
