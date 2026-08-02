package com.devcollab.knowledgecore.git.application;

import com.devcollab.knowledgecore.git.domain.CodeAnchorKind;
import com.devcollab.knowledgecore.git.domain.BindingRole;

import java.util.List;
import java.util.UUID;

public record CodeBindingContextItem(
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
}
