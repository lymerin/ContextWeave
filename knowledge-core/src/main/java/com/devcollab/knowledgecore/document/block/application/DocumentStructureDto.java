package com.devcollab.knowledgecore.document.block.application;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DocumentStructureDto(
        UUID documentId,
        UUID workspaceId,
        String title,
        String documentType,
        String reviewStatus,
        Instant updatedAt,
        List<DocumentBlockStructureDto> blocks,
        @JsonProperty("truncated") boolean isTruncated,
        int omittedBlockCount,
        int omittedCharacterCount
) {
}
