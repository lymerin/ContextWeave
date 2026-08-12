package com.devcollab.worker.git;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GitRepositoryProjectionStoreTests {

    JdbcTemplate jdbc;
    GitRepositoryProjectionStore store;
    UUID repositoryId;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:store-test-" + UUID.randomUUID()
                        + ";DB_CLOSE_DELAY=-1",
                "sa", ""
        );
        jdbc = new JdbcTemplate(dataSource);
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
        store = new GitRepositoryProjectionStore(jdbc);
        repositoryId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO git_repositories
                    (id, workspace_id, name, provider, remote_url, default_branch,
                     created_by, created_at, updated_at)
                VALUES (?, ?, 'repo', 'GITHUB', 'https://github.com/a/b.git',
                        'main', ?, ?, ?)
                """, repositoryId, UUID.randomUUID(), UUID.randomUUID(),
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
    }

    @Test
    void readsNullCheckpointForNewRepository() {
        GitRepositoryProjectionStore.ProjectionCheckpoint checkpoint =
                store.readProjectionCheckpoint(repositoryId);
        assertThat(checkpoint.lastSyncedCommit()).isNull();
        assertThat(checkpoint.projectionVersion()).isNull();
    }

    @Test
    void markReadyWritesHeadAndCurrentProjectionVersion() {
        store.markReady(repositoryId, "abc123");

        GitRepositoryProjectionStore.ProjectionCheckpoint checkpoint =
                store.readProjectionCheckpoint(repositoryId);
        assertThat(checkpoint.lastSyncedCommit()).isEqualTo("abc123");
        assertThat(checkpoint.projectionVersion()).isEqualTo(
                GitRepositoryProjectionStore.CURRENT_PROJECTION_VERSION);
        assertThat(jdbc.queryForObject(
                "SELECT sync_status FROM git_repositories WHERE id = ?",
                String.class, repositoryId)).isEqualTo("READY");
        assertThat(jdbc.queryForObject(
                "SELECT last_sync_error FROM git_repositories WHERE id = ?",
                String.class, repositoryId)).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT last_synced_at FROM git_repositories WHERE id = ?",
                Timestamp.class, repositoryId)).isNotNull();
    }

    @Test
    void markSyncingClaimsOnlyExistingRepositoryAndClearsError() {
        jdbc.update("""
                UPDATE git_repositories
                   SET sync_status = 'FAILED', last_sync_error = 'boom'
                 WHERE id = ?
                """, repositoryId);

        assertThat(store.markSyncing(repositoryId)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT sync_status FROM git_repositories WHERE id = ?",
                String.class, repositoryId)).isEqualTo("SYNCING");
        assertThat(jdbc.queryForObject(
                "SELECT last_sync_error FROM git_repositories WHERE id = ?",
                String.class, repositoryId)).isNull();
        assertThat(store.markSyncing(UUID.randomUUID())).isFalse();
    }
}
