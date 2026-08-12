package com.devcollab.knowledgecore.security;

import com.devcollab.knowledgecore.agentdelegation.AgentDelegationException;
import com.devcollab.knowledgecore.auth.infrastructure.JwtTokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

@Component
public class DelegatedRestAccessInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ) {
        Object rawClaims = request.getAttribute(
                JwtAuthenticationFilter.TOKEN_CLAIMS_ATTRIBUTE
        );
        if (!(rawClaims instanceof JwtTokenService.TokenClaims claims)
                || !JwtTokenService.TOKEN_TYPE_AGENT_DELEGATION.equals(
                claims.tokenType())) {
            return true;
        }
        if (!(handler instanceof HandlerMethod method)) {
            denyAccess();
            return false;
        }
        DelegatedRestAccess access =
                method.getMethodAnnotation(DelegatedRestAccess.class);
        if (access == null) {
            throw new AgentDelegationException(
                    HttpStatus.UNAUTHORIZED,
                    "AUTH_ACCESS_INVALID",
                    "Agent delegation token is not valid for this REST endpoint"
            );
        }
        if (Arrays.stream(access.tools())
                .noneMatch(claims.allowedTools()::contains)) {
            denyAccess();
        }
        Map<String, String> variables = pathVariables(request);
        requireMatch(variables.get(access.workspace()), claims.workspaceId());
        if (!access.repository().isBlank()) {
            String repository = variables.get(access.repository());
            if (repository == null) {
                repository = request.getParameter(access.repository());
            }
            if (repository != null) {
                requireMatch(repository, claims.repositoryId());
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> pathVariables(HttpServletRequest request) {
        Object value = request.getAttribute(
                HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE
        );
        return value instanceof Map<?, ?> map
                ? (Map<String, String>) map
                : Map.of();
    }

    private void requireMatch(String actual, UUID allowed) {
        if (actual == null || allowed == null
                || !allowed.toString().equalsIgnoreCase(actual)) {
            denyAccess();
        }
    }

    private void denyAccess() {
        throw new AgentDelegationException(
                HttpStatus.FORBIDDEN,
                "AGENT_DELEGATION_DENIED",
                "Agent delegation token is outside its allowed REST scope"
        );
    }
}
