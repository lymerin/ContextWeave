package com.devcollab.gateway.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "devcollab.security.jwt")
public record GatewayJwtProperties(
        String issuer,
        String audience,
        String secret
) {
    private static final String PLACEHOLDER_SECRET =
            "devcollab-local-development-secret-change-me";

    public GatewayJwtProperties {
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
    }
}
