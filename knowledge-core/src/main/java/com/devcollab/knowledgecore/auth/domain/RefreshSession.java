package com.devcollab.knowledgecore.auth.domain;

import java.time.Instant;
import java.util.UUID;

public record RefreshSession(
        UUID id,
        UUID userId,
        String refreshTokenHash,
        String csrfTokenHash,
        Instant createdAt,
        Instant expiresAt
) {
}
