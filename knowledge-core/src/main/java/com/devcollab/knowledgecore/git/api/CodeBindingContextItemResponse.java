package com.devcollab.knowledgecore.git.api;

import com.devcollab.knowledgecore.git.application.CodeBindingContextItem;
import com.devcollab.knowledgecore.git.domain.CodeAnchorKind;
import com.devcollab.knowledgecore.git.domain.BindingRole;

import java.util.List;
import java.util.UUID;

public record CodeBindingContextItemResponse(
        UUID bindingId,
        UUID workspaceId,
        UUID repositoryId,
        String revision,
        CodeAnchorKind anchorKind,
        String symbolKey,
        Integer startLine,
        Integer endLine,
        BindingRole bindingRole,
        int bindingOrdinal,
        UUID documentId,
        UUID blockId,
        String targetKey,
        String pathPattern,
        String documentTitle,
        List<String> matchingFilePaths,
        boolean blockExists
) {
    public static CodeBindingContextItemResponse from(CodeBindingContextItem item) {
        return new CodeBindingContextItemResponse(
                item.bindingId(),
                item.workspaceId(),
                item.repositoryId(),
                item.revision(),
                item.anchorKind(),
                item.symbolKey(),
                item.startLine(),
                item.endLine(),
                item.bindingRole(),
                item.bindingOrdinal(),
                item.documentId(),
                item.blockId(),
                item.targetKey(),
                item.pathPattern(),
                item.documentTitle(),
                item.matchingFilePaths(),
                item.blockExists()
        );
    }
}
