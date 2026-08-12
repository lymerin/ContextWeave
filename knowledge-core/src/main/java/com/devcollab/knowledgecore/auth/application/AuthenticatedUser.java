package com.devcollab.knowledgecore.auth.application;

import java.util.UUID;

public record AuthenticatedUser(
        UUID id,
        String username,
        String displayName,
        String accessToken,
        String tokenType,
        long expiresInSeconds,
        String refreshToken,
        String csrfToken
) {
}
