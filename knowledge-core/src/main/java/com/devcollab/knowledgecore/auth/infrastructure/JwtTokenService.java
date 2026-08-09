package com.devcollab.knowledgecore.auth.infrastructure;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Date;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Component
public class JwtTokenService {
    public static final String TOKEN_TYPE_ACCESS = "access";
    public static final String TOKEN_TYPE_AGENT_DELEGATION =
            "agent_delegation";

    private final JwtProperties properties;
    private final Algorithm algorithm;
    private final JWTVerifier verifier;

    public JwtTokenService(JwtProperties properties) {
        this.properties = properties;
        this.algorithm = Algorithm.HMAC256(properties.secret());
        this.verifier = JWT.require(algorithm)
                .withIssuer(properties.issuer())
                .withAudience(properties.audience())
                .build();
    }

    public String issueAccessToken(
            UUID userId,
            String username,
            UUID sessionId
    ) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.accessTokenTtl());

        return JWT.create()
                .withIssuer(properties.issuer())
                .withAudience(properties.audience())
                .withSubject(userId.toString())
                .withJWTId(UUID.randomUUID().toString())
                .withClaim("sid", sessionId.toString())
                .withClaim("username", username)
                .withClaim("token_type", TOKEN_TYPE_ACCESS)
                .withIssuedAt(Date.from(now))
                .withExpiresAt(Date.from(expiresAt))
                .sign(algorithm);
    }

    public String issueAgentDelegationToken(
            UUID userId,
            UUID delegationId,
            UUID workspaceId,
            UUID repositoryId,
            UUID jobId,
            String revision,
            List<String> allowedTools,
            Duration ttl
    ) {
        if (allowedTools == null
                || allowedTools.isEmpty()
                || allowedTools.stream().anyMatch(tool ->
                tool == null || tool.isBlank()
        )) {
            throw new IllegalArgumentException(
                    "Agent delegation token must include allowed_tools"
            );
        }
        Instant now = Instant.now();
        return JWT.create()
                .withIssuer(properties.issuer())
                .withAudience(properties.audience())
                .withSubject(userId.toString())
                .withJWTId(UUID.randomUUID().toString())
                .withClaim("sid", delegationId.toString())
                .withClaim("username", "agent-worker")
                .withClaim("token_type", TOKEN_TYPE_AGENT_DELEGATION)
                .withClaim("delegation_id", delegationId.toString())
                .withClaim("workspace_id", workspaceId.toString())
                .withClaim("repository_id", repositoryId.toString())
                .withClaim("job_id", jobId.toString())
                .withClaim("revision", revision)
                .withArrayClaim("allowed_tools", allowedTools.toArray(String[]::new))
                .withIssuedAt(Date.from(now))
                .withExpiresAt(Date.from(now.plus(ttl)))
                .sign(algorithm);
    }

    public TokenClaims verify(String token) {
        DecodedJWT jwt = verifier.verify(token);

        return new TokenClaims(
                UUID.fromString(jwt.getSubject()),
                UUID.fromString(jwt.getClaim("sid").asString()),
                jwt.getClaim("username").asString(),
                jwt.getId(),
                jwt.getExpiresAt().toInstant(),
                jwt.getClaim("token_type").asString(),
                optionalUuid(jwt, "delegation_id"),
                optionalUuid(jwt, "workspace_id"),
                optionalUuid(jwt, "repository_id"),
                optionalUuid(jwt, "job_id"),
                jwt.getClaim("revision").asString(),
                allowedTools(jwt)
        );
    }

    public long expiresInSeconds() {
        return properties.accessTokenTtl().toSeconds();
    }

    private static UUID optionalUuid(DecodedJWT jwt, String claimName) {
        String value = jwt.getClaim(claimName).asString();
        return value == null ? null : UUID.fromString(value);
    }

    private static List<String> allowedTools(DecodedJWT jwt) {
        List<String> tools = jwt.getClaim("allowed_tools").asList(String.class);
        return tools == null ? List.of() : List.copyOf(tools);
    }

    public record TokenClaims(
            UUID userId,
            UUID sessionId,
            String username,
            String tokenId,
            Instant expiresAt,
            String tokenType,
            UUID delegationId,
            UUID workspaceId,
            UUID repositoryId,
            UUID jobId,
            String revision,
            List<String> allowedTools
    ) {
    }
}
