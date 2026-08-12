package com.devcollab.knowledgecore.common.redis;

import com.devcollab.knowledgecore.common.redis.RedisFixedWindowLimiter.LimitDecision;
import com.devcollab.knowledgecore.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class UserOperationRateLimitInterceptor implements HandlerInterceptor {

    private final RedisFixedWindowLimiter limiter;
    private final RedisCoordinationProperties properties;

    public UserOperationRateLimitInterceptor(
            RedisFixedWindowLimiter limiter,
            RedisCoordinationProperties properties
    ) {
        this.limiter = limiter;
        this.properties = properties;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ) {
        if (!isMutation(request.getMethod())
                || request.getRequestURI().startsWith("/api/v1/auth/")) {
            return true;
        }

        Authentication authentication = SecurityContextHolder.getContext()
                .getAuthentication();
        if (authentication == null
                || !(authentication.getPrincipal() instanceof CurrentUser user)) {
            return true;
        }

        RedisCoordinationProperties.RateLimit policy = properties.rateLimit();
        LimitDecision decision = limiter.acquire(
                "rate-limit:" + user.userId(),
                policy.maxOperations(),
                policy.window()
        );
        if (!decision.allowed()) {
            throw new RateLimitExceededException(
                    "操作过于频繁，请稍后重试",
                    decision.retryAfterMillis()
            );
        }
        return true;
    }

    private boolean isMutation(String method) {
        return "POST".equals(method)
                || "PUT".equals(method)
                || "PATCH".equals(method)
                || "DELETE".equals(method);
    }
}
