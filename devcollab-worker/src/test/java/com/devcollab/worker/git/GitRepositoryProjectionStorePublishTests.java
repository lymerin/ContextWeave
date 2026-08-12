package com.devcollab.worker.git;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies {@link GitRepositoryProjectionStore#publishProjection} atomicity
 * and batching against H2. A minimal Spring context is required so the
 * {@code @Transactional} boundary is honored.
 */
@SpringJUnitConfig(GitRepositoryProjectionStorePublishTests.Config.class)
class GitRepositoryProjectionStorePublishTests {

    @Configuration
    @EnableTransactionManagement
    static class Config {

        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(
                    "jdbc:h2:mem:publish-test-" + UUID.randomUUID()
                            + ";DB_CLOSE_DELAY=-1",
                    "sa", ""
            );
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        GitRepositoryProjectionStore store(JdbcTemplate jdbcTemplate) {
            return new GitRepositoryProjectionStore(jdbcTemplate);
        }
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    GitRepositoryProjectionStore store;

    @Autowired
    DataSource dataSource;

    UUID repositoryId;

    @BeforeEach
    void setUp() {
        repositoryId = UUID.randomUUID();
        for (String table : List.of(
                "code_file_dependencies", "code_symbol_dependencies",
                "code_symbols", "git_file_diffs", "git_changes",
                "git_repository_files", "git_repositories"
        )) {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        }
        jdbc.execute("""
                CREATE TABLE git_repositories (
                    id UUID PRIMARY KEY,
                    workspace_id UUID NOT NULL,
                    name VARCHAR(120) NOT NULL,
                    provider VARCHAR(30) NOT NULL,
                    remote_url VARCHAR(500) NOT NULL,
                    default_branch VARCHAR(200) NOT NULL,
                    created_by UUID NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    sync_status VARCHAR(30) NOT NULL DEFAULT 'REGISTERED',
                    last_synced_commit VARCHAR(64),
                    last_synced_at TIMESTAMP WITH TIME ZONE,
                    last_sync_error VARCHAR(1000),
                    projection_version INTEGER
                )
                """);
        jdbc.execute("""
                CREATE TABLE git_repository_files (
                    id UUID PRIMARY KEY,
                    repository_id UUID NOT NULL,
                    path VARCHAR(1000) NOT NULL,
                    blob_sha VARCHAR(64) NOT NULL,
                    size_bytes BIGINT NOT NULL,
                    language VARCHAR(80),
                    content_text TEXT,
                    CONSTRAINT uk_git_repository_file UNIQUE (repository_id, path)
                )
                """);
        jdbc.execute("""
                CREATE TABLE git_changes (
                    id UUID PRIMARY KEY,
                    repository_id UUID NOT NULL,
                    change_type VARCHAR(30) NOT NULL,
                    external_id VARCHAR(200) NOT NULL,
                    title VARCHAR(500) NOT NULL,
                    commit_sha VARCHAR(64) NOT NULL,
                    author_name VARCHAR(200),
                    author_email VARCHAR(320),
                    authored_at TIMESTAMP WITH TIME ZONE,
                    committer_name VARCHAR(200),
                    committer_email VARCHAR(200),
                    parent_commit_sha VARCHAR(64),
                    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    CONSTRAINT uk_git_change_external UNIQUE
                        (repository_id, change_type, external_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE git_file_diffs (
                    id UUID PRIMARY KEY,
                    git_change_id UUID NOT NULL,
                    path VARCHAR(1000) NOT NULL,
                    old_path VARCHAR(1000),
                    change_type VARCHAR(30) NOT NULL,
                    additions INTEGER NOT NULL,
                    deletions INTEGER NOT NULL,
                    binary_file BOOLEAN NOT NULL DEFAULT FALSE,
                    patch_excerpt TEXT
                )
                """);
        jdbc.execute("""
                CREATE TABLE code_symbols (
                    id UUID PRIMARY KEY,
                    repository_id UUID NOT NULL,
                    file_path VARCHAR(1000) NOT NULL,
                    symbol_key VARCHAR(1500) NOT NULL,
                    language VARCHAR(40) NOT NULL,
                    symbol_kind VARCHAR(40) NOT NULL,
                    qualified_name VARCHAR(1000) NOT NULL,
                    simple_name VARCHAR(300) NOT NULL,
                    signature VARCHAR(1500),
                    parent_symbol_key VARCHAR(1500),
                    start_line INTEGER,
                    end_line INTEGER,
                    CONSTRAINT uk_code_symbol_key UNIQUE (repository_id, symbol_key)
                )
                """);
        jdbc.execute("""
                CREATE TABLE code_symbol_dependencies (
                    id UUID PRIMARY KEY,
                    repository_id UUID NOT NULL,
                    source_symbol_key VARCHAR(1500) NOT NULL,
                    target_symbol_key VARCHAR(1500) NOT NULL,
                    relation_type VARCHAR(40) NOT NULL,
                    evidence_file_path VARCHAR(1000) NOT NULL,
                    CONSTRAINT uk_code_symbol_dependency UNIQUE
                        (repository_id, source_symbol_key, target_symbol_key,
                         relation_type)
                )
                """);
        jdbc.execute("""
                CREATE TABLE code_file_dependencies (
                    id UUID PRIMARY KEY,
                    repository_id UUID NOT NULL,
                    source_path VARCHAR(1000) NOT NULL,
                    target_path VARCHAR(1000) NOT NULL,
                    relation_type VARCHAR(40) NOT NULL,
                    CONSTRAINT uk_code_file_dependency UNIQUE
                        (repository_id, source_path, target_path, relation_type)
                )
                """);
        jdbc.update("""
                INSERT INTO git_repositories
                    (id, workspace_id, name, provider, remote_url, default_branch,
                     created_by, created_at, updated_at)
                VALUES (?, ?, 'repo', 'GITHUB', 'https://github.com/a/b.git',
                        'main', ?, ?, ?)
                """, repositoryId, UUID.randomUUID(), UUID.randomUUID(),
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
    }

    private GitRepositoryFileProjection file(String path, String blobSha) {
        return new GitRepositoryFileProjection(path, blobSha, 4, "Java", "code");
    }

    private GitDiffProjection diff(String path, String changeType) {
        return new GitDiffProjection(path, null, changeType, 1, 1, false, "p");
    }

    private GitCommitProjection commit(String sha, String title,
                                       List<GitDiffProjection> diffs) {
        return new GitCommitProjection(
                sha, title, "author", "a@b.c", Instant.parse("2026-08-01T00:00:00Z"),
                "committer", "c@d.e", Instant.parse("2026-08-01T00:00:00Z"),
                null, diffs
        );
    }

    private CodeGraphProjection graph() {
        return new CodeGraphProjection(
                List.of(new CodeSymbolProjection(
                        "a/OrderService.java", "a.OrderService", "Java", "CLASS",
                        "a.OrderService", "OrderService", "class OrderService",
                        null, 1, 5
                )),
                List.of(new CodeSymbolDependencyProjection(
                        "a.OrderService", "a.OrderRepository", "EXTENDS",
                        "a/OrderService.java"
                )),
                List.of(new CodeFileDependencyProjection(
                        "a/OrderService.java", "a/OrderRepository.java", "EXTENDS"
                )),
                List.of()
        );
    }

    @Test
    void publishesCompleteProjectionAtomically() {
        store.publishProjection(
                repositoryId,
                List.of(file("a/One.java", "sha1"), file("a/Two.java", "sha2")),
                List.of(),
                List.of(commit("c1", "first", List.of(diff("a/One.java", "ADDED")))),
                graph(),
                "c1"
        );

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM git_repository_files WHERE repository_id = ?",
                Integer.class, repositoryId)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT content_text FROM git_repository_files WHERE repository_id = ? AND path = 'a/One.java'",
                String.class, repositoryId)).isEqualTo("code");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM git_changes WHERE repository_id = ?",
                Integer.class, repositoryId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM git_file_diffs WHERE git_change_id = (SELECT id FROM git_changes WHERE repository_id = ?)",
                Integer.class, repositoryId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM code_symbols WHERE repository_id = ?",
                Integer.class, repositoryId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM code_symbol_dependencies WHERE repository_id = ?",
                Integer.class, repositoryId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM code_file_dependencies WHERE repository_id = ?",
                Integer.class, repositoryId)).isEqualTo(1);
        GitRepositoryProjectionStore.ProjectionCheckpoint checkpoint =
                store.readProjectionCheckpoint(repositoryId);
        assertThat(checkpoint.lastSyncedCommit()).isEqualTo("c1");
        assertThat(checkpoint.projectionVersion()).isEqualTo(
                GitRepositoryProjectionStore.CURRENT_PROJECTION_VERSION);
        assertThat(jdbc.queryForObject(
                "SELECT sync_status FROM git_repositories WHERE id = ?",
                String.class, repositoryId)).isEqualTo("READY");
    }

    @Test
    void failedPublishRollsBackFilesCommitsGraphAndCheckpoint() {
        store.publishProjection(
                repositoryId,
                List.of(file("a/Keep.java", "keep-sha")),
                List.of(),
                List.of(commit("keep", "kept", List.of())),
                new CodeGraphProjection(List.of(), List.of(), List.of(), List.of()),
                "keep"
        );

        assertThatThrownBy(() -> store.publishProjection(
                repositoryId,
                List.of(file("b/Bomb.java", "bomb-sha")),
                List.of(),
                List.of(commit("bomb", "x".repeat(501), List.of())),
                graph(),
                "bomb"
        )).isInstanceOf(org.springframework.dao.DataAccessException.class);

        // Old projection fully intact, checkpoint untouched.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM git_repository_files WHERE repository_id = ?",
                Integer.class, repositoryId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT path FROM git_repository_files WHERE repository_id = ?",
                String.class, repositoryId)).isEqualTo("a/Keep.java");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM git_changes WHERE repository_id = ?",
                Integer.class, repositoryId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT commit_sha FROM git_changes WHERE repository_id = ?",
                String.class, repositoryId)).isEqualTo("keep");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM code_symbols WHERE repository_id = ?",
                Integer.class, repositoryId)).isZero();
        GitRepositoryProjectionStore.ProjectionCheckpoint checkpoint =
                store.readProjectionCheckpoint(repositoryId);
        assertThat(checkpoint.lastSyncedCommit()).isEqualTo("keep");
    }

    @Test
    void republishingSameCommitReplacesDiffsWithoutDuplicates() {
        store.publishProjection(
                repositoryId,
                List.of(file("a/One.java", "sha1")),
                List.of(),
                List.of(commit("c1", "first", List.of(diff("a/One.java", "ADDED")))),
                new CodeGraphProjection(List.of(), List.of(), List.of(), List.of()),
                "c1"
        );
        store.publishProjection(
                repositoryId,
                List.of(file("a/One.java", "sha1"), file("a/Two.java", "sha2")),
                List.of(),
                List.of(commit("c1", "first",
                        List.of(diff("a/One.java", "MODIFIED"), diff("a/Two.java", "ADDED")))),
                new CodeGraphProjection(List.of(), List.of(), List.of(), List.of()),
                "c1"
        );

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM git_changes WHERE repository_id = ?",
                Integer.class, repositoryId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM git_file_diffs WHERE git_change_id = (SELECT id FROM git_changes WHERE repository_id = ?)",
                Integer.class, repositoryId)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM git_file_diffs WHERE path = 'a/One.java'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void publishesLargeFileSetInBoundedChunks() {
        List<GitRepositoryFileProjection> files = new java.util.ArrayList<>();
        for (int i = 0; i < 1200; i++) {
            files.add(file("dir/f" + i + ".txt", "sha" + i));
        }
        store.publishProjection(
                repositoryId, files, List.of(),
                List.of(commit("big", "big", List.of())),
                new CodeGraphProjection(List.of(), List.of(), List.of(), List.of()),
                "big"
        );
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM git_repository_files WHERE repository_id = ?",
                Integer.class, repositoryId)).isEqualTo(1200);
    }

    @Test
    void incrementalPublishPreservesUnchangedContentAndDeletesRemovedPaths() {
        store.publishProjection(
                repositoryId,
                List.of(file("a/Keep.java", "keep-sha"), file("b/Gone.java", "gone-sha")),
                List.of(),
                List.of(commit("c1", "first", List.of())),
                new CodeGraphProjection(List.of(), List.of(), List.of(), List.of()),
                "c1"
        );
        // Pretend the untouched row holds content an earlier version wrote;
        // the incremental publish must not rewrite it.
        jdbc.update("""
                UPDATE git_repository_files
                   SET content_text = 'MARKER'
                 WHERE repository_id = ? AND path = 'a/Keep.java'
                """, repositoryId);

        store.publishProjection(
                repositoryId,
                List.of(file("c/New.java", "new-sha")),
                List.of("b/Gone.java"),
                List.of(commit("c2", "second", List.of())),
                new CodeGraphProjection(List.of(), List.of(), List.of(), List.of()),
                "c2"
        );

        assertThat(jdbc.queryForObject(
                "SELECT content_text FROM git_repository_files WHERE repository_id = ? AND path = 'a/Keep.java'",
                String.class, repositoryId)).isEqualTo("MARKER");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM git_repository_files WHERE repository_id = ? AND path = 'b/Gone.java'",
                Integer.class, repositoryId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT blob_sha FROM git_repository_files WHERE repository_id = ? AND path = 'c/New.java'",
                String.class, repositoryId)).isEqualTo("new-sha");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM git_repository_files WHERE repository_id = ?",
                Integer.class, repositoryId)).isEqualTo(2);
    }

    @Test
    void incrementalPublishUpsertsChangedPathInPlace() {
        store.publishProjection(
                repositoryId,
                List.of(file("a/One.java", "sha-v1")),
                List.of(),
                List.of(commit("c1", "first", List.of())),
                new CodeGraphProjection(List.of(), List.of(), List.of(), List.of()),
                "c1"
        );
        store.publishProjection(
                repositoryId,
                List.of(file("a/One.java", "sha-v2")),
                List.of(),
                List.of(commit("c2", "second", List.of())),
                new CodeGraphProjection(List.of(), List.of(), List.of(), List.of()),
                "c2"
        );

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM git_repository_files WHERE repository_id = ?",
                Integer.class, repositoryId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT blob_sha FROM git_repository_files WHERE repository_id = ? AND path = 'a/One.java'",
                String.class, repositoryId)).isEqualTo("sha-v2");
        assertThat(jdbc.queryForObject(
                "SELECT content_text FROM git_repository_files WHERE repository_id = ? AND path = 'a/One.java'",
                String.class, repositoryId)).isEqualTo("code");
    }
}
