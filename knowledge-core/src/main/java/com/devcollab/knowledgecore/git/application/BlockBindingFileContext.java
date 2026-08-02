package com.devcollab.knowledgecore.git.application;

import java.util.List;
import java.util.UUID;

/**
 * Result of resolving a document block to its bound source file
 * together with all bindings for that file.
 */
public record BlockBindingFileContext(
        UUID workspaceId,
        UUID repositoryId,
        UUID documentId,
        UUID blockId,
        String filePath,
        UUID preferredBindingId,
        List<CodeBindingQueryItem> bindings
) {
}
