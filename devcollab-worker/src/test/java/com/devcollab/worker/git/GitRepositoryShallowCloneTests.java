package com.devcollab.worker.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Verifies the shallow-clone boundary semantics of
 * {@link GitRepositoryStorageService#openOrClone}: the clone is bounded to
 * {@code maxCommits + 1} history entries, the {@code max-commits}-th diff is
 * still resolvable against its parent, later fetches stay bounded, and a
 * force-push to unrelated history does not fabricate boundary diffs.
 */
class GitRepositoryShallowCloneTests {

    private static final String MAIN = "main";

    Path tempDir;
    Path originDir;
    UUID repositoryId;
    GitRepositoryStorageService service;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Path.of("target", "git-shallow-tests", UUID.randomUUID().toString())
                .toAbsolutePath().normalize();
        Files.createDirectories(tempDir);
        originDir = tempDir.resolve("origin");
        Files.createDirectories(originDir);
        repositoryId = UUID.randomUUID();
        service = new GitRepositoryStorageService(
                new GitRepositoryStorageProperties(
                        true, "test", tempDir, List.of("github.com"),
                        10, 100, 10, 100_000_000
                ),
                mock(GitRepositoryProjectionStore.class),
                new JavaCodeGraphAnalyzer()
        );
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

    private void makeCommits(Path directory, int startIndex, int count)
            throws Exception {
        boolean fresh = !Files.exists(directory.resolve(".git"));
        try (Git git = fresh
                ? Git.init().setInitialBranch(MAIN).setDirectory(directory.toFile()).call()
                : Git.open(directory.toFile())) {
            for (int i = startIndex; i < startIndex + count; i++) {
                Files.writeString(
                        directory.resolve("f" + (i % 3) + ".txt"),
                        "line " + i + "\n"
                );
                git.add().addFilepattern(".").call();
                git.commit().setMessage("commit " + i).call();
            }
        }
    }

    private Path targetDir() {
        Path target = tempDir.resolve("clone").resolve("repository");
        target.getParent().toFile().mkdirs();
        return target;
    }

    private static void assertCommitsEqual(
            List<GitCommitProjection> actual,
            List<GitCommitProjection> expected
    ) {
        assertThat(actual).hasSameSizeAs(expected);
        for (int i = 0; i < expected.size(); i++) {
            GitCommitProjection a = actual.get(i);
            GitCommitProjection e = expected.get(i);
            assertThat(a.commitSha()).isEqualTo(e.commitSha());
            assertThat(a.title()).isEqualTo(e.title());
            assertThat(a.authorName()).isEqualTo(e.authorName());
            assertThat(a.committerName()).isEqualTo(e.committerName());
            assertThat(a.parentCommitSha()).isEqualTo(e.parentCommitSha());
            assertThat(a.files()).hasSameSizeAs(e.files());
            for (int j = 0; j < e.files().size(); j++) {
                GitDiffProjection da = a.files().get(j);
                GitDiffProjection de = e.files().get(j);
                assertThat(da.path()).isEqualTo(de.path());
                assertThat(da.oldPath()).isEqualTo(de.oldPath());
                assertThat(da.changeType()).isEqualTo(de.changeType());
                assertThat(da.additions()).isEqualTo(de.additions());
                assertThat(da.deletions()).isEqualTo(de.deletions());
                assertThat(da.binaryFile()).isEqualTo(de.binaryFile());
            }
        }
    }

    private List<GitCommitProjection> fullCloneCommits(String originUri)
            throws Exception {
        Path fullDir = tempDir.resolve("full");
        try (Git full = Git.cloneRepository()
                .setURI(originUri)
                .setDirectory(fullDir.toFile())
                .setBranch(MAIN)
                .setCloneAllBranches(false)
                .call()) {
            return service.scanCommits(full, full.getRepository());
        }
    }

    @Test
    void shallowCloneBoundedHistoryAndBoundaryDiffMatchesFullClone()
            throws Exception {
        makeCommits(originDir, 0, 102);
        String originUri = originDir.toUri().toString();

        try (Git git = service.openOrClone(
                repositoryId, targetDir(), originUri, MAIN
        )) {
            assertThat(targetDir().resolve(".git").resolve("shallow")).exists();
            List<GitCommitProjection> commits =
                    service.scanCommits(git, git.getRepository());
            assertThat(commits).hasSize(10);
            // The oldest kept commit still has its boundary parent locally,
            // so its diff is computed against a real parent, not fabricated.
            assertThat(commits.get(9).parentCommitSha()).isNotNull();

            assertCommitsEqual(commits, fullCloneCommits(originUri));
        }
    }

    @Test
    void fetchAfterNewCommitsKeepsBoundedHistory() throws Exception {
        makeCommits(originDir, 0, 102);
        String originUri = originDir.toUri().toString();
        try (Git ignored = service.openOrClone(
                repositoryId, targetDir(), originUri, MAIN
        )) {
            // initial shallow clone
        }
        makeCommits(originDir, 102, 120); // 222 total on the origin

        try (Git git = service.openOrClone(
                repositoryId, targetDir(), originUri, MAIN
        )) {
            List<GitCommitProjection> commits =
                    service.scanCommits(git, git.getRepository());
            assertThat(commits).hasSize(10);
            assertThat(commits.get(0).title()).isEqualTo("commit 221");
            assertThat(commits.get(9).parentCommitSha()).isNotNull();
            assertCommitsEqual(commits, fullCloneCommits(originUri));
        }
    }

    @Test
    void forcePushToUnrelatedHistoryResyncsWithoutFabricatedDiffs()
            throws Exception {
        makeCommits(originDir, 0, 102);
        String originUri = originDir.toUri().toString();
        try (Git ignored = service.openOrClone(
                repositoryId, targetDir(), originUri, MAIN
        )) {
            // initial shallow clone
        }
        Path newOrigin = tempDir.resolve("new-origin");
        Files.createDirectories(newOrigin);
        makeCommits(newOrigin, 0, 5);
        try (Git newGit = Git.open(newOrigin.toFile())) {
            newGit.push()
                    .setRemote(originDir.toUri().toString())
                    .setRefSpecs(new RefSpec("refs/heads/" + MAIN + ":refs/heads/" + MAIN))
                    .setForce(true)
                    .call();
        }

        try (Git git = service.openOrClone(
                repositoryId, targetDir(), originUri, MAIN
        )) {
            List<GitCommitProjection> commits =
                    service.scanCommits(git, git.getRepository());
            assertThat(commits).hasSize(5);
            assertThat(commits.get(0).title()).isEqualTo("commit 4");
            assertCommitsEqual(commits, fullCloneCommits(originUri));
        }
    }

    @Test
    void rejectsNonPositiveMaxCommits() {
        assertThatThrownBy(() -> GitRepositoryStorageService.historyDepth(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GitRepositoryStorageService.historyDepth(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(GitRepositoryStorageService.historyDepth(100)).isEqualTo(101);
        assertThat(GitRepositoryStorageService.historyDepth(Integer.MAX_VALUE))
                .isEqualTo(Integer.MAX_VALUE);
    }
}
