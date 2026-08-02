package com.devcollab.mcp.application;

import com.devcollab.mcp.client.KnowledgeCoreGateway;
import com.devcollab.mcp.config.McpProperties;
import com.devcollab.mcp.error.McpToolErrorCode;
import com.devcollab.mcp.error.McpToolException;
import com.devcollab.mcp.security.McpUserIdentity;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class BindingListApplicationService {

    private final KnowledgeCoreGateway knowledgeCoreGateway;
    private final McpProperties mcpProperties;

    public BindingListApplicationService(KnowledgeCoreGateway knowledgeCoreGateway, McpProperties mcpProperties) {
        this.knowledgeCoreGateway = knowledgeCoreGateway;
        this.mcpProperties = mcpProperties;
    }

    public Map<String, Object> getFileBindings(
            UUID workspaceId,
            UUID repositoryId,
            String filePath,
            McpUserIdentity identity
    ) {
        validateRepositoryPath(filePath);

        KnowledgeCoreGateway.BindingQueryResult queryResult = knowledgeCoreGateway.getFileBindings(
                workspaceId,
                repositoryId,
                filePath,
                mcpProperties.maxBindings(),
                identity
        );

        Map<String, Object> result = new HashMap<>();
        result.put("workspaceId", queryResult.workspaceId());
        result.put("repositoryId", queryResult.repositoryId());
        result.put("filePath", queryResult.filePath());
        result.put("fileHasBindings", queryResult.fileHasBindings());
        
        List<Map<String, Object>> bindings = queryResult.bindings().stream()
                .map(b -> {
                    Map<String, Object> bindingMap = new HashMap<>();
                    bindingMap.put("bindingId", b.bindingId());
                    bindingMap.put("pathPattern", b.pathPattern());
                    bindingMap.put("documentId", b.documentId());
                    bindingMap.put("documentTitle", b.documentTitle());
                    if (b.blockId() != null) {
                        bindingMap.put("blockId", b.blockId());
                    }
                    if (b.revision() != null) {
                        bindingMap.put("revision", b.revision());
                    }
                    if (b.anchorKind() != null) {
                        bindingMap.put("anchorKind", b.anchorKind());
                    }
                    if (b.symbolKey() != null) {
                        bindingMap.put("symbolKey", b.symbolKey());
                    }
                    if (b.startLine() != null) {
                        bindingMap.put("startLine", b.startLine());
                    }
                    if (b.endLine() != null) {
                        bindingMap.put("endLine", b.endLine());
                    }
                    if (b.bindingRole() != null) {
                        bindingMap.put("bindingRole", b.bindingRole());
                    }
                    bindingMap.put("bindingOrdinal", b.bindingOrdinal());
                    return bindingMap;
                })
                .collect(Collectors.toList());
                
        result.put("bindings", bindings);
        result.put("truncated", queryResult.isTruncated());
        result.put("omittedBindingCount", queryResult.omittedBindingCount());
        return result;
    }

    private void validateRepositoryPath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            throw new McpToolException(McpToolErrorCode.INVALID_ARGUMENT, "File path cannot be blank");
        }
        if (filePath.contains("\0")) {
            throw new McpToolException(McpToolErrorCode.INVALID_REPOSITORY_PATH, "File path must not contain nul");
        }
        String trimmed = filePath.trim();
        if (trimmed.isEmpty()) {
            throw new McpToolException(McpToolErrorCode.INVALID_ARGUMENT, "File path cannot be blank");
        }
        String normalized = trimmed.replace('\\', '/');
        if (normalized.startsWith("/")) {
            throw new McpToolException(McpToolErrorCode.INVALID_REPOSITORY_PATH, "File path must be a valid relative path");
        }
        if (normalized.matches("^[a-zA-Z]:.*")) {
            throw new McpToolException(McpToolErrorCode.INVALID_REPOSITORY_PATH, "File path must not contain a drive letter");
        }
        if (normalized.startsWith("//")) {
            throw new McpToolException(McpToolErrorCode.INVALID_REPOSITORY_PATH, "File path must not be a UNC path");
        }
        String[] segments = normalized.split("/");
        for (String segment : segments) {
            if ("..".equals(segment)) {
                throw new McpToolException(McpToolErrorCode.INVALID_REPOSITORY_PATH, "File path must not contain traversal");
            }
            if (segment.isEmpty()) {
                throw new McpToolException(McpToolErrorCode.INVALID_REPOSITORY_PATH, "File path must not contain empty segments");
            }
        }
    }
}
