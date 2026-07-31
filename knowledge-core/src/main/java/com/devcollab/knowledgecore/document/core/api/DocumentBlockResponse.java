package com.devcollab.knowledgecore.document.core.api;

import com.devcollab.knowledgecore.document.core.domain.DocumentBlock;
import com.devcollab.knowledgecore.document.core.domain.DocumentBlockType;
import com.devcollab.knowledgecore.document.block.application.DocumentBlockContentCodec;
import com.devcollab.knowledgecore.document.block.api.DocumentBlockContentResponse;

import java.time.Instant;
import java.util.UUID;

public record DocumentBlockResponse(
        UUID id,
        UUID documentId,
        DocumentBlockType type,
        DocumentBlockContentResponse content,
        int sortOrder,
        long version,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt
) {
    public static DocumentBlockResponse from(
            DocumentBlock block,
            DocumentBlockContentCodec contentCodec
    ) {
        return new DocumentBlockResponse(
                block.id(),
                block.documentId(),
                block.type(),
                new DocumentBlockContentResponse(
                        block.text(),
                        contentCodec.schemaVersion(block),
                        contentCodec.document(block)
                ),
                block.sortOrder(),
                block.version(),
                block.createdBy(),
                block.createdAt(),
                block.updatedAt()
        );
    }
}
