package com.devcollab.knowledgecore.git.application;

import com.devcollab.knowledgecore.git.domain.CodeAnchorKind;
import com.devcollab.knowledgecore.git.domain.BindingRole;

import java.util.UUID;

public record CreateCodeBindingCommand(
        UUID repositoryId,
        UUID blockId,
        String pathPattern,
        String revision,
        CodeAnchorKind anchorKind,
        String symbolKey,
        Integer startLine,
        Integer endLine,
        BindingRole bindingRole,
        int bindingOrdinal
) {
    public CreateCodeBindingCommand(
            UUID repositoryId,
            UUID blockId,
            String pathPattern
    ) {
        this(
                repositoryId,
                blockId,
                pathPattern,
                null,
                CodeAnchorKind.FILE,
                null,
                null,
                null,
                BindingRole.PRIMARY,
                1
        );
    }

    public CreateCodeBindingCommand(
            UUID repositoryId, UUID blockId, String pathPattern, String revision,
            CodeAnchorKind anchorKind, String symbolKey, Integer startLine, Integer endLine
    ) {
        this(repositoryId, blockId, pathPattern, revision, anchorKind, symbolKey,
                startLine, endLine, BindingRole.PRIMARY, 1);
    }
}
