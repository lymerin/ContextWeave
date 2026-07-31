package com.devcollab.knowledgecore.git.application;

import com.devcollab.knowledgecore.common.outbox.application.OutboxEventPublisher;
import com.devcollab.knowledgecore.document.core.domain.DocumentBlockRepository;
import com.devcollab.knowledgecore.document.core.domain.DocumentRepository;
import com.devcollab.knowledgecore.git.application.exception.GitRepositoryNotFoundException;
import com.devcollab.knowledgecore.git.application.exception.InvalidCodeBindingException;
import com.devcollab.knowledgecore.git.domain.*;
import com.devcollab.knowledgecore.workspace.application.WorkspaceApplicationService;
import com.devcollab.knowledgecore.workspace.application.WorkspacePermissionPolicy;
import com.devcollab.knowledgecore.workspace.application.exception.WorkspaceAccessDeniedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class RepositoryDiscoveryApplicationServiceTests {

    private final GitKnowledgeRepository repository = mock(GitKnowledgeRepository.class);
    private final WorkspaceApplicationService workspaceService = mock(WorkspaceApplicationService.class);
    private GitKnowledgeApplicationService service;
    private UUID workspaceId;
    private UUID repositoryId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        service = new GitKnowledgeApplicationService(
                repository, workspaceService, mock(WorkspacePermissionPolicy.class),
                mock(DocumentRepository.class), mock(DocumentBlockRepository.class),
                mock(OutboxEventPublisher.class), new CodeMetadataInspector()
        );
        workspaceId = UUID.randomUUID();
        repositoryId = UUID.randomUUID();
        userId = UUID.randomUUID();
        Instant now = Instant.parse("2026-07-28T00:00:00Z");
        when(repository.findRepositoryById(repositoryId)).thenReturn(Optional.of(
                new GitRepository(repositoryId, workspaceId, "repo", GitProvider.GITHUB,
                        "https://example.test/repo", "main", userId, now, now,
                        GitRepositoryStatus.READY, "abc123", now, null)
        ));
    }

    @Test
    void listsRootFilesInStablePagesWithoutDuplicates() {
        when(repository.findFilesByRepositoryId(repositoryId)).thenReturn(List.of(
                file("src/z/Z.java"), file("README.md"), file("src/a/A.java")
        ));
        RepositoryFilePageResult first = service.listFilePage(
                workspaceId, repositoryId, userId, "", true, null, 2);
        RepositoryFilePageResult second = service.listFilePage(
                workspaceId, repositoryId, userId, "", true, first.nextCursor(), 2);
        assertThat(first.files()).extracting(GitRepositoryFile::path)
                .containsExactly("README.md", "src/a/A.java");
        assertThat(second.files()).extracting(GitRepositoryFile::path)
                .containsExactly("src/z/Z.java");
        assertThat(first.hasMore()).isTrue();
        assertThat(second.hasMore()).isFalse();
    }

    @Test
    void filtersDirectoryAndHonorsNonRecursiveMode() {
        when(repository.findFilesByRepositoryId(repositoryId)).thenReturn(List.of(
                file("src/App.java"), file("src/main/Nested.java"), file("test/Test.java")
        ));
        RepositoryFilePageResult result = service.listFilePage(
                workspaceId, repositoryId, userId, "src", false, null, 20);
        assertThat(result.files()).extracting(GitRepositoryFile::path)
                .containsExactly("src/App.java");
    }

    @Test
    void emptyRepositoryReturnsSuccessfulEmptyPage() {
        when(repository.findFilesByRepositoryId(repositoryId)).thenReturn(List.of());
        RepositoryFilePageResult result = service.listFilePage(
                workspaceId, repositoryId, userId, "", true, null, 20);
        assertThat(result.files()).isEmpty();
        assertThat(result.nextCursor()).isNull();
        assertThat(result.hasMore()).isFalse();
    }

    @Test
    void rejectsTraversalAndAbsolutePaths() {
        assertThatThrownBy(() -> service.listFilePage(
                workspaceId, repositoryId, userId, "../secret", true, null, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.listFilePage(
                workspaceId, repositoryId, userId, "C:\\secret", true, null, 20))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enforcesWorkspaceMembershipAndRepositoryOwnership() {
        doThrow(new WorkspaceAccessDeniedException())
                .when(workspaceService).requireMembership(workspaceId, userId);
        assertThatThrownBy(() -> service.listFilePage(
                workspaceId, repositoryId, userId, "", true, null, 20))
                .isInstanceOf(WorkspaceAccessDeniedException.class);

        UUID otherWorkspace = UUID.randomUUID();
        when(repository.findRepositoryById(repositoryId)).thenReturn(Optional.of(
                new GitRepository(repositoryId, otherWorkspace, "repo", GitProvider.GITHUB,
                        "https://example.test/repo", "main", userId, Instant.now(),
                        Instant.now(), GitRepositoryStatus.READY, null, null, null)
        ));
        assertThatThrownBy(() -> service.listFilePage(
                workspaceId, repositoryId, UUID.randomUUID(), "", true, null, 20))
                .isInstanceOf(GitRepositoryNotFoundException.class);
    }

    @Test
    void listsLatestPersistedChangeIncludingRenameAndDeleteWithoutReadingFiles() {
        GitChange change = change();
        when(repository.findChangesByRepositoryId(repositoryId)).thenReturn(List.of(change));
        when(repository.findDiffsByChangeId(change.id())).thenReturn(List.of(
                diff(change.id(), "src/Old.java", null, GitFileChangeType.DELETED),
                diff(change.id(), "src/New.java", "src/Previous.java", GitFileChangeType.RENAMED)
        ));
        RepositoryChangePageResult result = service.listLatestChangeFilePage(
                workspaceId, repositoryId, userId, null, 20);
        assertThat(result.files()).extracting(RepositoryChangePageResult.ChangedFile::status)
                .containsExactly(GitFileChangeType.RENAMED, GitFileChangeType.DELETED);
        assertThat(result.files().get(0).oldPath()).isEqualTo("src/Previous.java");
        verify(repository, never()).findFilesByRepositoryId(repositoryId);
    }

    @Test
    void noPersistedChangesReturnsSuccessfulEmptyPage() {
        when(repository.findChangesByRepositoryId(repositoryId)).thenReturn(List.of());
        RepositoryChangePageResult result = service.listLatestChangeFilePage(
                workspaceId, repositoryId, userId, null, 20);
        assertThat(result.files()).isEmpty();
        assertThat(result.changeId()).isNull();
    }

    @Test
    void batchBindingsUsesOneRepositoryQueryAndPreservesManyToMany() {
        UUID documentId = UUID.randomUUID();
        UUID secondDocumentId = UUID.randomUUID();
        CodeDocumentBinding first = binding("src/A.java", documentId);
        CodeDocumentBinding second = binding("src/A.java", secondDocumentId);
        CodeDocumentBinding third = binding("src/B.java", documentId);
        when(repository.findBindingsByRepositoryId(repositoryId))
                .thenReturn(List.of(first, second, third, first));
        CodeBindingBatchQueryResult result = service.queryBindingsBatch(
                workspaceId, repositoryId, userId,
                List.of("src/B.java", "src/A.java", "src/A.java", "src/Empty.java"));
        assertThat(result.files()).extracting(CodeBindingBatchQueryResult.FileBindings::filePath)
                .containsExactly("src/A.java", "src/B.java", "src/Empty.java");
        assertThat(result.files().get(0).bindings()).hasSize(2);
        assertThat(result.files().get(1).bindings()).hasSize(1);
        assertThat(result.files().get(2).bindings()).isEmpty();
        verify(repository, times(1)).findBindingsByRepositoryId(repositoryId);
    }

    @Test
    void batchBindingsRejectsEmptyAndOversizedRequests() {
        assertThatThrownBy(() -> service.queryBindingsBatch(
                workspaceId, repositoryId, userId, List.of()))
                .isInstanceOf(InvalidCodeBindingException.class);
        assertThatThrownBy(() -> service.queryBindingsBatch(
                workspaceId, repositoryId, userId,
                java.util.stream.IntStream.range(0, 101)
                        .mapToObj(index -> "src/F" + index + ".java").toList()))
                .isInstanceOf(InvalidCodeBindingException.class);
    }

    private GitRepositoryFile file(String path) {
        return new GitRepositoryFile(
                UUID.randomUUID(), repositoryId, path, "blob", 10, "Java", "content");
    }

    private GitChange change() {
        Instant now = Instant.parse("2026-07-28T00:00:00Z");
        return new GitChange(UUID.randomUUID(), repositoryId, GitChangeType.COMMIT,
                "abc123", "change", "abc123", "main", "main", "author", null,
                now, "committer", null, "parent", null, now, now);
    }

    private GitFileDiff diff(UUID changeId, String path, String oldPath, GitFileChangeType type) {
        return new GitFileDiff(
                UUID.randomUUID(), changeId, path, oldPath, type, 1, 1, false, null);
    }

    private CodeDocumentBinding binding(String path, UUID documentId) {
        return new CodeDocumentBinding(
                UUID.randomUUID(), workspaceId, repositoryId, documentId,
                UUID.randomUUID(), path, userId,
                Instant.parse("2026-07-28T00:00:00Z"));
    }
}
