package com.devcollab.knowledgecore.auth.infrastructure;

import com.auth0.jwt.exceptions.JWTVerificationException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtTokenServiceTests {

    private static final String TEST_SECRET =
            "devcollab-test-secret-with-at-least-32-characters";

    @Test
    void shouldIssueAndVerifyAccessToken() {
        JwtTokenService tokenService = tokenService(TEST_SECRET);
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        String token = tokenService.issueAccessToken(
                userId,
                "alice",
                sessionId
        );
        JwtTokenService.TokenClaims claims = tokenService.verify(token);

        assertEquals(userId, claims.userId());
        assertEquals(sessionId, claims.sessionId());
        assertEquals("alice", claims.username());
        assertTrue(claims.tokenId() != null && !claims.tokenId().isBlank());
        assertTrue(claims.expiresAt().isAfter(Instant.now()));
        assertEquals(JwtTokenService.TOKEN_TYPE_ACCESS, claims.tokenType());
        assertEquals(1800, tokenService.expiresInSeconds());
    }

    @Test
    void shouldRejectTokenSignedWithDifferentSecret() {
        JwtTokenService issuer = tokenService(TEST_SECRET);
        JwtTokenService verifier = tokenService(
                "another-test-secret-with-at-least-32-characters"
        );
        String token = issuer.issueAccessToken(
                UUID.randomUUID(),
                "alice",
                UUID.randomUUID()
        );

        assertThrows(
                JWTVerificationException.class,
                () -> verifier.verify(token)
        );
    }

    @Test
    void shouldRejectDefaultPlaceholderSecret() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new JwtProperties(
                        "devcollab-knowledge-core-test",
                        "devcollab-web-test",
                        "devcollab-local-development-secret-change-me",
                        Duration.ofMinutes(30)
                )
        );
    }

    private JwtTokenService tokenService(String secret) {
        JwtProperties properties = new JwtProperties(
                "devcollab-knowledge-core-test",
                "devcollab-web-test",
                secret,
                Duration.ofMinutes(30)
        );

        return new JwtTokenService(properties);
    }
}
