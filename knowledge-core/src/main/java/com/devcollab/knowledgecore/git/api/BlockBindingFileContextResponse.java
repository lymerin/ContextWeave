package com.devcollab.knowledgecore.git.api;

import com.devcollab.knowledgecore.git.application.BlockBindingFileContext;

import java.util.List;
import java.util.UUID;

public record BlockBindingFileContextResponse(
        UUID workspaceId,
        UUID repositoryId,
        UUID documentId,
        UUID blockId,
        String filePath,
        UUID preferredBindingId,
        List<CodeBindingQueryItemResponse> bindings
) {
    public static BlockBindingFileContextResponse from(BlockBindingFileContext ctx) {
        return new BlockBindingFileContextResponse(
                ctx.workspaceId(),
                ctx.repositoryId(),
                ctx.documentId(),
                ctx.blockId(),
                ctx.filePath(),
                ctx.preferredBindingId(),
                ctx.bindings().stream()
                        .map(CodeBindingQueryItemResponse::from)
                        .toList()
        );
    }
}
