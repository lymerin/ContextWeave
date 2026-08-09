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
    private static final String PLACEHOLDER_SECRET =
            "devcollab-local-development-secret-change-me";

    public JwtProperties {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("JWT issuer must not be blank");
        }

        if (audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("JWT audience must not be blank");
        }

        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException(
                    "JWT secret must contain at least 32 characters"
            );
        }
        if (PLACEHOLDER_SECRET.equals(secret)) {
            throw new IllegalArgumentException(
                    "JWT secret must not be the default placeholder; "
                            + "set DEVCOLLAB_JWT_SECRET explicitly"
            );
        }

        if (accessTokenTtl == null
                || accessTokenTtl.isZero()
                || accessTokenTtl.isNegative()) {
            throw new IllegalArgumentException(
                    "JWT access-token-ttl must be positive"
            );
        }
    }
}
