package com.devcollab.mcp.application;

import com.devcollab.mcp.client.KnowledgeCoreGateway;
import com.devcollab.mcp.error.McpToolErrorCode;
import com.devcollab.mcp.error.McpToolException;
import com.devcollab.mcp.governance.ContextBudgetPolicy;
import com.devcollab.mcp.security.McpUserIdentity;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CodeReadApplicationService {

    private static final int MAX_BATCH_PATHS = 100;

    private final KnowledgeCoreGateway coreGateway;
    private final ContextBudgetPolicy budgetPolicy;

    public CodeReadApplicationService(
            KnowledgeCoreGateway coreGateway,
            ContextBudgetPolicy budgetPolicy
    ) {
        this.coreGateway = coreGateway;
        this.budgetPolicy = budgetPolicy;
    }

    public Map<String, Object> read(
            UUID workspaceId,
            UUID repositoryId,
            String path,
            Integer startLine,
            Integer endLine,
            boolean includeExistingBindings,
            McpUserIdentity identity
    ) {
        budgetPolicy.validateRepositoryPath(path);
        KnowledgeCoreGateway.RepositorySource source =
                coreGateway.readRepositorySource(workspaceId, repositoryId, path, identity);
        if (!source.readable() || source.content() == null) {
            throw new McpToolException(
                    McpToolErrorCode.UNSUPPORTED_FILE_TYPE,
                    "The repository file is not readable text"
            );
        }

        ContextBudgetPolicy.BudgetedContent content =
                budgetPolicy.limit(source.content(), startLine, endLine);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workspaceId", workspaceId.toString());
        result.put("repositoryId", source.repositoryId().toString());
        result.put("path", source.path());
        result.put("commitHash", source.commitSha());
        result.put("language", source.language());
        result.put("sizeBytes", source.sizeBytes());
        result.put("startLine", content.startLine());
        result.put("endLine", content.endLine());
        result.put("totalLines", content.totalLines());
        result.put("content", content.content());
        result.put("truncated", content.truncated());
        result.put("omittedLineCount", content.omittedLineCount());
        result.put("omittedCharacterCount", content.omittedCharacterCount());
        result.put("existingBindings", List.of());
        result.put("existingBindingsAvailable", false);
        result.put("existingBindingsRequested", includeExistingBindings);
        return result;
    }

    public Map<String, Object> readBatch(
            UUID workspaceId,
            UUID repositoryId,
            List<String> paths,
            String revision,
            McpUserIdentity identity
    ) {
        if (paths.size() > MAX_BATCH_PATHS) {
            throw new McpToolException(
                    McpToolErrorCode.INVALID_ARGUMENT,
                    "paths exceeds the configured batch limit"
            );
        }

        Map<String, Object> files = new LinkedHashMap<>();
        boolean batchTruncated = false;
        for (String path : paths) {
            budgetPolicy.validateRepositoryPath(path);
            KnowledgeCoreGateway.RepositorySource source =
                    coreGateway.readRepositorySource(workspaceId, repositoryId, path, identity);
            if (!source.readable() || source.content() == null) {
                throw new McpToolException(
                        McpToolErrorCode.UNSUPPORTED_FILE_TYPE,
                        "The repository file is not readable text"
                );
            }
            if (revision != null && !revision.isBlank()
                    && !revision.equalsIgnoreCase(source.commitSha())) {
                throw new McpToolException(
                        McpToolErrorCode.INVALID_ARGUMENT,
                        "Repository revision changed after the batch read request was created"
                );
            }
            ContextBudgetPolicy.BudgetedContent content =
                    budgetPolicy.limit(source.content(), null, null);
            batchTruncated = batchTruncated || content.truncated();

            Map<String, Object> file = new LinkedHashMap<>();
            file.put("path", source.path());
            file.put("commitHash", source.commitSha());
            file.put("language", source.language());
            file.put("sizeBytes", source.sizeBytes());
            file.put("startLine", content.startLine());
            file.put("endLine", content.endLine());
            file.put("totalLines", content.totalLines());
            file.put("content", content.content());
            file.put("truncated", content.truncated());
            file.put("omittedLineCount", content.omittedLineCount());
            file.put("omittedCharacterCount", content.omittedCharacterCount());
            files.put(source.path(), file);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workspaceId", workspaceId.toString());
        result.put("repositoryId", repositoryId.toString());
        result.put("revision", revision);
        result.put("truncated", batchTruncated);
        result.put("files", files);
        return result;
    }
}
