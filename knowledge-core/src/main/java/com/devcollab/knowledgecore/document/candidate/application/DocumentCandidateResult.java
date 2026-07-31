package com.devcollab.knowledgecore.document.candidate.application;

import java.util.List;
import java.util.UUID;

public record DocumentCandidateResult(
        UUID workspaceId,
        UUID repositoryId,
        String filePath,
        String query,
        List<DocumentCandidateItem> candidates,
        boolean truncated,
        int omittedCandidateCount
) {
}
