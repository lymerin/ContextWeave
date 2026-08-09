package com.devcollab.mcp.application;

import com.devcollab.mcp.client.KnowledgeCoreGateway;
import com.devcollab.mcp.config.McpProperties;
import com.devcollab.mcp.error.McpToolErrorCode;
import com.devcollab.mcp.error.McpToolException;
import com.devcollab.mcp.security.McpUserIdentity;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class DocumentStructureApplicationService {

    private static final int MAX_BATCH_DOCUMENTS = 100;

    private final KnowledgeCoreGateway knowledgeCoreGateway;
    private final McpProperties mcpProperties;

    public DocumentStructureApplicationService(KnowledgeCoreGateway knowledgeCoreGateway, McpProperties mcpProperties) {
        this.knowledgeCoreGateway = knowledgeCoreGateway;
        this.mcpProperties = mcpProperties;
    }

    public Map<String, Object> getDocumentStructure(
            UUID workspaceId,
            UUID documentId,
            boolean includeBlockContent,
            Integer maxBlocksArg,
            Integer maxContentCharsArg,
            McpUserIdentity identity
    ) {
        int maxBlocks = maxBlocksArg != null ? Math.min(maxBlocksArg, mcpProperties.maxDocumentBlocks()) : mcpProperties.maxDocumentBlocks();
        int maxContentChars = maxContentCharsArg != null ? Math.min(maxContentCharsArg, mcpProperties.maxDocumentContentCharacters()) : mcpProperties.maxDocumentContentCharacters();

        KnowledgeCoreGateway.DocumentStructure structure = knowledgeCoreGateway.getDocumentStructure(
                workspaceId,
                documentId,
                includeBlockContent,
                maxBlocks,
                maxContentChars,
                identity
        );

        Map<String, Object> result = new HashMap<>();
        result.put("documentId", structure.documentId());
        result.put("workspaceId", structure.workspaceId());
        result.put("title", structure.title());
        result.put("documentType", structure.documentType());
        result.put("reviewStatus", structure.reviewStatus());
        if (structure.updatedAt() != null) {
            result.put("updatedAt", structure.updatedAt().toString());
        }
        
        List<Map<String, Object>> blocks = structure.blocks().stream()
                .map(b -> {
                    Map<String, Object> blockMap = new HashMap<>();
                    blockMap.put("blockId", b.blockId());
                    blockMap.put("blockType", b.blockType());
                    blockMap.put("sortOrder", b.sortOrder());
                    blockMap.put("version", b.version());
                    if (b.plainText() != null) {
                        blockMap.put("plainText", b.plainText());
                    }
                    if (b.content() != null) {
                        blockMap.put("content", b.content());
                    }
                    blockMap.put("contentTruncated", b.isContentTruncated());
                    return blockMap;
                })
                .collect(Collectors.toList());
                
        result.put("blocks", blocks);
        result.put("truncated", structure.isTruncated());
        result.put("omittedBlockCount", structure.omittedBlockCount());
        result.put("omittedCharacterCount", structure.omittedCharacterCount());
        return result;
    }

    public Map<String, Object> getDocumentStructures(
            UUID workspaceId,
            List<UUID> documentIds,
            boolean includeBlockContent,
            Integer maxBlocksArg,
            Integer maxContentCharsArg,
            McpUserIdentity identity
    ) {
        if (documentIds.size() > MAX_BATCH_DOCUMENTS) {
            throw new McpToolException(
                    McpToolErrorCode.INVALID_ARGUMENT,
                    "documentIds exceeds the configured batch limit"
            );
        }
        List<Map<String, Object>> structures = documentIds.stream()
                .map(documentId -> getDocumentStructure(
                        workspaceId,
                        documentId,
                        includeBlockContent,
                        maxBlocksArg,
                        maxContentCharsArg,
                        identity
                ))
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workspaceId", workspaceId);
        result.put("includeBlockContent", includeBlockContent);
        result.put("structures", structures);
        return result;
    }
}
