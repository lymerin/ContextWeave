package com.devcollab.knowledgecore.git.domain;

import java.time.Instant;
import java.util.UUID;

public record CodeDocumentBinding(
        UUID id,
        UUID workspaceId,
        UUID repositoryId,
        UUID documentId,
        UUID blockId,
        String targetKey,
        String pathPattern,
        String revision,
        CodeAnchorKind anchorKind,
        String symbolKey,
        Integer startLine,
        Integer endLine,
        BindingRole bindingRole,
        int bindingOrdinal,
        UUID createdBy,
        Instant createdAt,
        String boundSignature
) {
    public CodeDocumentBinding(
            UUID id, UUID workspaceId, UUID repositoryId, UUID documentId,
            UUID blockId, String targetKey, String pathPattern, String revision,
            CodeAnchorKind anchorKind, String symbolKey, Integer startLine,
            Integer endLine, UUID createdBy, Instant createdAt
    ) {
        this(id, workspaceId, repositoryId, documentId, blockId, targetKey,
                pathPattern, revision, anchorKind, symbolKey, startLine, endLine,
                BindingRole.PRIMARY, 1, createdBy, createdAt, null);
    }

    public CodeDocumentBinding(
            UUID id,
            UUID workspaceId,
            UUID repositoryId,
            UUID documentId,
            UUID blockId,
            String pathPattern,
            UUID createdBy,
            Instant createdAt
    ) {
        this(
                id,
                workspaceId,
                repositoryId,
                documentId,
                blockId,
                blockId == null ? "DOCUMENT" : blockId.toString(),
                pathPattern,
                null,
                CodeAnchorKind.FILE,
                null,
                null,
                null,
                BindingRole.PRIMARY,
                1,
                createdBy,
                createdAt,
                null
        );
    }
}
