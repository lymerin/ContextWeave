package com.devcollab.knowledgecore.auth.infrastructure;

import com.devcollab.knowledgecore.auth.application.exception.InvalidCsrfTokenException;
import com.devcollab.knowledgecore.auth.application.exception.InvalidRefreshTokenException;
import com.devcollab.knowledgecore.auth.domain.RefreshSession;
import com.devcollab.knowledgecore.auth.infrastructure.RefreshTokenService.RefreshCredentials;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class RefreshTokenServiceTests {

    @Test
    void shouldRotateRefreshTokenAndRejectOldToken() {
        RefreshTokenService service = refreshTokenService();
        UUID userId = UUID.randomUUID();
        RefreshCredentials original = service.issue(userId);

        RefreshCredentials rotated = service.rotate(
                original.refreshToken(),
                original.csrfToken()
        );

        assertEquals(userId, rotated.userId());
        assertNotEquals(original.sessionId(), rotated.sessionId());
        assertNotEquals(original.refreshToken(), rotated.refreshToken());
        assertNotEquals(original.csrfToken(), rotated.csrfToken());
        assertThrows(
                InvalidRefreshTokenException.class,
                () -> service.rotate(
                        original.refreshToken(),
                        original.csrfToken()
                )
        );
    }

    @Test
    void shouldRejectWrongCsrfTokenWithoutConsumingSession() {
        RefreshTokenService service = refreshTokenService();
        RefreshCredentials credentials = service.issue(UUID.randomUUID());

        assertThrows(
                InvalidCsrfTokenException.class,
                () -> service.rotate(
                        credentials.refreshToken(),
                        "wrong-csrf-token"
                )
        );

        service.rotate(
                credentials.refreshToken(),
                credentials.csrfToken()
        );
    }

    private RefreshTokenService refreshTokenService() {
        RefreshTokenProperties properties = new RefreshTokenProperties(
                Duration.ofDays(7),
                "dc_refresh",
                "dc_csrf",
                "X-CSRF-Token",
                List.of("http://localhost:5173"),
                false
        );

        return new RefreshTokenService(
                new InMemoryRefreshSessionRepository(),
                properties
        );
    }

    /**
     * Test double for {@link JdbcRefreshSessionRepository}; previously lived in
     * main sources under the unused {@code in-memory} profile.
     */
    private static final class InMemoryRefreshSessionRepository
            extends JdbcRefreshSessionRepository {

        private final Map<String, RefreshSession> sessionsByTokenHash =
                new HashMap<>();

        private InMemoryRefreshSessionRepository() {
            super(mock(JdbcTemplate.class));
        }

        @Override
        public synchronized RefreshSession save(RefreshSession session) {
            sessionsByTokenHash.put(session.refreshTokenHash(), session);
            return session;
        }

        @Override
        public synchronized Optional<RefreshSession> findActiveByTokenHash(
                String refreshTokenHash,
                Instant now
        ) {
            RefreshSession session = sessionsByTokenHash.get(refreshTokenHash);
            if (session == null) {
                return Optional.empty();
            }
            return Optional.of(session);
        }

        @Override
        public synchronized boolean consume(
                String refreshTokenHash,
                UUID sessionId
        ) {
            RefreshSession session = sessionsByTokenHash.get(refreshTokenHash);
            if (session == null || !session.id().equals(sessionId)) {
                return false;
            }
            sessionsByTokenHash.remove(refreshTokenHash);
            return true;
        }

        @Override
        public synchronized void revoke(String refreshTokenHash) {
            sessionsByTokenHash.remove(refreshTokenHash);
        }
    }
}
