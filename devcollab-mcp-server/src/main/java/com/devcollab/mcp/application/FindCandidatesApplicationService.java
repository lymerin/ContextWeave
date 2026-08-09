package com.devcollab.mcp.application;

import com.devcollab.mcp.client.KnowledgeCoreGateway;
import com.devcollab.mcp.config.McpProperties;
import com.devcollab.mcp.error.McpToolErrorCode;
import com.devcollab.mcp.error.McpToolException;
import com.devcollab.mcp.governance.ContextBudgetPolicy;
import com.devcollab.mcp.security.McpUserIdentity;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class FindCandidatesApplicationService {

    private static final int MAX_BATCH_PATHS = 100;

    private final KnowledgeCoreGateway knowledgeCoreGateway;
    private final McpProperties properties;
    private final ContextBudgetPolicy budgetPolicy;

    public FindCandidatesApplicationService(
            KnowledgeCoreGateway knowledgeCoreGateway,
            McpProperties properties,
            ContextBudgetPolicy budgetPolicy
    ) {
        this.knowledgeCoreGateway = knowledgeCoreGateway;
        this.properties = properties;
        this.budgetPolicy = budgetPolicy;
    }

    public Map<String, Object> findCandidates(
            UUID workspaceId,
            UUID repositoryId,
            String filePath,
            String query,
            Integer requestedLimit,
            McpUserIdentity identity
    ) {
        Inputs inputs = validate(repositoryId, filePath, query, requestedLimit);
        KnowledgeCoreGateway.DocumentCandidateResult coreResult =
                knowledgeCoreGateway.findDocumentCandidates(
                        workspaceId, inputs.repositoryId(), inputs.filePath(), inputs.query(),
                        inputs.limit(), identity
                );

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workspaceId", coreResult.workspaceId());
        result.put("repositoryId", coreResult.repositoryId());
        result.put("filePath", coreResult.filePath());
        result.put("query", coreResult.query());
        result.put("candidates", coreResult.candidates().stream().map(this::candidateMap).toList());
        result.put("truncated", coreResult.truncated());
        result.put("omittedCandidateCount", coreResult.omittedCandidateCount());
        return result;
    }

    public Map<String, Object> findCandidatesBatch(
            UUID workspaceId,
            UUID repositoryId,
            List<String> paths,
            Integer requestedLimit,
            McpUserIdentity identity
    ) {
        if (paths.size() > MAX_BATCH_PATHS) {
            throw invalidQuery("paths exceeds the configured batch limit");
        }

        Map<String, Object> byPath = new LinkedHashMap<>();
        for (String path : paths) {
            Map<String, Object> result = findCandidates(
                    workspaceId, repositoryId, path, null, requestedLimit, identity
            );
            byPath.put(path.replace('\\', '/'), result.get("candidates"));
        }

        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put("workspaceId", workspaceId);
        batch.put("repositoryId", repositoryId);
        batch.put("candidatesByPath", byPath);
        return batch;
    }

    private Map<String, Object> candidateMap(KnowledgeCoreGateway.DocumentCandidate candidate) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("documentId", candidate.documentId());
        item.put("title", candidate.title());
        item.put("score", candidate.score());
        item.put("matchReasons", candidate.matchReasons().stream().map(reason -> {
            Map<String, Object> reasonMap = new LinkedHashMap<>();
            reasonMap.put("code", reason.code());
            reasonMap.put("weight", reason.weight());
            reasonMap.put("matchedTerm", reason.matchedTerm());
            reasonMap.put("matchedBlockIds", reason.matchedBlockIds());
            return reasonMap;
        }).toList());
        item.put("matchedBlockIds", candidate.matchedBlockIds());
        item.put("existingBindingCount", candidate.existingBindingCount());
        return item;
    }

    private Inputs validate(
            UUID repositoryId,
            String filePath,
            String query,
            Integer requestedLimit
    ) {
        String normalizedPath = filePath == null ? null : filePath.trim();
        String normalizedQuery = query == null ? null : query.trim();
        if (filePath != null && normalizedPath.isEmpty()) {
            throw invalidQuery("filePath must not be blank");
        }
        if (query != null && normalizedQuery.isEmpty()) {
            throw invalidQuery("query must not be blank");
        }
        if (normalizedPath == null && normalizedQuery == null) {
            throw invalidQuery("filePath or query is required");
        }
        if (normalizedPath != null && repositoryId == null) {
            throw invalidQuery("repositoryId is required with filePath");
        }
        if (normalizedPath != null) {
            budgetPolicy.validateRepositoryPath(normalizedPath);
            normalizedPath = normalizedPath.replace('\\', '/');
        }
        if (normalizedQuery != null
                && normalizedQuery.codePointCount(0, normalizedQuery.length())
                > properties.maxDocumentQueryCharacters()) {
            throw invalidQuery("query exceeds the configured context limit");
        }
        int limit = requestedLimit == null ? properties.maxCandidates() : requestedLimit;
        if (limit < 1 || limit > properties.maxCandidates()) {
            throw invalidQuery("limit must be between 1 and " + properties.maxCandidates());
        }
        return new Inputs(repositoryId, normalizedPath, normalizedQuery, limit);
    }

    private McpToolException invalidQuery(String message) {
        return new McpToolException(McpToolErrorCode.INVALID_DOCUMENT_QUERY, message);
    }

    private record Inputs(UUID repositoryId, String filePath, String query, int limit) {
    }
}
