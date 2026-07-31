package com.devcollab.knowledgecore.document.block.api;

import com.devcollab.knowledgecore.document.block.application.DocumentBlockStructureDto;

import java.util.UUID;

public record DocumentBlockStructureResponse(
        UUID blockId,
        String blockType,
        int sortOrder,
        long version,
        String plainText,
        String content,
        boolean contentTruncated
) {
    public static DocumentBlockStructureResponse from(DocumentBlockStructureDto dto) {
        return new DocumentBlockStructureResponse(
                dto.blockId(),
                dto.blockType(),
                dto.sortOrder(),
                dto.version(),
                dto.plainText(),
                dto.content(),
                dto.isContentTruncated()
        );
    }
}
