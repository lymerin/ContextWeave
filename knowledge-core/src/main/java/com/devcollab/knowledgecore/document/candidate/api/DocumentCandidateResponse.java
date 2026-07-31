package com.devcollab.knowledgecore.document.candidate.api;

import com.devcollab.knowledgecore.document.candidate.application.DocumentCandidateResult;

import java.util.List;
import java.util.UUID;

public record DocumentCandidateResponse(
        UUID workspaceId,
        UUID repositoryId,
        String filePath,
        String query,
        List<DocumentCandidateItemResponse> candidates,
        boolean truncated,
        int omittedCandidateCount
) {
    public static DocumentCandidateResponse from(DocumentCandidateResult result) {
        return new DocumentCandidateResponse(
                result.workspaceId(), result.repositoryId(), result.filePath(), result.query(),
                result.candidates().stream().map(DocumentCandidateItemResponse::from).toList(),
                result.truncated(), result.omittedCandidateCount()
        );
    }
}
