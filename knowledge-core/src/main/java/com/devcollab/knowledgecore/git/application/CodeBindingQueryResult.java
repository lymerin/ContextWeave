package com.devcollab.knowledgecore.git.application;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;

public record CodeBindingQueryResult(
        UUID workspaceId,
        UUID repositoryId,
        String filePath,
        boolean fileHasBindings,
        List<CodeBindingQueryItem> bindings,
        @JsonProperty("truncated") boolean isTruncated,
        int omittedBindingCount
) {
}
