package com.devcollab.mcp.application;

import com.devcollab.mcp.client.KnowledgeCoreGateway;
import com.devcollab.mcp.config.McpProperties;
import com.devcollab.mcp.error.McpToolErrorCode;
import com.devcollab.mcp.error.McpToolException;
import com.devcollab.mcp.security.McpUserIdentity;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class BindingListBatchApplicationService {

    private final KnowledgeCoreGateway gateway;
    private final McpProperties properties;

    public BindingListBatchApplicationService(
            KnowledgeCoreGateway gateway,
            McpProperties properties
    ) {
        this.gateway = gateway;
        this.properties = properties;
    }

    public Map<String, Object> listBindings(
            UUID workspaceId,
            UUID repositoryId,
            List<String> filePaths,
            McpUserIdentity identity
    ) {
        if (filePaths.isEmpty() || filePaths.size() > properties.maxBindingBatchPaths()) {
            throw new McpToolException(
                    McpToolErrorCode.INVALID_ARGUMENT,
                    "filePaths exceeds the binding batch size"
            );
        }
        List<String> normalized = filePaths.stream()
                .map(path -> RepositoryPathPolicy.normalize(path, false))
                .distinct()
                .toList();
        var batch = gateway.getFileBindingsBatch(
                workspaceId, repositoryId, normalized, identity
        );
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workspaceId", batch.workspaceId());
        result.put("repositoryId", batch.repositoryId());
        result.put("files", batch.files().stream().map(file -> {
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("filePath", file.filePath());
            var bindings = file.bindings();
            group.put("fileHasBindings", !bindings.isEmpty());
            group.put("bindings", bindings.stream().map(binding -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("bindingId", binding.bindingId());
                item.put("repositoryId", binding.repositoryId());
                item.put("documentId", binding.documentId());
                item.put("documentTitle", binding.documentTitle());
                item.put("blockId", binding.blockId());
                item.put("pathPattern", binding.pathPattern());
                if (binding.revision() != null) {
                    item.put("revision", binding.revision());
                }
                if (binding.anchorKind() != null) {
                    item.put("anchorKind", binding.anchorKind());
                }
                if (binding.symbolKey() != null) {
                    item.put("symbolKey", binding.symbolKey());
                }
                if (binding.startLine() != null) {
                    item.put("startLine", binding.startLine());
                }
                if (binding.endLine() != null) {
                    item.put("endLine", binding.endLine());
                }
                if (binding.bindingRole() != null) {
                    item.put("bindingRole", binding.bindingRole());
                }
                item.put("bindingOrdinal", binding.bindingOrdinal());
                return item;
            }).toList());
            group.put("truncated", false);
            group.put("omittedBindingCount", 0);
            return group;
        }).toList());
        return result;
    }
}
