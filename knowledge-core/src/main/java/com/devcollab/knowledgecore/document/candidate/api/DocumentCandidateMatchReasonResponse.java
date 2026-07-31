package com.devcollab.knowledgecore.document.candidate.api;

import com.devcollab.knowledgecore.document.candidate.application.DocumentCandidateMatchReason;

import java.util.List;
import java.util.UUID;

public record DocumentCandidateMatchReasonResponse(
        String code,
        int weight,
        String matchedTerm,
        List<UUID> matchedBlockIds
) {
    static DocumentCandidateMatchReasonResponse from(DocumentCandidateMatchReason reason) {
        return new DocumentCandidateMatchReasonResponse(
                reason.code(), reason.weight(), reason.matchedTerm(), reason.matchedBlockIds()
        );
    }
}
