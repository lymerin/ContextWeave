package com.devcollab.mcp.application;

import com.devcollab.mcp.client.KnowledgeCoreGateway;
import com.devcollab.mcp.config.McpProperties;
import com.devcollab.mcp.error.McpToolErrorCode;
import com.devcollab.mcp.error.McpToolException;
import com.devcollab.mcp.security.McpUserIdentity;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class RepositoryFilesApplicationService {

    private final KnowledgeCoreGateway gateway;
    private final McpProperties properties;

    public RepositoryFilesApplicationService(
            KnowledgeCoreGateway gateway,
            McpProperties properties
    ) {
        this.gateway = gateway;
        this.properties = properties;
    }

    public Map<String, Object> listFiles(
            UUID workspaceId,
            UUID repositoryId,
            String revision,
            String pathPrefix,
            boolean recursive,
            String cursor,
            int limit,
            McpUserIdentity identity
    ) {
        String normalizedPrefix = RepositoryPathPolicy.normalize(pathPrefix, true);
        if (limit < 1 || limit > properties.maxRepositoryPageSize()) {
            throw new McpToolException(
                    McpToolErrorCode.INVALID_ARGUMENT,
                    "limit exceeds the repository page size"
            );
        }
        if (identity.delegated()
                && (!workspaceId.equals(identity.delegationWorkspaceId())
                || !repositoryId.equals(identity.delegationRepositoryId()))) {
            throw new McpToolException(
                    McpToolErrorCode.PERMISSION_DENIED,
                    "Delegated repository scope does not match"
            );
        }
        String expectedRevision = revision;
        if (identity.delegated()) {
            if (revision != null
                    && !revision.equalsIgnoreCase(identity.delegationRevision())) {
                throw new McpToolException(
                        McpToolErrorCode.PERMISSION_DENIED,
                        "Delegated repository revision does not match"
                );
            }
            expectedRevision = identity.delegationRevision();
        }
        var page = gateway.listRepositoryFiles(
                workspaceId, repositoryId, normalizedPrefix, recursive,
                cursor, limit, identity
        );
        if (expectedRevision != null
                && !expectedRevision.equalsIgnoreCase(page.revision())) {
            throw new McpToolException(
                    McpToolErrorCode.INVALID_ARGUMENT,
                    "Repository revision changed during file discovery"
            );
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workspaceId", page.workspaceId());
        result.put("repositoryId", page.repositoryId());
        result.put("revision", page.revision());
        result.put("pathPrefix", page.pathPrefix());
        result.put("recursive", page.recursive());
        result.put("files", page.files().stream().map(file -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("filePath", file.filePath());
            item.put("blobSha", file.blobSha());
            item.put("fileName", file.fileName());
            item.put("extension", file.extension());
            item.put("sizeBytes", file.sizeBytes());
            item.put("language", file.language());
            item.put("readable", file.readable());
            item.put("isDirectory", false);
            return item;
        }).toList());
        result.put("nextCursor", page.nextCursor());
        result.put("hasMore", page.hasMore());
        return result;
    }
}
