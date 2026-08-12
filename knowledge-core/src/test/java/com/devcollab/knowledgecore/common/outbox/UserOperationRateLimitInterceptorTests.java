package com.devcollab.knowledgecore.common.redis;

import com.devcollab.knowledgecore.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserOperationRateLimitInterceptorTests {

    private final RedisFixedWindowLimiter limiter =
            mock(RedisFixedWindowLimiter.class);
    private final RedisCoordinationProperties properties =
            new RedisCoordinationProperties(
                    null,
                    new RedisCoordinationProperties.RateLimit(
                            2, Duration.ofMinutes(1)
                    )
            );
    private final UserOperationRateLimitInterceptor interceptor =
            new UserOperationRateLimitInterceptor(limiter, properties);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatedMutationInsideLimitIsAllowed() {
        UUID userId = authenticate();
        HttpServletRequest request = request("PATCH", "/api/v1/documents/1");
        when(limiter.acquire(
                "rate-limit:" + userId, 2, Duration.ofMinutes(1)
        )).thenReturn(new RedisFixedWindowLimiter.LimitDecision(
                true, 20_000
        ));

        assertThat(interceptor.preHandle(
                request, mock(HttpServletResponse.class), new Object()
        )).isTrue();
    }

    @Test
    void authenticatedMutationAboveLimitThrowsRateLimitExceeded() {
        UUID userId = authenticate();
        HttpServletRequest request = request("POST", "/api/v1/documents");
        when(limiter.acquire(
                "rate-limit:" + userId, 2, Duration.ofMinutes(1)
        )).thenReturn(new RedisFixedWindowLimiter.LimitDecision(
                false, 20_000
        ));

        assertThatThrownBy(() -> interceptor.preHandle(
                request, mock(HttpServletResponse.class), new Object()
        )).isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void readRequestDoesNotConsumeQuota() {
        authenticate();
        assertThat(interceptor.preHandle(
                request("GET", "/api/v1/documents/1"),
                mock(HttpServletResponse.class),
                new Object()
        )).isTrue();
        verify(limiter, org.mockito.Mockito.never()).acquire(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    private UUID authenticate() {
        UUID userId = UUID.randomUUID();
        CurrentUser user = new CurrentUser(
                userId, UUID.randomUUID(), "alice"
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null)
        );
        return userId;
    }

    private HttpServletRequest request(String method, String uri) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getRequestURI()).thenReturn(uri);
        return request;
    }
}
