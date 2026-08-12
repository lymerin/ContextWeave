package com.devcollab.knowledgecore.auth.application;

import com.devcollab.knowledgecore.auth.domain.UserAccount;
import com.devcollab.knowledgecore.auth.domain.UserStatus;
import com.devcollab.knowledgecore.auth.infrastructure.JdbcUserRepository;
import com.devcollab.knowledgecore.auth.infrastructure.JwtTokenService;
import com.devcollab.knowledgecore.auth.infrastructure.RefreshTokenService;
import com.devcollab.knowledgecore.auth.infrastructure.RefreshTokenService.RefreshCredentials;
import com.devcollab.knowledgecore.common.redis.RedisCoordinationProperties;
import com.devcollab.knowledgecore.common.redis.RedisFixedWindowLimiter;
import com.devcollab.knowledgecore.common.redis.RedisFixedWindowLimiter.LimitDecision;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthenticationApplicationServiceTests {

    @Test
    void loginIdentityIsNormalizedAndHashedBeforeEnteringRedisKey() {
        RedisFixedWindowLimiter redisLimiter = mock(RedisFixedWindowLimiter.class);
        RedisCoordinationProperties properties = new RedisCoordinationProperties(
                new RedisCoordinationProperties.LoginLimit(
                        5, Duration.ofMinutes(15)
                ),
                new RedisCoordinationProperties.RateLimit(
                        120, Duration.ofMinutes(1)
                )
        );
        JdbcUserRepository userRepository = mock(JdbcUserRepository.class);
        UserAccount user = new UserAccount(
                UUID.randomUUID(), "Alice", "alice", "Alice",
                "hash", UserStatus.ACTIVE, Instant.now(), Instant.now()
        );
        when(userRepository.findByNormalizedUsername("alice"))
                .thenReturn(Optional.of(user));
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        when(passwordEncoder.matches("secret", "hash")).thenReturn(true);
        RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
        when(refreshTokenService.issue(user.id()))
                .thenReturn(new RefreshCredentials(
                        UUID.randomUUID(), user.id(), "rt", "ct"
                ));
        when(redisLimiter.acquire(
                startsWith("login-limit:"),
                eq(5),
                eq(Duration.ofMinutes(15))
        )).thenReturn(new LimitDecision(true, 900_000));

        AuthenticationApplicationService service = new AuthenticationApplicationService(
                userRepository,
                passwordEncoder,
                mock(JwtTokenService.class),
                refreshTokenService,
                redisLimiter,
                properties
        );

        service.login(" Alice ", "secret");

        var keyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(redisLimiter).acquire(
                keyCaptor.capture(), eq(5), eq(Duration.ofMinutes(15))
        );
        assertThat(keyCaptor.getValue())
                .startsWith("login-limit:")
                .doesNotContain("alice");
        verify(redisLimiter).reset(keyCaptor.getValue());
    }
}
