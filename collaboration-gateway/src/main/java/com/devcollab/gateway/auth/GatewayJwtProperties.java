package com.devcollab.gateway.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "devcollab.security.jwt")
public record GatewayJwtProperties(
        String issuer,
        String audience,
        String secret
) {

    public GatewayJwtProperties {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("JWT issuer 不能为空");
        }
        if (audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("JWT audience 不能为空");
        }
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException("JWT secret 长度不能少于 32 个字符");
        }
    }
}
