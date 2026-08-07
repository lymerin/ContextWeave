import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class ResolverProbe {
    public static void main(String[] args) throws Exception {
        String token = Files.readString(Paths.get(args[0])).trim();
        String secret = "devcollab-local-development-secret-change-me";
        JWTVerifier verifier = JWT.require(Algorithm.HMAC256(secret))
                .withIssuer("devcollab-knowledge-core")
                .withAudience("devcollab-web")
                .build();
        try {
            DecodedJWT jwt = verifier.verify(token);
            String sessionId = jwt.getClaim("sid").asString();
            String username = jwt.getClaim("username").asString();
            if (sessionId == null || username == null || username.isBlank()) {
                throw new IllegalArgumentException("missing identity claims");
            }
            String tokenType = jwt.getClaim("token_type").asString();
            boolean delegated = "agent_delegation".equals(tokenType);
            String[] tools = delegated ? jwt.getClaim("allowed_tools").asArray(String.class) : null;
            System.out.println("sessionId=" + sessionId);
            System.out.println("username=" + username);
            System.out.println("delegated=" + delegated + " tools=" + (tools == null ? "null" : tools.length));
            UUID.fromString(jwt.getSubject());
            UUID.fromString(sessionId);
            System.out.println("workspace_id=" + requiredClaim(jwt, "workspace_id"));
            System.out.println("repository_id=" + requiredClaim(jwt, "repository_id"));
            System.out.println("job_id=" + requiredClaim(jwt, "job_id"));
            System.out.println("revision=" + requiredClaim(jwt, "revision"));
            var toolSet = tools == null ? Set.of() : Set.copyOf(Arrays.asList(tools));
            System.out.println("toolSet=" + toolSet.size());
            System.out.println("RESOLVER SIMULATION: PASS");
        } catch (Exception e) {
            System.out.println("RESOLVER SIMULATION FAIL: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }
    private static String requiredClaim(DecodedJWT jwt, String name) {
        String value = jwt.getClaim(name).asString();
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing " + name);
        }
        return value;
    }
}
