package com.devcollab.knowledgecore.document.candidate.application;

import java.util.List;
import java.util.UUID;

public record DocumentCandidateMatchReason(
        String code,
        int weight,
        String matchedTerm,
        List<UUID> matchedBlockIds
) {
}
