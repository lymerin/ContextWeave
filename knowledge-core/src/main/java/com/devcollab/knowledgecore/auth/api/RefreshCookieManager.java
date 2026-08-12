package com.devcollab.knowledgecore.auth.api;

import com.devcollab.knowledgecore.auth.application.exception.InvalidCsrfTokenException;
import com.devcollab.knowledgecore.auth.application.exception.InvalidRefreshTokenException;
import com.devcollab.knowledgecore.auth.infrastructure.RefreshTokenProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

@Component
public class RefreshCookieManager {

    private static final String SAME_SITE = "Strict";
    private static final String AUTH_COOKIE_PATH = "/api/v1/auth";
    private static final String CSRF_COOKIE_PATH = "/";

    private final RefreshTokenProperties properties;

    public RefreshCookieManager(RefreshTokenProperties properties) {
        this.properties = properties;
    }

    public RequestCredentials readAndValidate(HttpServletRequest request) {
        String refreshToken = cookieValue(
                request,
                properties.refreshCookieName()
        );

        if (refreshToken == null || refreshToken.isBlank()) {
            throw new InvalidRefreshTokenException();
        }

        return validateCsrf(request, refreshToken);
    }

    public Optional<RequestCredentials> readForLogout(
            HttpServletRequest request
    ) {
        String refreshToken = cookieValue(
                request,
                properties.refreshCookieName()
        );

        if (refreshToken == null || refreshToken.isBlank()) {
            return Optional.empty();
        }

        return Optional.of(validateCsrf(request, refreshToken));
    }

    public void write(
            HttpServletResponse response,
            String refreshToken,
            String csrfToken
    ) {
        addCookie(
                response,
                properties.refreshCookieName(),
                refreshToken,
                true,
                AUTH_COOKIE_PATH,
                properties.ttl()
        );
        addCookie(
                response,
                properties.csrfCookieName(),
                csrfToken,
                false,
                CSRF_COOKIE_PATH,
                properties.ttl()
        );
    }

    public void clear(HttpServletResponse response) {
        addCookie(
                response,
                properties.refreshCookieName(),
                "",
                true,
                AUTH_COOKIE_PATH,
                Duration.ZERO
        );
        addCookie(
                response,
                properties.csrfCookieName(),
                "",
                false,
                CSRF_COOKIE_PATH,
                Duration.ZERO
        );
    }

    private String cookieValue(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }

        return Arrays.stream(cookies)
                .filter(cookie -> name.equals(cookie.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }

    private RequestCredentials validateCsrf(
            HttpServletRequest request,
            String refreshToken
    ) {
        String csrfCookie = cookieValue(
                request,
                properties.csrfCookieName()
        );
        String csrfHeader = request.getHeader(properties.csrfHeaderName());
        String origin = request.getHeader(HttpHeaders.ORIGIN);

        if (!properties.allowedOrigins().contains(origin)
                || csrfCookie == null
                || csrfHeader == null
                || !MessageDigest.isEqual(
                        csrfCookie.getBytes(StandardCharsets.UTF_8),
                        csrfHeader.getBytes(StandardCharsets.UTF_8)
                )) {
            throw new InvalidCsrfTokenException();
        }

        return new RequestCredentials(refreshToken, csrfHeader);
    }

    private void addCookie(
            HttpServletResponse response,
            String name,
            String value,
            boolean httpOnly,
            String path,
            Duration maxAge
    ) {
        ResponseCookie cookie = ResponseCookie.from(name, value)
                .httpOnly(httpOnly)
                .secure(properties.secure())
                .sameSite(SAME_SITE)
                .path(path)
                .maxAge(maxAge)
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public record RequestCredentials(
            String refreshToken,
            String csrfToken
    ) {
    }
}
