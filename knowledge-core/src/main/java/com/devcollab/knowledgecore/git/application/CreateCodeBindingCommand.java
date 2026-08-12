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
        int bindingOrdinal,
        String boundSignature
) {
}
