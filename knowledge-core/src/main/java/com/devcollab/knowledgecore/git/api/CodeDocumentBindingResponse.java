package com.devcollab.knowledgecore.git.api;

import com.devcollab.knowledgecore.git.domain.CodeDocumentBinding;
import com.devcollab.knowledgecore.git.domain.CodeAnchorKind;
import com.devcollab.knowledgecore.git.domain.BindingRole;

import java.time.Instant;
import java.util.UUID;

public record CodeDocumentBindingResponse(
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
        Instant createdAt
) {
    public static CodeDocumentBindingResponse from(CodeDocumentBinding binding) {
        return new CodeDocumentBindingResponse(
                binding.id(), binding.workspaceId(), binding.repositoryId(),
                binding.documentId(), binding.blockId(), binding.targetKey(),
                binding.pathPattern(), binding.revision(), binding.anchorKind(),
                binding.symbolKey(), binding.startLine(), binding.endLine(),
                binding.bindingRole(), binding.bindingOrdinal(),
                binding.createdBy(),
                binding.createdAt()
        );
    }
}
