package com.devcollab.gateway.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayTokenServiceTests {

    private static final String SECRET =
            "devcollab-local-development-secret-change-me";

    private final GatewayTokenService tokenService =
            new GatewayTokenService(new GatewayJwtProperties(
                    "devcollab-knowledge-core",
                    "devcollab-web",
                    SECRET
            ));

    @Test
    void verifiesAccessTokenIssuedByKnowledgeCore() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        GatewayTokenService.GatewayUser user = tokenService.verify(token(
                userId,
                sessionId,
                "alice",
                "devcollab-knowledge-core",
                "devcollab-web"
        ));

        assertThat(user.userId()).isEqualTo(userId);
        assertThat(user.sessionId()).isEqualTo(sessionId);
        assertThat(user.username()).isEqualTo("alice");
    }

    @Test
    void rejectsTokenWithWrongAudience() {
        String token = token(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "alice",
                "devcollab-knowledge-core",
                "other-client"
        );

        assertThatThrownBy(() -> tokenService.verify(token))
                .isInstanceOf(JWTVerificationException.class);
    }

    private String token(
            UUID userId,
            UUID sessionId,
            String username,
            String issuer,
            String audience
    ) {
        Instant now = Instant.now();
        return JWT.create()
                .withIssuer(issuer)
                .withAudience(audience)
                .withSubject(userId.toString())
                .withJWTId(UUID.randomUUID().toString())
                .withClaim("sid", sessionId.toString())
                .withClaim("username", username)
                .withIssuedAt(Date.from(now))
                .withExpiresAt(Date.from(now.plusSeconds(1800)))
                .sign(Algorithm.HMAC256(SECRET));
    }
}
