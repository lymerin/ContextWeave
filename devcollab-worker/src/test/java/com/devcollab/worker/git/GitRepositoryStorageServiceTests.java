package com.devcollab.worker.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GitRepositoryStorageServiceTests {

    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Path.of("target", "git-storage-tests", UUID.randomUUID().toString())
                .toAbsolutePath().normalize();
        Files.createDirectories(tempDir);
    }

    @AfterEach
    void cleanUp() throws Exception {
        if (!Files.exists(tempDir)) return;
        try (var paths = Files.walk(tempDir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.setAttribute(path, "dos:readonly", false);
                } catch (UnsupportedOperationException ignored) {
                    // Non-Windows file system.
                }
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void deletesOnlyRepositoryUuidDirectory() throws Exception {
        GitRepositoryProjectionStore store = mock(GitRepositoryProjectionStore.class);
        GitRepositoryStorageService service = service(store);
        UUID workspaceId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        Path repository = tempDir.resolve(workspaceId.toString())
                .resolve(repositoryId.toString()).resolve("repository");
        Files.createDirectories(repository);
        Path trackedFile = repository.resolve("README.md");
        Files.writeString(trackedFile, "hello");
        Files.setAttribute(trackedFile, "dos:readonly", true);
        Path neighbor = tempDir.resolve("keep.txt");
        Files.writeString(neighbor, "keep");

        service.delete(workspaceId, repositoryId);

        assertThat(repository.getParent()).doesNotExist();
        assertThat(neighbor).exists();
    }

    @Test
    void deletesEmptyWorkspaceDirectoryAfterLastRepository() throws Exception {
        GitRepositoryProjectionStore store = mock(GitRepositoryProjectionStore.class);
        GitRepositoryStorageService service = service(store);
        UUID workspaceId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        Path workspaceDirectory = tempDir.resolve(workspaceId.toString());
        Path repositoryDirectory = workspaceDirectory
                .resolve(repositoryId.toString())
                .resolve("repository");
        Files.createDirectories(repositoryDirectory);
        Files.writeString(repositoryDirectory.resolve("README.md"), "hello");

        service.delete(workspaceId, repositoryId);

        assertThat(workspaceDirectory).doesNotExist();
    }

    @Test
    void keepsOtherWorkspaceAndRepositoryDirectories() throws Exception {
        GitRepositoryProjectionStore store = mock(GitRepositoryProjectionStore.class);
        GitRepositoryStorageService service = service(store);
        UUID workspaceId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID otherRepositoryId = UUID.randomUUID();
        UUID otherWorkspaceId = UUID.randomUUID();
        Path deletedRepository = tempDir.resolve(workspaceId.toString())
                .resolve(repositoryId.toString()).resolve("repository");
        Path keptRepository = tempDir.resolve(workspaceId.toString())
                .resolve(otherRepositoryId.toString()).resolve("repository");
        Path otherWorkspaceRepository = tempDir.resolve(otherWorkspaceId.toString())
                .resolve(UUID.randomUUID().toString()).resolve("repository");
        Files.createDirectories(deletedRepository);
        Files.createDirectories(keptRepository);
        Files.createDirectories(otherWorkspaceRepository);

        service.delete(workspaceId, repositoryId);

        assertThat(deletedRepository.getParent()).doesNotExist();
        assertThat(keptRepository).exists();
        assertThat(otherWorkspaceRepository).exists();
    }

    @Test
    void rejectsNonAllowlistedRemoteAndRecordsFailure() {
        GitRepositoryProjectionStore store = mock(GitRepositoryProjectionStore.class);
        UUID repositoryId = UUID.randomUUID();
        when(store.markSyncing(repositoryId)).thenReturn(true);
        GitRepositoryStorageService service = service(store);

        assertThatThrownBy(() -> service.synchronize(
                UUID.randomUUID(), repositoryId,
                "https://internal.example/repository.git", "main"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("synchronization failed");

        verify(store).markFailed(
                repositoryId,
                "Only public HTTPS repositories on allowed hosts are supported"
        );
    }

    @Test
    void projectsCommitIdentitiesTextStatisticsBinaryAndPatch() throws Exception {
        GitRepositoryProjectionStore store = mock(GitRepositoryProjectionStore.class);
        GitRepositoryStorageService service = service(store);
        Path repositoryDirectory = tempDir.resolve("fixture");
        Files.createDirectories(repositoryDirectory);
        try (Repository repository = FileRepositoryBuilder.create(
                repositoryDirectory.resolve(".git").toFile()
        )) {
            repository.create();
            try (Git git = new Git(repository)) {
            PersonIdent firstAuthor = new PersonIdent(
                    "Alice Author", "alice@example.com",
                    Instant.parse("2026-07-20T10:00:00Z"), ZoneOffset.UTC
            );
            PersonIdent firstCommitter = new PersonIdent(
                    "Build Bot", "bot@example.com",
                    Instant.parse("2026-07-20T10:01:00Z"), ZoneOffset.UTC
            );
            Files.writeString(repositoryDirectory.resolve("app.txt"), "one\ntwo\n");
            Files.writeString(repositoryDirectory.resolve("old.txt"), "alpha\nbeta\ngamma\n");
            Files.writeString(repositoryDirectory.resolve("delete.txt"), "gone\nagain\n");
            Files.write(repositoryDirectory.resolve("image.bin"), new byte[]{0, 1, 2});
            git.add().addFilepattern(".").call();
            var first = git.commit().setMessage("initial")
                    .setAuthor(firstAuthor).setCommitter(firstCommitter).call();

            Files.writeString(
                    repositoryDirectory.resolve("app.txt"),
                    "one changed\ntwo\nthree\n"
            );
            Files.move(repositoryDirectory.resolve("old.txt"),
                    repositoryDirectory.resolve("renamed.txt"));
            Files.delete(repositoryDirectory.resolve("delete.txt"));
            Files.writeString(repositoryDirectory.resolve("added.txt"), "new\nfile\n");
            Files.write(repositoryDirectory.resolve("image.bin"), new byte[]{0, 9, 2, 3});
            git.rm().addFilepattern("old.txt").addFilepattern("delete.txt").call();
            git.add().addFilepattern(".").call();

            PersonIdent author = new PersonIdent(
                    "Carol Coder", "carol@example.com",
                    Instant.parse("2026-07-21T08:30:00Z"), ZoneOffset.UTC
            );
            PersonIdent committer = new PersonIdent(
                    "Merge Bot", "merge-bot@example.com",
                    Instant.parse("2026-07-21T08:31:00Z"), ZoneOffset.UTC
            );
            git.commit().setMessage("x".repeat(550))
                    .setAuthor(author).setCommitter(committer).call();

            GitCommitProjection projection = service.scanCommits(
                    git, git.getRepository()
            ).getFirst();
            Map<String, GitDiffProjection> files = projection.files().stream()
                    .collect(Collectors.toMap(GitDiffProjection::path, value -> value));

            assertThat(projection.authorName()).isEqualTo("Carol Coder");
            assertThat(projection.authorEmail()).isEqualTo("carol@example.com");
            assertThat(projection.committerName()).isEqualTo("Merge Bot");
            assertThat(projection.committerEmail()).isEqualTo("merge-bot@example.com");
            assertThat(projection.parentCommitSha()).isEqualTo(first.name());
            assertThat(projection.title()).hasSize(500);
            assertThat(files.get("app.txt").additions()).isEqualTo(2);
            assertThat(files.get("app.txt").deletions()).isEqualTo(1);
            assertThat(files.get("app.txt").patchExcerpt())
                    .contains("-one", "+one changed");
            assertThat(files.get("added.txt").additions()).isEqualTo(2);
            assertThat(files.get("delete.txt").deletions()).isEqualTo(2);
            assertThat(files.get("renamed.txt").changeType()).isEqualTo("RENAMED");
            assertThat(files.get("renamed.txt").oldPath()).isEqualTo("old.txt");
            assertThat(files.get("image.bin").binaryFile()).isTrue();
            assertThat(files.get("image.bin").patchExcerpt()).isNull();
            }
        }
    }

    @Test
    void projectsReadableSourceButSkipsBinaryAndOversizedFiles() throws Exception {
        GitRepositoryProjectionStore store = mock(GitRepositoryProjectionStore.class);
        when(store.loadFileFingerprints(any())).thenReturn(Map.of());
        GitRepositoryStorageService service = service(store);
        Path repositoryDirectory = tempDir.resolve("source-fixture");
        Files.createDirectories(repositoryDirectory);
        try (Repository repository = FileRepositoryBuilder.create(
                repositoryDirectory.resolve(".git").toFile()
        )) {
            repository.create();
            try (Git git = new Git(repository)) {
                Files.writeString(
                        repositoryDirectory.resolve("OrderService.java"),
                        "class OrderService { void createOrder() {} }\n"
                );
                Files.write(
                        repositoryDirectory.resolve("Binary.java"),
                        new byte[]{'c', 'l', 'a', 's', 's', 0, 'X'}
                );
                Files.writeString(
                        repositoryDirectory.resolve("Huge.java"),
                        "x".repeat(512 * 1024 + 1)
                );
                Files.writeString(repositoryDirectory.resolve("README.md"), "# Read me\n");
                git.add().addFilepattern(".").call();
                git.commit().setMessage("source fixture").call();

                Map<String, GitRepositoryFileProjection> projected = service
                        .scanFiles(repository, UUID.randomUUID()).changed().stream()
                        .collect(Collectors.toMap(
                                GitRepositoryFileProjection::path,
                                value -> value
                        ));

                assertThat(projected.get("OrderService.java").contentText())
                        .contains("createOrder");
                assertThat(projected.get("README.md").contentText())
                        .isEqualTo("# Read me\n");
                assertThat(projected.get("Binary.java").contentText()).isNull();
                assertThat(projected.get("Huge.java").contentText()).isNull();
            }
        }
    }

    @Test
    void unchangedProjectionSkipsOnlyWhenHeadAndVersionMatch() {
        int version = GitRepositoryProjectionStore.CURRENT_PROJECTION_VERSION;
        assertThat(GitRepositoryStorageService.isUnchangedProjection(
                "abc123", null, version)).isFalse();
        assertThat(GitRepositoryStorageService.isUnchangedProjection(
                null, "abc123", version)).isFalse();
        assertThat(GitRepositoryStorageService.isUnchangedProjection(
                "abc123", "abc123", null)).isFalse();
        assertThat(GitRepositoryStorageService.isUnchangedProjection(
                "abc123", "abc123", version - 1)).isFalse();
        assertThat(GitRepositoryStorageService.isUnchangedProjection(
                "abc123", "abc124", version)).isFalse();
        assertThat(GitRepositoryStorageService.isUnchangedProjection(
                "abc123", "abc123", version)).isTrue();
    }

    private GitRepositoryStorageService service(GitRepositoryProjectionStore store) {
        return new GitRepositoryStorageService(
                new GitRepositoryStorageProperties(
                        true, "test", tempDir, List.of("github.com"),
                        10, 100, 10, 10_000_000
                ),
                store,
                new JavaCodeGraphAnalyzer()
        );
    }
}
