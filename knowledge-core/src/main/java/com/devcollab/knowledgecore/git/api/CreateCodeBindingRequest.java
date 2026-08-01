package com.devcollab.knowledgecore.git.api;

import com.devcollab.knowledgecore.git.domain.CodeAnchorKind;
import com.devcollab.knowledgecore.git.domain.BindingRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateCodeBindingRequest(
        @NotNull UUID repositoryId,
        UUID blockId,
        @NotBlank @Size(max = 1000) String pathPattern,
        @Size(max = 255) String revision,
        CodeAnchorKind anchorKind,
        @Size(max = 1000) String symbolKey,
        Integer startLine,
        Integer endLine,
        BindingRole bindingRole,
        Integer bindingOrdinal
) {
}
