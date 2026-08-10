package com.devcollab.knowledgecore.git.application;

import java.util.List;
import java.util.UUID;

public record CodeMetadataBatchResult(
        UUID workspaceId,
        UUID repositoryId,
        String revision,
        List<FileMetadata> files
) {
    public record FileMetadata(
            String filePath,
            String blobSha,
            String language,
            String packageName,
            String moduleKey,
            String layerHint,
            List<String> imports,
            List<String> exportedSymbols,
            List<String> topLevelSymbols,
            List<String> annotations,
            List<String> routeHints,
            List<String> roleHints,
            String parseStatus,
            String errorCode
    ) {
    }
}
