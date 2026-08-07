package com.devcollab.knowledgecore.agentdelegation;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "devcollab.agent-delegation")
public record AgentDelegationProperties(
        String serviceToken,
        Duration delegationTtl,
        Duration accessTokenTtl
) {
    private static final String PLACEHOLDER_SERVICE_TOKEN =
            "devcollab-local-agent-service-token-change-me";

    public AgentDelegationProperties {
        if (serviceToken == null || serviceToken.isBlank() || serviceToken.length() < 32) {
            throw new IllegalArgumentException(
                    "Agent delegation service token must contain at least 32 characters"
            );
        }
        if (PLACEHOLDER_SERVICE_TOKEN.equals(serviceToken)) {
            throw new IllegalArgumentException(
                    "Agent delegation service token must not be the default placeholder; "
                            + "set DEVCOLLAB_AGENT_INTERNAL_TOKEN explicitly"
            );
        }
        if (delegationTtl == null || delegationTtl.isNegative() || delegationTtl.isZero()) {
            throw new IllegalArgumentException("Agent delegation TTL must be positive");
        }
        if (accessTokenTtl == null || accessTokenTtl.isNegative() || accessTokenTtl.isZero()) {
            throw new IllegalArgumentException("Agent access token TTL must be positive");
        }
    }

    @Override
    public String toString() {
        return "AgentDelegationProperties[serviceToken=***, delegationTtl=" + delegationTtl
                + ", accessTokenTtl=" + accessTokenTtl + "]";
    }
}
