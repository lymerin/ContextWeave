package com.devcollab.mcp.client;

import com.devcollab.mcp.security.McpUserIdentity;

import java.util.List;
import java.util.UUID;
import java.util.Map;

public interface KnowledgeCoreGateway {

    WorkspaceContext getWorkspaceContext(UUID workspaceId, McpUserIdentity identity);

    RepositorySource readRepositorySource(
            UUID workspaceId,
            UUID repositoryId,
            String path,
            McpUserIdentity identity
    );

    DocumentStructure getDocumentStructure(
            UUID workspaceId,
            UUID documentId,
            boolean includeBlockContent,
            int maxBlocks,
            int maxContentChars,
            McpUserIdentity identity
    );

    BindingQueryResult getFileBindings(
            UUID workspaceId,
            UUID repositoryId,
            String filePath,
            int maxBindings,
            McpUserIdentity identity
    );

    DocumentCandidateResult findDocumentCandidates(
            UUID workspaceId,
            UUID repositoryId,
            String filePath,
            String query,
            int limit,
            McpUserIdentity identity
    );

    RepositoryFilePage listRepositoryFiles(
            UUID workspaceId,
            UUID repositoryId,
            String pathPrefix,
            boolean recursive,
            String cursor,
            int limit,
            McpUserIdentity identity
    );

    RepositoryChangePage listRepositoryChanges(
            UUID workspaceId,
            UUID repositoryId,
            String cursor,
            int limit,
            McpUserIdentity identity
    );

    BindingBatchResult getFileBindingsBatch(
            UUID workspaceId,
            UUID repositoryId,
            List<String> filePaths,
            McpUserIdentity identity
    );

    CodeMetadataBatch inspectCodeMetadata(
            UUID workspaceId,
            UUID repositoryId,
            String revision,
            List<String> filePaths,
            McpUserIdentity identity
    );

    Map<String, Object> submitDocumentChange(
            UUID workspaceId,
            Map<String, Object> request,
            McpUserIdentity identity
    );

    record WorkspaceContext(
            UUID workspaceId,
            String name,
            String currentUserRole,
            List<RepositoryContext> repositories
    ) {
    }

    record RepositoryContext(
            UUID repositoryId,
            String name,
            String provider,
            String remoteUrl,
            String defaultBranch,
            String syncStatus,
            String lastSyncedCommit
    ) {
    }

    record RepositorySource(
            UUID repositoryId,
            String commitSha,
            String path,
            long sizeBytes,
            String language,
            boolean readable,
            String content
    ) {
    }

    record DocumentStructure(
            UUID documentId,
            UUID workspaceId,
            String title,
            String documentType,
            String reviewStatus,
            java.time.Instant updatedAt,
            List<BlockInfo> blocks,
            boolean isTruncated,
            int omittedBlockCount,
            int omittedCharacterCount
    ) {
    }

    record BlockInfo(
            UUID blockId,
            String blockType,
            int sortOrder,
            long version,
            String plainText,
            String content,
            boolean isContentTruncated
    ) {
    }

    record BindingQueryResult(
            UUID workspaceId,
            UUID repositoryId,
            String filePath,
            boolean fileHasBindings,
            List<BindingInfo> bindings,
            boolean isTruncated,
            int omittedBindingCount
    ) {
    }

    record BindingInfo(
            UUID bindingId,
            String pathPattern,
            UUID documentId,
            String documentTitle,
            UUID blockId,
            String revision,
            String anchorKind,
            String symbolKey,
            Integer startLine,
            Integer endLine,
            String bindingRole,
            int bindingOrdinal
    ) {
    }

    record DocumentCandidateResult(
            UUID workspaceId,
            UUID repositoryId,
            String filePath,
            String query,
            List<DocumentCandidate> candidates,
            boolean truncated,
            int omittedCandidateCount
    ) {
    }

    record RepositoryFilePage(
            UUID workspaceId,
            UUID repositoryId,
            String revision,
            String pathPrefix,
            boolean recursive,
            List<RepositoryFileInfo> files,
            String nextCursor,
            boolean hasMore
    ) {
    }

    record RepositoryFileInfo(
            String filePath,
            String fileName,
            String extension,
            long sizeBytes,
            String language,
            boolean readable,
            boolean isDirectory
    ) {
    }

    record RepositoryChangePage(
            UUID workspaceId,
            UUID repositoryId,
            UUID changeId,
            String changeType,
            String commitSha,
            List<RepositoryChangedFile> files,
            String nextCursor,
            boolean hasMore
    ) {
    }

    record RepositoryChangedFile(
            String status,
            String filePath,
            String oldPath,
            boolean binaryFile
    ) {
    }

    record BindingBatchResult(
            UUID workspaceId,
            UUID repositoryId,
            List<FileBindingGroup> files
    ) {
    }

    record FileBindingGroup(String filePath, List<BatchBindingInfo> bindings) {
    }

    record BatchBindingInfo(
            UUID bindingId,
            UUID repositoryId,
            UUID documentId,
            String documentTitle,
            UUID blockId,
            String pathPattern,
            String revision,
            String anchorKind,
            String symbolKey,
            Integer startLine,
            Integer endLine,
            String bindingRole,
            int bindingOrdinal
    ) {
    }

    record CodeMetadataBatch(
            UUID workspaceId,
            UUID repositoryId,
            String revision,
            List<CodeMetadataInfo> files
    ) {
    }

    record CodeMetadataInfo(
            String filePath,
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

    record DocumentCandidate(
            UUID documentId,
            String title,
            int score,
            List<DocumentCandidateMatchReason> matchReasons,
            List<UUID> matchedBlockIds,
            int existingBindingCount
    ) {
    }

    record DocumentCandidateMatchReason(
            String code,
            int weight,
            String matchedTerm,
            List<UUID> matchedBlockIds
    ) {
    }
}
