package com.devcollab.knowledgecore.git.application;

import com.devcollab.knowledgecore.document.core.application.exception.DocumentBlockNotFoundException;
import com.devcollab.knowledgecore.common.outbox.application.OutboxEventPublisher;
import com.devcollab.knowledgecore.common.outbox.application.OutboxEventTypes;
import com.devcollab.knowledgecore.document.core.application.exception.DocumentNotFoundException;
import com.devcollab.knowledgecore.document.core.domain.Document;
import com.devcollab.knowledgecore.document.core.domain.DocumentBlock;
import com.devcollab.knowledgecore.document.core.domain.DocumentBlockRepository;
import com.devcollab.knowledgecore.document.core.domain.DocumentRepository;
import com.devcollab.knowledgecore.git.application.exception.GitChangeNotFoundException;
import com.devcollab.knowledgecore.git.application.exception.GitRepositoryAlreadyExistsException;
import com.devcollab.knowledgecore.git.application.exception.GitRepositoryNotFoundException;
import com.devcollab.knowledgecore.git.application.exception.GitRepositoryFileNotFoundException;
import com.devcollab.knowledgecore.git.application.exception.InvalidCodeBindingException;
import com.devcollab.knowledgecore.git.application.exception.CodeBindingNotFoundException;
import com.devcollab.knowledgecore.git.application.exception.DuplicateCodeBindingException;
import com.devcollab.knowledgecore.git.domain.CodeAnchorKind;
import com.devcollab.knowledgecore.git.domain.CodeDocumentBinding;
import com.devcollab.knowledgecore.git.domain.GitChange;
import com.devcollab.knowledgecore.git.domain.GitFileDiff;
import com.devcollab.knowledgecore.git.domain.GitKnowledgeRepository;
import com.devcollab.knowledgecore.git.domain.GitRepository;
import com.devcollab.knowledgecore.git.domain.GitRepositoryFile;
import com.devcollab.knowledgecore.git.domain.GitRepositoryStatus;
import com.devcollab.knowledgecore.workspace.application.WorkspaceApplicationService;
import com.devcollab.knowledgecore.workspace.application.WorkspacePermissionPolicy;
import com.devcollab.knowledgecore.workspace.application.exception.WorkspaceAccessDeniedException;
import com.devcollab.knowledgecore.workspace.domain.WorkspaceMember;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class GitKnowledgeApplicationService {

    private static final int DEFAULT_MAX_BINDINGS_PER_FILE = 1000;

    private final GitKnowledgeRepository gitRepository;
    private final WorkspaceApplicationService workspaceService;
    private final WorkspacePermissionPolicy permissionPolicy;
    private final DocumentRepository documentRepository;
    private final DocumentBlockRepository blockRepository;
    private final OutboxEventPublisher outboxPublisher;
    private final CodeMetadataInspector metadataInspector;

    public GitKnowledgeApplicationService(
            GitKnowledgeRepository gitRepository,
            WorkspaceApplicationService workspaceService,
            WorkspacePermissionPolicy permissionPolicy,
            DocumentRepository documentRepository,
            DocumentBlockRepository blockRepository,
            OutboxEventPublisher outboxPublisher,
            CodeMetadataInspector metadataInspector
    ) {
        this.gitRepository = gitRepository;
        this.workspaceService = workspaceService;
        this.permissionPolicy = permissionPolicy;
        this.documentRepository = documentRepository;
        this.blockRepository = blockRepository;
        this.outboxPublisher = outboxPublisher;
        this.metadataInspector = metadataInspector;
    }

    @Transactional
    public GitRepository registerRepository(
            UUID workspaceId,
            UUID currentUserId,
            RegisterGitRepositoryCommand command
    ) {
        requireAdmin(workspaceId, currentUserId);
        String remoteUrl = normalizeRemoteUrl(command.remoteUrl());
        gitRepository.findRepositoryByRemoteUrl(workspaceId, remoteUrl)
                .ifPresent(existing -> {
                    throw new GitRepositoryAlreadyExistsException();
                });
        Instant now = Instant.now();
        GitRepositoryStatus status = command.provider().name().equals("GITHUB")
                ? GitRepositoryStatus.SYNC_PENDING
                : GitRepositoryStatus.REGISTERED;
        GitRepository saved = gitRepository.saveRepository(new GitRepository(
                UUID.randomUUID(), workspaceId, command.name().trim(),
                command.provider(), remoteUrl, command.defaultBranch().trim(),
                currentUserId, now, now, status, null, null, null
        ));
        if (status == GitRepositoryStatus.SYNC_PENDING) {
            publishSyncRequest(saved);
        }
        return saved;
    }

    public List<GitRepository> listRepositories(
            UUID workspaceId,
            UUID currentUserId
    ) {
        workspaceService.requireMembership(workspaceId, currentUserId);
        return gitRepository.findRepositoriesByWorkspaceId(workspaceId);
    }

    @Transactional
    public GitRepository requestSync(
            UUID workspaceId,
            UUID repositoryId,
            UUID currentUserId
    ) {
        requireAdmin(workspaceId, currentUserId);
        GitRepository repository = requireRepository(repositoryId, workspaceId);
        if (repository.provider().name().equals("GITHUB")) {
            Instant now = Instant.now();
            gitRepository.markRepositorySyncPending(repositoryId, now);
            publishSyncRequest(repository);
            return new GitRepository(
                    repository.id(), repository.workspaceId(), repository.name(),
                    repository.provider(), repository.remoteUrl(),
                    repository.defaultBranch(), repository.createdBy(),
                    repository.createdAt(), now,
                    GitRepositoryStatus.SYNC_PENDING,
                    repository.lastSyncedCommit(), repository.lastSyncedAt(), null
            );
        }
        throw new InvalidCodeBindingException("当前自动同步仅支持 GitHub 仓库");
    }

    @Transactional
    public void deleteRepository(
            UUID workspaceId,
            UUID repositoryId,
            UUID currentUserId
    ) {
        requireAdmin(workspaceId, currentUserId);
        GitRepository repository = requireRepository(repositoryId, workspaceId);
        outboxPublisher.publish(
                "GIT_REPOSITORY",
                repository.id(),
                OutboxEventTypes.GIT_REPOSITORY_DELETE_REQUESTED,
                Map.of(
                        "workspaceId", workspaceId.toString(),
                        "repositoryId", repositoryId.toString()
                )
        );
        gitRepository.deleteRepository(repositoryId);
    }

    public List<GitRepositoryFile> listFiles(
            UUID workspaceId,
            UUID repositoryId,
            UUID currentUserId
    ) {
        workspaceService.requireMembership(workspaceId, currentUserId);
        requireRepository(repositoryId, workspaceId);
        return gitRepository.findFilesByRepositoryId(repositoryId);
    }

    public RepositoryFilePageResult listFilePage(
            UUID workspaceId,
            UUID repositoryId,
            UUID currentUserId,
            String pathPrefix,
            boolean recursive,
            String cursor,
            int limit
    ) {
        workspaceService.requireMembership(workspaceId, currentUserId);
        GitRepository repository = requireRepository(repositoryId, workspaceId);
        requirePageLimit(limit);
        String prefix = normalizeOptionalPrefix(pathPrefix);
        String afterPath = decodeCursor(cursor);
        List<GitRepositoryFile> matches = gitRepository.findFilesByRepositoryId(repositoryId)
                .stream()
                .filter(file -> withinPrefix(file.path(), prefix, recursive))
                .sorted(Comparator.comparing(GitRepositoryFile::path))
                .filter(file -> afterPath == null || file.path().compareTo(afterPath) > 0)
                .limit((long) limit + 1)
                .toList();
        boolean hasMore = matches.size() > limit;
        List<GitRepositoryFile> page = hasMore ? matches.subList(0, limit) : matches;
        String nextCursor = hasMore && !page.isEmpty()
                ? encodeCursor(page.get(page.size() - 1).path()) : null;
        return new RepositoryFilePageResult(
                workspaceId, repositoryId, repository.lastSyncedCommit(), prefix, recursive,
                List.copyOf(page), nextCursor, hasMore
        );
    }

    public RepositoryChangePageResult listLatestChangeFilePage(
            UUID workspaceId,
            UUID repositoryId,
            UUID currentUserId,
            String cursor,
            int limit
    ) {
        workspaceService.requireMembership(workspaceId, currentUserId);
        requireRepository(repositoryId, workspaceId);
        requirePageLimit(limit);
        List<GitChange> changes = gitRepository.findChangesByRepositoryId(repositoryId);
        if (changes.isEmpty()) {
            return new RepositoryChangePageResult(
                    workspaceId, repositoryId, null, null, null,
                    List.of(), null, false
            );
        }
        GitChange latest = changes.get(0);
        String afterKey = decodeCursor(cursor);
        List<RepositoryChangePageResult.ChangedFile> matches =
                gitRepository.findDiffsByChangeId(latest.id()).stream()
                        .map(diff -> new RepositoryChangePageResult.ChangedFile(
                                diff.id(), diff.changeType(), diff.path(),
                                diff.oldPath(), diff.binaryFile()
                        ))
                        .sorted(Comparator
                                .comparing(RepositoryChangePageResult.ChangedFile::filePath)
                                .thenComparing(RepositoryChangePageResult.ChangedFile::diffId))
                        .filter(file -> afterKey == null || changeKey(file).compareTo(afterKey) > 0)
                        .limit((long) limit + 1)
                        .toList();
        boolean hasMore = matches.size() > limit;
        List<RepositoryChangePageResult.ChangedFile> page =
                hasMore ? matches.subList(0, limit) : matches;
        String nextCursor = hasMore && !page.isEmpty()
                ? encodeCursor(changeKey(page.get(page.size() - 1))) : null;
        return new RepositoryChangePageResult(
                workspaceId, repositoryId, latest.id(),
                latest.changeType().name(), latest.commitSha(),
                List.copyOf(page), nextCursor, hasMore
        );
    }

    public CodeBindingBatchQueryResult queryBindingsBatch(
            UUID workspaceId,
            UUID repositoryId,
            UUID currentUserId,
            List<String> filePaths
    ) {
        return queryBindingsBatch(
                workspaceId,
                repositoryId,
                currentUserId,
                filePaths,
                null,
                true,
                null
        );
    }

    public CodeBindingBatchQueryResult queryBindingsBatch(
            UUID workspaceId,
            UUID repositoryId,
            UUID currentUserId,
            List<String> filePaths,
            String revision,
            boolean includeLegacy,
            Integer maxBindings
    ) {
        workspaceService.requireMembership(workspaceId, currentUserId);
        requireRepository(repositoryId, workspaceId);
        if (filePaths == null || filePaths.isEmpty() || filePaths.size() > 100) {
            throw new InvalidCodeBindingException("filePaths 数量必须在 1 到 100 之间");
        }
        List<String> normalizedPaths = filePaths.stream()
                .map(this::normalizePath)
                .distinct()
                .sorted()
                .toList();
        String normalizedRevision = normalizeOptionalRevision(revision);
        List<CodeDocumentBinding> bindings =
                gitRepository.findBindingsByRepositoryId(repositoryId);
        List<CodeBindingBatchQueryResult.FileBindings> files = normalizedPaths.stream()
                .map(path -> {
                    BindingSelection selection = selectBindings(
                            bindings,
                            path,
                            normalizedRevision,
                            includeLegacy,
                            maxBindings
                    );
                    return new CodeBindingBatchQueryResult.FileBindings(
                            path,
                            selection.fileHasBindings(),
                            selection.bindings(),
                            selection.isTruncated(),
                            selection.omittedBindingCount()
                    );
                })
                .toList();
        return new CodeBindingBatchQueryResult(workspaceId, repositoryId, files);
    }

    public CodeMetadataBatchResult inspectCodeMetadata(
            UUID workspaceId,
            UUID repositoryId,
            UUID currentUserId,
            String revision,
            List<String> filePaths
    ) {
        workspaceService.requireMembership(workspaceId, currentUserId);
        GitRepository repository = requireRepository(repositoryId, workspaceId);
        if (revision == null || !revision.equalsIgnoreCase(repository.lastSyncedCommit())) {
            throw new InvalidCodeBindingException("Repository revision does not match");
        }
        if (filePaths == null || filePaths.isEmpty() || filePaths.size() > 100) {
            throw new InvalidCodeBindingException("filePaths must contain 1 to 100 paths");
        }
        Map<String, GitRepositoryFile> available = gitRepository
                .findFilesByRepositoryId(repositoryId)
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        GitRepositoryFile::path,
                        file -> file,
                        (left, right) -> left
                ));
        List<CodeMetadataBatchResult.FileMetadata> files = filePaths.stream()
                .map(this::normalizePath)
                .distinct()
                .sorted()
                .map(path -> {
                    GitRepositoryFile file = available.get(path);
                    if (file == null) {
                        return new CodeMetadataBatchResult.FileMetadata(
                                path, null, null, null, null, List.of(), List.of(),
                                List.of(), List.of(), List.of(), List.of(),
                                "FAILED", "FILE_NOT_FOUND"
                        );
                    }
                    return metadataInspector.inspect(file);
                })
                .toList();
        return new CodeMetadataBatchResult(
                workspaceId, repositoryId, repository.lastSyncedCommit(), files
        );
    }

    public GitRepositorySourceDetails getSource(
            UUID workspaceId,
            UUID repositoryId,
            UUID currentUserId,
            String filePath
    ) {
        workspaceService.requireMembership(workspaceId, currentUserId);
        GitRepository repository = requireRepository(repositoryId, workspaceId);
        String normalizedPath = normalizePath(filePath);
        GitRepositoryFile file = gitRepository.findFileByRepositoryIdAndPath(
                        repositoryId, normalizedPath
                )
                .orElseThrow(GitRepositoryFileNotFoundException::new);
        return new GitRepositorySourceDetails(
                repositoryId,
                repository.lastSyncedCommit(),
                file,
                gitRepository.findSymbolsByRepositoryId(
                        repositoryId, normalizedPath
                )
        );
    }

    public CodeGraphDetails getCodeGraph(
            UUID workspaceId,
            UUID repositoryId,
            UUID currentUserId,
            String filePath
    ) {
        workspaceService.requireMembership(workspaceId, currentUserId);
        requireRepository(repositoryId, workspaceId);
        String normalizedPath = filePath == null || filePath.isBlank()
                ? null : normalizePath(filePath);
        return new CodeGraphDetails(
                gitRepository.findSymbolsByRepositoryId(
                        repositoryId, normalizedPath
                ),
                gitRepository.findSymbolDependenciesByRepositoryId(
                        repositoryId, normalizedPath
                ),
                gitRepository.findFileDependenciesByRepositoryId(
                        repositoryId, normalizedPath
                )
        );
    }

    @Transactional
    public GitChangeDetails ingestChange(
            UUID workspaceId,
            UUID repositoryId,
            UUID currentUserId,
            IngestGitChangeCommand command
    ) {
        requireAdmin(workspaceId, currentUserId);
        GitRepository repository = requireRepository(repositoryId, workspaceId);
        var duplicate = gitRepository.findChangeByExternalIdentity(
                repository.id(), command.changeType(), command.externalId().trim()
        );
        if (duplicate.isPresent()) {
            GitChange existing = duplicate.get();
            return new GitChangeDetails(
                    existing,
                    gitRepository.findDiffsByChangeId(existing.id()),
                    true
            );
        }
        if (!command.commitSha().matches("[0-9a-fA-F]{7,64}")) {
            throw new InvalidCodeBindingException("commitSha 格式不合法");
        }
        Instant now = Instant.now();
        GitChange change = gitRepository.saveChange(new GitChange(
                UUID.randomUUID(), repositoryId, command.changeType(),
                command.externalId().trim(), command.title().trim(),
                command.commitSha().toLowerCase(Locale.ROOT), trimToNull(command.baseRef()),
                trimToNull(command.headRef()), trimToNull(command.authorName()),
                trimToNull(command.authorEmail()), command.authoredAt(),
                trimToNull(command.committerName()), trimToNull(command.committerEmail()),
                trimToNull(command.parentCommitSha()),
                trimToNull(command.webUrl()), command.occurredAt(), now
        ));
        List<GitFileDiff> diffs = command.files().stream()
                .map(file -> new GitFileDiff(
                        UUID.randomUUID(), change.id(), normalizePath(file.path()),
                        normalizeNullablePath(file.oldPath()), file.changeType(),
                        file.additions(), file.deletions(), file.binaryFile(),
                        trimToNull(file.patchExcerpt())
                ))
                .toList();
        gitRepository.saveDiffs(diffs);
        outboxPublisher.publish(
                "GIT_CHANGE",
                change.id(),
                OutboxEventTypes.GIT_CHANGE_SYNCED,
                Map.of(
                        "workspaceId", workspaceId.toString(),
                        "repositoryId", repositoryId.toString(),
                        "changeId", change.id().toString(),
                        "changeType", change.changeType().name(),
                        "commitSha", change.commitSha()
                )
        );
        return new GitChangeDetails(change, diffs, false);
    }

    public List<GitChangeDetails> listChanges(
            UUID workspaceId,
            UUID repositoryId,
            UUID currentUserId
    ) {
        workspaceService.requireMembership(workspaceId, currentUserId);
        requireRepository(repositoryId, workspaceId);
        return gitRepository.findChangesByRepositoryId(repositoryId).stream()
                .map(change -> new GitChangeDetails(
                        change,
                        gitRepository.findDiffsByChangeId(change.id()),
                        false
                ))
                .toList();
    }

    @Transactional(propagation = Propagation.NESTED)
    public CodeDocumentBinding createBinding(
            UUID documentId,
            UUID currentUserId,
            CreateCodeBindingCommand command
    ) {
        Document document = requireDocument(documentId);
        workspaceService.requireMembership(document.workspaceId(), currentUserId);
        requireRepository(command.repositoryId(), document.workspaceId());
        if (command.blockId() != null) {
            DocumentBlock block = blockRepository.findById(command.blockId())
                    .orElseThrow(DocumentBlockNotFoundException::new);
            if (!block.documentId().equals(documentId)) {
                throw new InvalidCodeBindingException("Block 不属于目标文档");
            }
        }
        String pattern = normalizePattern(command.pathPattern());
        CodeBindingAnchorValidator.ValidatedAnchor anchor =
                CodeBindingAnchorValidator.validate(
                        command.revision(),
                        command.anchorKind(),
                        command.symbolKey(),
                        command.startLine(),
                        command.endLine()
                );
        if (gitRepository.findExactBinding(
                command.repositoryId(),
                documentId,
                command.blockId(),
                pattern,
                anchor.revision(),
                anchor.anchorKind(),
                anchor.symbolKey(),
                anchor.startLine(),
                anchor.endLine()
        ).isPresent()) {
            throw new DuplicateCodeBindingException();
        }
        String targetKey = command.blockId() == null
                ? "DOCUMENT"
                : command.blockId().toString();
        try {
            return gitRepository.saveBinding(new CodeDocumentBinding(
                    UUID.randomUUID(), document.workspaceId(),
                    command.repositoryId(), documentId, command.blockId(),
                    targetKey, pattern, anchor.revision(),
                    anchor.anchorKind(), anchor.symbolKey(),
                    anchor.startLine(), anchor.endLine(),
                    currentUserId, Instant.now()
            ));
        } catch (DataIntegrityViolationException exception) {
            // The database identity is the final guard for concurrent requests
            // that pass the optimistic duplicate check at the same time.
            throw new DuplicateCodeBindingException();
        }
    }

    public List<CodeDocumentBinding> listBindings(
            UUID documentId,
            UUID currentUserId
    ) {
        return listBindings(documentId, currentUserId, null, true, null);
    }

    public List<CodeDocumentBinding> listBindings(
            UUID documentId,
            UUID currentUserId,
            String revision,
            boolean includeLegacy,
            UUID blockId
    ) {
        Document document = requireDocument(documentId);
        workspaceService.requireMembership(document.workspaceId(), currentUserId);
        String normalizedRevision = normalizeOptionalRevision(revision);
        return gitRepository.findBindingsByDocumentId(documentId).stream()
                .filter(binding -> blockId == null
                        || java.util.Objects.equals(blockId, binding.blockId()))
                .filter(binding -> matchesRevision(
                        binding, normalizedRevision, includeLegacy
                ))
                .sorted(bindingComparator(normalizedRevision))
                .toList();
    }

    public java.util.Optional<CodeDocumentBinding> findExactBinding(
            UUID documentId,
            UUID currentUserId,
            CreateCodeBindingCommand command
    ) {
        Document document = requireDocument(documentId);
        workspaceService.requireMembership(document.workspaceId(), currentUserId);
        requireRepository(command.repositoryId(), document.workspaceId());
        String pattern = normalizePattern(command.pathPattern());
        CodeBindingAnchorValidator.ValidatedAnchor anchor =
                CodeBindingAnchorValidator.validate(
                        command.revision(),
                        command.anchorKind(),
                        command.symbolKey(),
                        command.startLine(),
                        command.endLine()
                );
        return gitRepository.findExactBinding(
                command.repositoryId(),
                documentId,
                command.blockId(),
                pattern,
                anchor.revision(),
                anchor.anchorKind(),
                anchor.symbolKey(),
                anchor.startLine(),
                anchor.endLine()
        );
    }

    @Transactional
    public java.util.Optional<CodeDocumentBinding> findBindingForUpdate(
            UUID workspaceId,
            UUID bindingId,
            UUID currentUserId
    ) {
        workspaceService.requireMembership(workspaceId, currentUserId);
        return gitRepository.findBindingByIdForUpdate(bindingId);
    }

    public CodeBindingQueryResult queryBindings(
            UUID workspaceId,
            UUID repositoryId,
            UUID currentUserId,
            String filePath,
            Integer maxBindings
    ) {
        return queryBindings(
                workspaceId,
                repositoryId,
                currentUserId,
                filePath,
                null,
                true,
                maxBindings
        );
    }

    public CodeBindingQueryResult queryBindings(
            UUID workspaceId,
            UUID repositoryId,
            UUID currentUserId,
            String filePath,
            String revision,
            boolean includeLegacy,
            Integer maxBindings
    ) {
        workspaceService.requireMembership(workspaceId, currentUserId);
        requireRepository(repositoryId, workspaceId);

        String normalizedPath = normalizePath(filePath);
        String normalizedRevision = normalizeOptionalRevision(revision);

        List<CodeDocumentBinding> allRepoBindings = gitRepository.findBindingsByRepositoryId(repositoryId);
        BindingSelection selection = selectBindings(
                allRepoBindings,
                normalizedPath,
                normalizedRevision,
                includeLegacy,
                maxBindings
        );

        return new CodeBindingQueryResult(
                workspaceId,
                repositoryId,
                filePath,
                selection.fileHasBindings(),
                selection.bindings(),
                selection.isTruncated(),
                selection.omittedBindingCount()
        );
    }

    @Transactional
    public void deleteBinding(UUID bindingId, UUID currentUserId) {
        CodeDocumentBinding binding = gitRepository.findBindingById(bindingId)
                .orElseThrow(CodeBindingNotFoundException::new);
        workspaceService.requireMembership(binding.workspaceId(), currentUserId);
        if (!gitRepository.deleteBinding(bindingId)) {
            throw new CodeBindingNotFoundException();
        }
    }

    public List<AffectedCodeDocument> findAffectedDocuments(
            UUID workspaceId,
            UUID changeId,
            UUID currentUserId
    ) {
        workspaceService.requireMembership(workspaceId, currentUserId);
        GitChange change = gitRepository.findChangeById(changeId)
                .orElseThrow(GitChangeNotFoundException::new);
        requireRepository(change.repositoryId(), workspaceId);
        List<String> changedPaths = gitRepository.findDiffsByChangeId(changeId)
                .stream().flatMap(diff -> {
                    LinkedHashSet<String> paths = new LinkedHashSet<>();
                    paths.add(diff.path());
                    if (diff.oldPath() != null) {
                        paths.add(diff.oldPath());
                    }
                    return paths.stream();
                }).toList();
        List<AffectedCodeDocument> result = new ArrayList<>();
        for (CodeDocumentBinding binding
                : gitRepository.findBindingsByRepositoryId(change.repositoryId())) {
            List<String> matches = changedPaths.stream()
                    .filter(path -> matches(binding.pathPattern(), path))
                    .toList();
            if (!matches.isEmpty()) {
                result.add(new AffectedCodeDocument(
                        binding.id(), binding.documentId(), binding.blockId(),
                        binding.pathPattern(), matches
                ));
            }
        }
        return result;
    }

    private GitRepository requireRepository(UUID repositoryId, UUID workspaceId) {
        GitRepository repository = gitRepository.findRepositoryById(repositoryId)
                .orElseThrow(GitRepositoryNotFoundException::new);
        if (!repository.workspaceId().equals(workspaceId)) {
            throw new GitRepositoryNotFoundException();
        }
        return repository;
    }

    private Document requireDocument(UUID documentId) {
        return documentRepository.findById(documentId)
                .orElseThrow(DocumentNotFoundException::new);
    }

    private void requireAdmin(UUID workspaceId, UUID userId) {
        WorkspaceMember member = workspaceService.requireMembership(workspaceId, userId);
        if (!permissionPolicy.isAdmin(member)) {
            throw new WorkspaceAccessDeniedException();
        }
    }

    private String normalizeRemoteUrl(String value) {
        String trimmed = value.trim();
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException exception) {
            throw new InvalidCodeBindingException("remoteUrl 格式不合法");
        }
        if (uri.getScheme() == null || uri.getHost() == null
                || !(uri.getScheme().equals("https") || uri.getScheme().equals("http"))) {
            throw new InvalidCodeBindingException("remoteUrl 必须是 HTTP(S) 地址");
        }
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private String normalizePattern(String value) {
        String pattern = normalizePath(value);
        if (pattern.contains("*") && !(pattern.endsWith("/**") || pattern.startsWith("**/*."))) {
            throw new InvalidCodeBindingException("仅支持精确路径、目录/** 或 **/*.扩展名");
        }
        return pattern;
    }

    private String normalizeNullablePath(String value) {
        return value == null || value.isBlank() ? null : normalizePath(value);
    }

    private String normalizePath(String value) {
        String normalized = com.devcollab.knowledgecore.common.util.RepositoryPathValidator.normalize(value);
        com.devcollab.knowledgecore.common.util.RepositoryPathValidator.validate(value, "代码路径必须是仓库内相对路径");
        return normalized;
    }

    private String normalizeOptionalPrefix(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = normalizePath(value);
        return normalized.endsWith("/")
                ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    private boolean withinPrefix(String path, String prefix, boolean recursive) {
        String remainder;
        if (prefix.isEmpty()) {
            remainder = path;
        } else {
            String directoryPrefix = prefix + "/";
            if (!path.startsWith(directoryPrefix)) {
                return false;
            }
            remainder = path.substring(directoryPrefix.length());
        }
        return recursive || !remainder.contains("/");
    }

    private void requirePageLimit(int limit) {
        if (limit < 1 || limit > 200) {
            throw new InvalidCodeBindingException("limit 必须在 1 到 200 之间");
        }
    }

    private String encodeCursor(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return new String(
                    Base64.getUrlDecoder().decode(cursor),
                    StandardCharsets.UTF_8
            );
        } catch (IllegalArgumentException exception) {
            throw new InvalidCodeBindingException("cursor 格式不合法");
        }
    }

    private String changeKey(RepositoryChangePageResult.ChangedFile file) {
        return file.filePath() + "\0" + file.diffId();
    }

    private boolean matches(String pattern, String path) {
        if (pattern.endsWith("/**")) {
            return path.startsWith(pattern.substring(0, pattern.length() - 2));
        }
        if (pattern.startsWith("**/*.")) {
            return path.endsWith(pattern.substring(4));
        }
        return pattern.equals(path);
    }

    private BindingSelection selectBindings(
            List<CodeDocumentBinding> bindings,
            String normalizedPath,
            String revision,
            boolean includeLegacy,
            Integer maxBindings
    ) {
        java.util.Set<UUID> seenBindingIds = new java.util.HashSet<>();
        List<CodeDocumentBinding> matching = bindings.stream()
                .filter(binding -> matches(binding.pathPattern(), normalizedPath))
                .filter(binding -> matchesRevision(binding, revision, includeLegacy))
                .filter(binding -> seenBindingIds.add(binding.id()))
                .toList();
        List<CodeBindingQueryItem> ordered = matching.stream()
                .map(this::toQueryItem)
                .sorted(queryItemComparator(revision))
                .toList();

        if (maxBindings != null
                && (maxBindings < 1
                || maxBindings > DEFAULT_MAX_BINDINGS_PER_FILE)) {
            throw new InvalidCodeBindingException(
                    "maxBindings 必须在 1 到 1000 之间"
            );
        }
        int effectiveMax = maxBindings == null
                ? DEFAULT_MAX_BINDINGS_PER_FILE
                : maxBindings;
        boolean truncated = ordered.size() > effectiveMax;
        int omittedBindingCount = truncated ? ordered.size() - effectiveMax : 0;
        List<CodeBindingQueryItem> result = truncated
                ? ordered.subList(0, effectiveMax)
                : ordered;
        return new BindingSelection(
                !matching.isEmpty(),
                result,
                truncated,
                omittedBindingCount
        );
    }

    private CodeBindingQueryItem toQueryItem(CodeDocumentBinding binding) {
        String documentTitle = documentRepository.findById(binding.documentId())
                .map(Document::title)
                .orElse(null);
        return new CodeBindingQueryItem(
                binding.id(),
                binding.workspaceId(),
                binding.repositoryId(),
                binding.revision(),
                binding.anchorKind(),
                binding.symbolKey(),
                binding.startLine(),
                binding.endLine(),
                binding.documentId(),
                binding.blockId(),
                binding.targetKey(),
                binding.pathPattern(),
                documentTitle
        );
    }

    private boolean matchesRevision(
            CodeDocumentBinding binding,
            String revision,
            boolean includeLegacy
    ) {
        if (revision != null) {
            return revision.equals(binding.revision())
                    || (includeLegacy && binding.revision() == null);
        }
        return includeLegacy || binding.revision() != null;
    }

    private Comparator<CodeDocumentBinding> bindingComparator(String revision) {
        return Comparator
                .comparingInt((CodeDocumentBinding binding) -> {
                    if (revision != null && revision.equals(binding.revision())) {
                        return 0;
                    }
                    return binding.revision() == null ? 1 : 0;
                })
                .thenComparingInt(binding -> binding.blockId() == null ? 1 : 0)
                .thenComparingInt(binding -> switch (binding.anchorKind()) {
                    case SYMBOL -> 0;
                    case RANGE -> 1;
                    case FILE -> 2;
                })
                .thenComparing(CodeDocumentBinding::createdAt)
                .thenComparing(CodeDocumentBinding::id);
    }

    private Comparator<CodeBindingQueryItem> queryItemComparator(String revision) {
        return Comparator
                .comparingInt((CodeBindingQueryItem item) -> {
                    if (revision != null && revision.equals(item.revision())) {
                        return 0;
                    }
                    return item.revision() == null ? 1 : 0;
                })
                .thenComparingInt(item -> item.blockId() == null ? 1 : 0)
                .thenComparingInt(item -> switch (item.anchorKind()) {
                    case SYMBOL -> 0;
                    case RANGE -> 1;
                    case FILE -> 2;
                })
                .thenComparing(
                        CodeBindingQueryItem::documentTitle,
                        Comparator.nullsLast(String::compareToIgnoreCase)
                )
                .thenComparing(CodeBindingQueryItem::documentId)
                .thenComparing(
                        CodeBindingQueryItem::blockId,
                        Comparator.nullsLast(Comparator.naturalOrder())
                )
                .thenComparing(CodeBindingQueryItem::bindingId);
    }

    private String normalizeOptionalRevision(String revision) {
        if (revision == null) {
            return null;
        }
        String normalized = revision.trim();
        if (normalized.isEmpty()) {
            throw new InvalidCodeBindingException("revision 不能为空白字符串");
        }
        return normalized;
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record BindingSelection(
            boolean fileHasBindings,
            List<CodeBindingQueryItem> bindings,
            boolean isTruncated,
            int omittedBindingCount
    ) {
    }

    private void publishSyncRequest(GitRepository repository) {
        outboxPublisher.publish(
                "GIT_REPOSITORY",
                repository.id(),
                OutboxEventTypes.GIT_REPOSITORY_SYNC_REQUESTED,
                Map.of(
                        "workspaceId", repository.workspaceId().toString(),
                        "repositoryId", repository.id().toString(),
                        "remoteUrl", repository.remoteUrl(),
                        "defaultBranch", repository.defaultBranch()
                )
        );
    }
}
