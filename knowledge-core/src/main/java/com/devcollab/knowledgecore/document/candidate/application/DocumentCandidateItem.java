package com.devcollab.knowledgecore.document.candidate.application;

import java.util.List;
import java.util.UUID;

public record DocumentCandidateItem(
        UUID documentId,
        String title,
        int score,
        List<DocumentCandidateMatchReason> matchReasons,
        List<UUID> matchedBlockIds,
        int existingBindingCount
) {
}
