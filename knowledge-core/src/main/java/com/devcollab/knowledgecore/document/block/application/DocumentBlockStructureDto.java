package com.devcollab.knowledgecore.document.block.application;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

public record DocumentBlockStructureDto(
        UUID blockId,
        String blockType,
        int sortOrder,
        long version,
        String plainText,
        String content,
        @JsonProperty("contentTruncated") boolean isContentTruncated
) {
}
