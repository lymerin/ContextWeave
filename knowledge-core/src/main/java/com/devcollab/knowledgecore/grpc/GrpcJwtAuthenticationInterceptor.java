package com.devcollab.knowledgecore.grpc;

import com.devcollab.knowledgecore.auth.infrastructure.JwtTokenService;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import org.springframework.stereotype.Component;

@Component
public class GrpcJwtAuthenticationInterceptor implements ServerInterceptor {

    public static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of(
                    "authorization",
                    Metadata.ASCII_STRING_MARSHALLER
            );

    private final JwtTokenService tokenService;

    public GrpcJwtAuthenticationInterceptor(JwtTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next
    ) {
        String authorization = headers.get(AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            call.close(
                    Status.UNAUTHENTICATED.withDescription(
                            "Bearer access token is required"
                    ),
                    new Metadata()
            );
            return new ServerCall.Listener<>() {
            };
        }

        try {
            var claims = tokenService.verify(authorization.substring(7));
            Context context = Context.current().withValue(
                    GrpcAuthenticationContext.TOKEN_CLAIMS,
                    claims
            );
            return Contexts.interceptCall(context, call, headers, next);
        } catch (RuntimeException exception) {
            call.close(
                    Status.UNAUTHENTICATED.withDescription(
                            "Access token is invalid or expired"
                    ),
                    new Metadata()
            );
            return new ServerCall.Listener<>() {
            };
        }
    }
}
