package com.devcollab.knowledgecore.auth.application;

import com.devcollab.knowledgecore.auth.application.exception.InvalidCredentialsException;
import com.devcollab.knowledgecore.auth.application.exception.InvalidRefreshTokenException;
import com.devcollab.knowledgecore.auth.application.exception.UsernameAlreadyExistsException;
import com.devcollab.knowledgecore.auth.domain.UserAccount;
import com.devcollab.knowledgecore.auth.domain.UserStatus;
import com.devcollab.knowledgecore.auth.infrastructure.JdbcUserRepository;
import com.devcollab.knowledgecore.auth.infrastructure.JwtTokenService;
import com.devcollab.knowledgecore.auth.infrastructure.RefreshTokenService;
import com.devcollab.knowledgecore.auth.infrastructure.RefreshTokenService.RefreshCredentials;
import com.devcollab.knowledgecore.common.redis.RateLimitExceededException;
import com.devcollab.knowledgecore.common.redis.RedisCoordinationProperties;
import com.devcollab.knowledgecore.common.redis.RedisFixedWindowLimiter;
import com.devcollab.knowledgecore.common.redis.RedisFixedWindowLimiter.LimitDecision;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

@Service
public class AuthenticationApplicationService {

    private final JdbcUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final RefreshTokenService refreshTokenService;
    private final RedisFixedWindowLimiter loginLimiter;
    private final RedisCoordinationProperties properties;

    public AuthenticationApplicationService(
            JdbcUserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenService jwtTokenService,
            RefreshTokenService refreshTokenService,
            RedisFixedWindowLimiter loginLimiter,
            RedisCoordinationProperties properties
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.refreshTokenService = refreshTokenService;
        this.loginLimiter = loginLimiter;
        this.properties = properties;
    }

    @Transactional
    public AuthenticatedUser register(
            String username,
            String displayName,
            String password
    ) {
        String normalizedUsername = normalizeUsername(username);

        if (userRepository.existsByNormalizedUsername(normalizedUsername)) {
            throw new UsernameAlreadyExistsException();
        }

        Instant now = Instant.now();

        UserAccount userAccount = new UserAccount(
                UUID.randomUUID(),
                username.trim(),
                normalizedUsername,
                displayName.trim(),
                passwordEncoder.encode(password),
                UserStatus.ACTIVE,
                now,
                now
        );

        return createAuthenticatedUser(userRepository.save(userAccount));
    }

    public AuthenticatedUser login(String username, String password) {
        String normalizedUsername = normalizeUsername(username);

        var limitDecision = acquire(normalizedUsername);
        if (!limitDecision.allowed()) {
            throw new RateLimitExceededException(
                    "登录尝试过于频繁，请稍后重试",
                    limitDecision.retryAfterMillis()
            );
        }

        UserAccount userAccount = userRepository
                .findByNormalizedUsername(normalizedUsername)
                .orElseThrow(this::invalidCredentials);

        if (!userAccount.canLogin()
                || !passwordEncoder.matches(
                        password,
                        userAccount.passwordHash()
                )) {
            throw invalidCredentials();
        }

        reset(normalizedUsername);
        return createAuthenticatedUser(userAccount);
    }

    @Transactional
    public AuthenticatedUser refresh(
            String refreshToken,
            String csrfToken
    ) {
        RefreshCredentials credentials = refreshTokenService.rotate(
                refreshToken,
                csrfToken
        );

        UserAccount userAccount = userRepository
                .findById(credentials.userId())
                .filter(UserAccount::canLogin)
                .orElseThrow(() -> {
                    refreshTokenService.revoke(credentials.refreshToken());
                    return new InvalidRefreshTokenException();
                });

        return createAuthenticatedUser(userAccount, credentials);
    }

    public void logout(String refreshToken, String csrfToken) {
        refreshTokenService.revoke(refreshToken, csrfToken);
    }

    private AuthenticatedUser createAuthenticatedUser(
            UserAccount userAccount
    ) {
        return createAuthenticatedUser(
                userAccount,
                refreshTokenService.issue(userAccount.id())
        );
    }

    private AuthenticatedUser createAuthenticatedUser(
            UserAccount userAccount,
            RefreshCredentials credentials
    ) {
        String accessToken = jwtTokenService.issueAccessToken(
                userAccount.id(),
                userAccount.username(),
                credentials.sessionId()
        );

        return new AuthenticatedUser(
                userAccount.id(),
                userAccount.username(),
                userAccount.displayName(),
                accessToken,
                "Bearer",
                jwtTokenService.expiresInSeconds(),
                credentials.refreshToken(),
                credentials.csrfToken()
        );
    }

    public static String normalizeUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("用户名不能为空");
        }

        return username.trim().toLowerCase(Locale.ROOT);
    }

    private LimitDecision acquire(String identity) {
        RedisCoordinationProperties.LoginLimit policy = properties.loginLimit();
        return loginLimiter.acquire(
                key(identity),
                policy.maxAttempts(),
                policy.window()
        );
    }

    private void reset(String identity) {
        loginLimiter.reset(key(identity));
    }

    private String key(String identity) {
        String normalized = identity == null
                ? ""
                : identity.trim().toLowerCase(Locale.ROOT);
        return "login-limit:" + sha256(normalized);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private InvalidCredentialsException invalidCredentials() {
        return new InvalidCredentialsException();
    }
}
