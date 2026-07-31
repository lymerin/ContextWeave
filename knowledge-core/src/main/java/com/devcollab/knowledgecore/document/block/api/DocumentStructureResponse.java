package com.devcollab.knowledgecore.document.block.api;

import com.devcollab.knowledgecore.document.block.application.DocumentStructureDto;
import com.devcollab.knowledgecore.document.block.application.DocumentBlockStructureDto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DocumentStructureResponse(
        UUID documentId,
        UUID workspaceId,
        String title,
        String documentType,
        String reviewStatus,
        Instant updatedAt,
        List<DocumentBlockStructureResponse> blocks,
        boolean truncated,
        int omittedBlockCount,
        int omittedCharacterCount
) {
    public static DocumentStructureResponse from(DocumentStructureDto dto) {
        return new DocumentStructureResponse(
                dto.documentId(),
                dto.workspaceId(),
                dto.title(),
                dto.documentType(),
                dto.reviewStatus(),
                dto.updatedAt(),
                dto.blocks().stream().map(DocumentBlockStructureResponse::from).toList(),
                dto.isTruncated(),
                dto.omittedBlockCount(),
                dto.omittedCharacterCount()
        );
    }
}
