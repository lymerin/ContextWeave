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
public class RepositoryCodeMetadataApplicationService {

    private final KnowledgeCoreGateway gateway;
    private final McpProperties properties;

    public RepositoryCodeMetadataApplicationService(
            KnowledgeCoreGateway gateway,
            McpProperties properties
    ) {
        this.gateway = gateway;
        this.properties = properties;
    }

    public Map<String, Object> inspect(
            UUID workspaceId,
            UUID repositoryId,
            String revision,
            List<String> filePaths,
            McpUserIdentity identity
    ) {
        if (filePaths.isEmpty() || filePaths.size() > properties.maxBindingBatchPaths()) {
            throw new McpToolException(
                    McpToolErrorCode.INVALID_ARGUMENT,
                    "filePaths exceeds the code metadata batch size"
            );
        }
        if (identity.delegated()) {
            if (!workspaceId.equals(identity.delegationWorkspaceId())
                    || !repositoryId.equals(identity.delegationRepositoryId())
                    || !revision.equalsIgnoreCase(identity.delegationRevision())) {
                throw new McpToolException(
                        McpToolErrorCode.PERMISSION_DENIED,
                        "Delegated repository scope or revision does not match"
                );
            }
        }
        List<String> normalized = filePaths.stream()
                .map(path -> RepositoryPathPolicy.normalize(path, false))
                .distinct()
                .toList();
        var batch = gateway.inspectCodeMetadata(
                workspaceId, repositoryId, revision, normalized, identity
        );
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workspaceId", batch.workspaceId());
        result.put("repositoryId", batch.repositoryId());
        result.put("revision", batch.revision());
        result.put("files", batch.files().stream().map(file -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("filePath", file.filePath());
            item.put("blobSha", file.blobSha());
            item.put("language", file.language());
            item.put("packageName", file.packageName());
            item.put("moduleKey", file.moduleKey());
            item.put("moduleName", file.moduleKey());
            item.put("layerHint", file.layerHint());
            item.put("imports", file.imports());
            item.put("resolvedRepositoryImports", List.of());
            item.put("exportedSymbols", file.exportedSymbols());
            item.put("topLevelSymbols", file.topLevelSymbols());
            item.put("annotations", file.annotations());
            item.put("routeHints", file.routeHints());
            item.put("roleHints", file.roleHints());
            item.put("parseStatus", file.parseStatus());
            item.put("errorCode", file.errorCode());
            return item;
        }).toList());
        return result;
    }
}
