package com.devcollab.mcp.application;

import com.devcollab.mcp.client.KnowledgeCoreGateway;
import com.devcollab.mcp.error.McpToolErrorCode;
import com.devcollab.mcp.error.McpToolException;
import com.devcollab.mcp.governance.ContextBudgetPolicy;
import com.devcollab.mcp.security.McpUserIdentity;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CodeReadApplicationService {

    private static final int MAX_BATCH_PATHS = 100;
    private static final int MAX_BATCH_BYTES = 200 * 1024;

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
        int remainingBytes = MAX_BATCH_BYTES;
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
            TruncatedContent content = fitWithinRemainingBytes(source.content(), remainingBytes);
            remainingBytes = Math.max(0, remainingBytes - content.bytesUsed());
            batchTruncated = batchTruncated || content.truncated();

            Map<String, Object> file = new LinkedHashMap<>();
            file.put("path", source.path());
            file.put("commitHash", source.commitSha());
            file.put("language", source.language());
            file.put("totalLines", totalLines(source.content()));
            file.put("content", content.content());
            file.put("truncated", content.truncated());
            files.put(source.path(), file);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workspaceId", workspaceId.toString());
        result.put("repositoryId", repositoryId.toString());
        result.put("revision", revision);
        result.put("byteLimit", MAX_BATCH_BYTES);
        result.put("truncated", batchTruncated);
        result.put("files", files);
        return result;
    }

    private TruncatedContent fitWithinRemainingBytes(String content, int remainingBytes) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= remainingBytes) {
            return new TruncatedContent(content, bytes.length, false);
        }
        if (remainingBytes <= 0) {
            return new TruncatedContent("", 0, true);
        }
        int end = 0;
        int bytesUsed = 0;
        while (end < content.length()) {
            int next = content.offsetByCodePoints(end, 1);
            int nextBytes = content.substring(end, next).getBytes(StandardCharsets.UTF_8).length;
            if (bytesUsed + nextBytes > remainingBytes) {
                break;
            }
            bytesUsed += nextBytes;
            end = next;
        }
        return new TruncatedContent(content.substring(0, end), bytesUsed, true);
    }

    private int totalLines(String content) {
        if (content.isEmpty()) {
            return 0;
        }
        return content.split("\\R", -1).length;
    }

    private record TruncatedContent(String content, int bytesUsed, boolean truncated) {
    }
}
