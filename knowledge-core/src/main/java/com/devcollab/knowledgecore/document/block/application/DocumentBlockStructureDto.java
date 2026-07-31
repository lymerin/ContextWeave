package com.devcollab.knowledgecore.document.block.application;

import java.util.UUID;

public record DocumentBlockStructureDto(
        UUID blockId,
        String blockType,
        int sortOrder,
        long version,
        String plainText,
        String content,
        boolean isContentTruncated
) {
}
