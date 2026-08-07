import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import java.nio.file.Files;
import java.nio.file.Paths;

public class Auth0Probe {
    public static void main(String[] args) throws Exception {
        String token = Files.readString(Paths.get(args[0])).trim();
        String secret = "devcollab-local-development-secret-change-me";
        JWTVerifier verifier = JWT.require(Algorithm.HMAC256(secret))
                .withIssuer("devcollab-knowledge-core")
                .withAudience("devcollab-web")
                .build();
        try {
            var jwt = verifier.verify(token);
            System.out.println("AUTH0 VERIFY: PASS");
            System.out.println("sid=" + jwt.getClaim("sid").asString());
            System.out.println("username=" + jwt.getClaim("username").asString());
            System.out.println("token_type=" + jwt.getClaim("token_type").asString());
        } catch (JWTVerificationException | IllegalArgumentException e) {
            System.out.println("AUTH0 VERIFY FAIL: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }
}
