package com.devcollab.knowledgecore.git.application;

import com.devcollab.knowledgecore.common.outbox.application.OutboxEventPublisher;
import com.devcollab.knowledgecore.document.core.domain.DocumentBlockRepository;
import com.devcollab.knowledgecore.document.core.domain.DocumentRepository;
import com.devcollab.knowledgecore.git.domain.GitKnowledgeRepository;
import com.devcollab.knowledgecore.git.domain.GitProvider;
import com.devcollab.knowledgecore.git.domain.GitRepository;
import com.devcollab.knowledgecore.git.domain.GitRepositoryFile;
import com.devcollab.knowledgecore.git.domain.GitRepositoryStatus;
import com.devcollab.knowledgecore.workspace.application.WorkspaceApplicationService;
import com.devcollab.knowledgecore.workspace.application.WorkspacePermissionPolicy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CodeMetadataCacheApplicationServiceTests {

    private final UUID workspaceId = UUID.randomUUID();
    private final UUID repositoryId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-08-10T00:00:00Z");

    @Test
    void reusesInspectorResultWhenCacheIsEnabled() {
        GitKnowledgeRepository repository = repositoryWith(file("src/App.java", "blob-a"));
        CountingCodeMetadataInspector inspector = new CountingCodeMetadataInspector();
        GitKnowledgeApplicationService service = service(
                repository,
                inspector,
                new CodeMetadataCacheProperties(true, 16)
        );

        service.inspectCodeMetadata(
                workspaceId, repositoryId, userId, "abc123", List.of("src/App.java")
        );
        service.inspectCodeMetadata(
                workspaceId, repositoryId, userId, "abc123", List.of("src/App.java")
        );

        assertThat(inspector.count()).isEqualTo(1);
    }

    @Test
    void callsInspectorEveryTimeWhenCacheIsDisabled() {
        GitKnowledgeRepository repository = repositoryWith(file("src/App.java", "blob-a"));
        CountingCodeMetadataInspector inspector = new CountingCodeMetadataInspector();
        GitKnowledgeApplicationService service = service(
                repository,
                inspector,
                new CodeMetadataCacheProperties(false, 16)
        );

        service.inspectCodeMetadata(
                workspaceId, repositoryId, userId, "abc123", List.of("src/App.java")
        );
        service.inspectCodeMetadata(
                workspaceId, repositoryId, userId, "abc123", List.of("src/App.java")
        );

        assertThat(inspector.count()).isEqualTo(2);
    }

    private GitKnowledgeApplicationService service(
            GitKnowledgeRepository repository,
            CodeMetadataInspector inspector,
            CodeMetadataCacheProperties properties
    ) {
        return new GitKnowledgeApplicationService(
                repository,
                mock(WorkspaceApplicationService.class),
                mock(WorkspacePermissionPolicy.class),
                mock(DocumentRepository.class),
                mock(DocumentBlockRepository.class),
                mock(OutboxEventPublisher.class),
                inspector,
                null,
                properties
        );
    }

    private GitKnowledgeRepository repositoryWith(GitRepositoryFile file) {
        GitKnowledgeRepository repository = mock(GitKnowledgeRepository.class);
        when(repository.findRepositoryById(repositoryId)).thenReturn(Optional.of(
                new GitRepository(
                        repositoryId,
                        workspaceId,
                        "repo",
                        GitProvider.GITHUB,
                        "https://example.test/repo",
                        "main",
                        userId,
                        now,
                        now,
                        GitRepositoryStatus.READY,
                        "abc123",
                        now,
                        null
                )
        ));
        when(repository.findFilesByRepositoryId(repositoryId)).thenReturn(List.of(file));
        return repository;
    }

    private GitRepositoryFile file(String path, String blobSha) {
        return new GitRepositoryFile(
                UUID.randomUUID(),
                repositoryId,
                path,
                blobSha,
                18,
                "Java",
                "class App {}"
        );
    }

    private static class CountingCodeMetadataInspector extends CodeMetadataInspector {
        private final AtomicInteger count = new AtomicInteger();

        @Override
        public CodeMetadataBatchResult.FileMetadata inspect(GitRepositoryFile file) {
            count.incrementAndGet();
            return super.inspect(file);
        }

        int count() {
            return count.get();
        }
    }
}
