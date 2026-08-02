package com.devcollab.knowledgecore.git.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GitKnowledgeRepository {

    GitRepository saveRepository(GitRepository repository);

    Optional<GitRepository> findRepositoryById(UUID repositoryId);

    Optional<GitRepository> findRepositoryByRemoteUrl(UUID workspaceId, String remoteUrl);

    List<GitRepository> findRepositoriesByWorkspaceId(UUID workspaceId);

    void markRepositorySyncPending(UUID repositoryId, Instant updatedAt);

    void deleteRepository(UUID repositoryId);

    List<GitRepositoryFile> findFilesByRepositoryId(UUID repositoryId);

    Optional<GitRepositoryFile> findFileByRepositoryIdAndPath(
            UUID repositoryId,
            String path
    );

    List<CodeSymbol> findSymbolsByRepositoryId(UUID repositoryId, String filePath);

    List<CodeSymbolDependency> findSymbolDependenciesByRepositoryId(
            UUID repositoryId,
            String filePath
    );

    List<CodeFileDependency> findFileDependenciesByRepositoryId(
            UUID repositoryId,
            String filePath
    );

    GitChange saveChange(GitChange change);

    Optional<GitChange> findChangeById(UUID changeId);

    Optional<GitChange> findChangeByExternalIdentity(
            UUID repositoryId,
            GitChangeType type,
            String externalId
    );

    List<GitChange> findChangesByRepositoryId(UUID repositoryId);

    void saveDiffs(List<GitFileDiff> diffs);

    List<GitFileDiff> findDiffsByChangeId(UUID changeId);

    CodeDocumentBinding saveBinding(CodeDocumentBinding binding);

    Optional<CodeDocumentBinding> findBindingById(UUID bindingId);

    Optional<CodeDocumentBinding> findBindingByIdForUpdate(UUID bindingId);

    Optional<CodeDocumentBinding> findExactBinding(
            UUID repositoryId,
            UUID documentId,
            UUID blockId,
            String pathPattern,
            String revision,
            CodeAnchorKind anchorKind,
            String symbolKey,
            Integer startLine,
            Integer endLine
    );

    List<CodeDocumentBinding> findBindingsByDocumentId(UUID documentId);

    List<CodeDocumentBinding> findBindingsByBlockId(UUID blockId);

    List<CodeDocumentBinding> findBindingsByRepositoryId(UUID repositoryId);

    boolean deleteBinding(UUID bindingId);
}
