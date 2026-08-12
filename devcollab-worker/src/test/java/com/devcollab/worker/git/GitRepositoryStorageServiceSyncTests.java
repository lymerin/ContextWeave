package com.devcollab.worker.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.AfterEach;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that a projection built through incremental syncs (a few commits
 * at a time) is equivalent to one built by a single full sync of the same
 * final origin state. The {@code synchronize} entry point cannot be used
 * because {@code validateRemote} only allows public HTTPS hosts; this test
 * mirrors its pipeline (openOrClone → checkpoint → scan → publish) so both
 * paths exercise the same incremental code.
 */
@SpringJUnitConfig(GitRepositoryStorageServiceSyncTests.Config.class)
class GitRepositoryStorageServiceSyncTests {

    private static final String MAIN = "main";

    @Configuration
    @EnableTransactionManagement
    static class Config {

        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(
                    "jdbc:h2:mem:sync-test-" + UUID.randomUUID()
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
        GitRepositoryStorageProperties storageProperties() {
            return new GitRepositoryStorageProperties(
                    true, "test",
                    Path.of("target", "sync-tests",
                            UUID.randomUUID().toString()).toAbsolutePath().normalize(),
                    List.of("github.com"), 10, 100, 10, 100_000_000
            );
        }

        @Bean
        GitRepositoryProjectionStore store(JdbcTemplate jdbcTemplate) {
            return new GitRepositoryProjectionStore(jdbcTemplate);
        }

        @Bean
        JavaCodeGraphAnalyzer analyzer() {
            return new JavaCodeGraphAnalyzer();
        }

        @Bean
        GitRepositoryStorageService service(
                GitRepositoryStorageProperties properties,
                GitRepositoryProjectionStore store,
                JavaCodeGraphAnalyzer analyzer
        ) {
            return new GitRepositoryStorageService(properties, store, analyzer);
        }
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    GitRepositoryProjectionStore store;

    @Autowired
    GitRepositoryStorageService service;

    @Autowired
    GitRepositoryStorageProperties properties;

    @Autowired
    JavaCodeGraphAnalyzer analyzer;

    Path tempDir;
    Path originDir;
    String originUri;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Path.of("target", "sync-fixtures", UUID.randomUUID().toString())
                .toAbsolutePath().normalize();
        Files.createDirectories(tempDir);
        originDir = tempDir.resolve("origin");
        Files.createDirectories(originDir);
        originUri = originDir.toUri().toString();
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS git_repositories (
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
                CREATE TABLE IF NOT EXISTS git_repository_files (
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
                CREATE TABLE IF NOT EXISTS git_changes (
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
                CREATE TABLE IF NOT EXISTS git_file_diffs (
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
                CREATE TABLE IF NOT EXISTS code_symbols (
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
                CREATE TABLE IF NOT EXISTS code_symbol_dependencies (
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
                CREATE TABLE IF NOT EXISTS code_file_dependencies (
                    id UUID PRIMARY KEY,
                    repository_id UUID NOT NULL,
                    source_path VARCHAR(1000) NOT NULL,
                    target_path VARCHAR(1000) NOT NULL,
                    relation_type VARCHAR(40) NOT NULL,
                    CONSTRAINT uk_code_file_dependency UNIQUE
                        (repository_id, source_path, target_path, relation_type)
                )
                """);
    }

    @AfterEach
    void cleanUp() throws Exception {
        for (Path root : List.of(tempDir, properties.dataRoot())) {
            if (!Files.exists(root)) continue;
            try (var paths = Files.walk(root)) {
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
    }

    // ---- fixture helpers ----

    private void makeCommits(int startIndex, int count) throws Exception {
        boolean fresh = !Files.exists(originDir.resolve(".git"));
        try (Git git = fresh
                ? Git.init().setInitialBranch(MAIN).setDirectory(originDir.toFile()).call()
                : Git.open(originDir.toFile())) {
            for (int i = startIndex; i < startIndex + count; i++) {
                switch (i % 6) {
                    case 0 -> {
                        Files.writeString(originDir.resolve("OrderService.java"), """
                                package app;
                                public class OrderService {
                                    private String id;
                                    public void createOrder() {}
                                }
                                """);
                    }
                    case 1 -> {
                        Files.writeString(originDir.resolve("SpecialService.java"), """
                                package app;
                                public class SpecialService extends OrderService {
                                    public void special() {}
                                }
                                """);
                    }
                    case 2 -> Files.writeString(
                            originDir.resolve("notes.txt"), "note " + i + "\n");
                    case 3 -> {
                        Files.writeString(originDir.resolve("OrderService.java"), """
                                package app;
                                public class OrderService {
                                    private String id;
                                    private String status;
                                    public void createOrder() {}
                                    public void cancelOrder() {}
                                }
                                """);
                    }
                    case 4 -> {
                        Files.deleteIfExists(originDir.resolve("notes.txt"));
                        Files.writeString(originDir.resolve("README.md"),
                                "# doc " + i + "\n");
                    }
                    case 5 -> {
                        Path current = originDir.resolve("SpecialService.java");
                        Path renamed = originDir.resolve("AdvancedService.java");
                        if (Files.exists(current)) {
                            Files.deleteIfExists(renamed);
                            Files.move(current, renamed);
                        } else {
                            Files.move(renamed, current);
                        }
                    }
                }
                git.add().addFilepattern(".").call();
                git.commit().setMessage("commit " + i).call();
            }
        }
    }

    private void forcePushNewHistory(int count) throws Exception {
        Path newOrigin = tempDir.resolve("new-origin");
        Files.createDirectories(newOrigin);
        boolean fresh = !Files.exists(newOrigin.resolve(".git"));
        try (Git git = fresh
                ? Git.init().setInitialBranch(MAIN).setDirectory(newOrigin.toFile()).call()
                : Git.open(newOrigin.toFile())) {
            for (int i = 0; i < count; i++) {
                Files.writeString(newOrigin.resolve("Fresh.java"),
                        "package app;\npublic class Fresh" + i
                                + " { public void run() {} }\n");
                git.add().addFilepattern(".").call();
                git.commit().setMessage("fresh " + i).call();
            }
            git.push()
                    .setRemote(originDir.toUri().toString())
                    .setRefSpecs(new RefSpec(
                            "refs/heads/" + MAIN + ":refs/heads/" + MAIN))
                    .setForce(true)
                    .call();
        }
    }

    private UUID registerRepository(UUID workspaceId) {
        UUID repositoryId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO git_repositories
                    (id, workspace_id, name, provider, remote_url, default_branch,
                     created_by, created_at, updated_at)
                VALUES (?, ?, 'repo', 'GITHUB', ?, 'main', ?, ?, ?)
                """, repositoryId, workspaceId, originUri, UUID.randomUUID(),
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
        return repositoryId;
    }

    /**
     * Mirror of the GitRepositoryStorageService#synchronize pipeline minus
     * validateRemote/markSyncing/markFailed; the entry point rejects non-HTTPS
     * remotes by design.
     */
    private void syncOnce(UUID workspaceId, UUID repositoryId) throws Exception {
        store.markSyncing(repositoryId);
        Path directory = properties.dataRoot()
                .resolve(workspaceId.toString())
                .resolve(repositoryId.toString())
                .resolve("repository");
        Files.createDirectories(directory.getParent());
        try (Git git = service.openOrClone(
                repositoryId, directory, originUri, MAIN
        )) {
            Repository repository = git.getRepository();
            String head = repository.resolve("HEAD").name();
            GitRepositoryProjectionStore.ProjectionCheckpoint checkpoint =
                    store.readProjectionCheckpoint(repositoryId);
            if (GitRepositoryStorageService.isUnchangedProjection(
                    head, checkpoint.lastSyncedCommit(),
                    checkpoint.projectionVersion()
            )) {
                store.markReady(repositoryId, head);
                return;
            }
            GitRepositoryStorageService.FileScanResult scan =
                    service.scanFiles(repository, repositoryId);
            List<GitCommitProjection> commits = service.scanCommits(
                    git, repository, checkpoint.lastSyncedCommit()
            );
            var graph = analyzer.analyze(directory, scan.currentMetadata());
            store.publishProjection(
                    repositoryId, scan.changed(), scan.deletedPaths(),
                    commits, graph, head
            );
        }
    }

    // ---- snapshot helpers ----

    private List<String> fileSnapshot(UUID repositoryId) {
        return jdbc.query("""
                SELECT path, blob_sha, size_bytes, language, content_text
                  FROM git_repository_files
                 WHERE repository_id = ?
                 ORDER BY path
                """, (rs, row) -> String.join("|",
                        rs.getString("path"), rs.getString("blob_sha"),
                        String.valueOf(rs.getLong("size_bytes")),
                        String.valueOf(rs.getString("language")),
                        String.valueOf(rs.getString("content_text"))
                ), repositoryId);
    }

    private List<String> graphSnapshot(UUID repositoryId) {
        List<String> rows = new ArrayList<>();
        rows.addAll(jdbc.query("""
                SELECT file_path, symbol_key, symbol_kind, qualified_name,
                       simple_name, signature, parent_symbol_key, start_line, end_line
                  FROM code_symbols
                 WHERE repository_id = ?
                 ORDER BY symbol_key
                """, (rs, row) -> String.join("|",
                        rs.getString("file_path"), rs.getString("symbol_key"),
                        rs.getString("symbol_kind"), rs.getString("qualified_name"),
                        rs.getString("simple_name"), String.valueOf(rs.getString("signature")),
                        String.valueOf(rs.getString("parent_symbol_key")),
                        String.valueOf(rs.getInt("start_line")),
                        String.valueOf(rs.getInt("end_line"))
                ), repositoryId));
        rows.addAll(jdbc.query("""
                SELECT source_symbol_key, target_symbol_key, relation_type,
                       evidence_file_path
                  FROM code_symbol_dependencies
                 WHERE repository_id = ?
                 ORDER BY source_symbol_key, target_symbol_key
                """, (rs, row) -> String.join("|",
                        rs.getString("source_symbol_key"),
                        rs.getString("target_symbol_key"),
                        rs.getString("relation_type"),
                        rs.getString("evidence_file_path")
                ), repositoryId));
        rows.addAll(jdbc.query("""
                SELECT source_path, target_path, relation_type
                  FROM code_file_dependencies
                 WHERE repository_id = ?
                 ORDER BY source_path, target_path
                """, (rs, row) -> String.join("|",
                        rs.getString("source_path"), rs.getString("target_path"),
                        rs.getString("relation_type")
                ), repositoryId));
        return rows;
    }

    private record CommitSnapshot(
            String title, String parentSha, List<String> diffs
    ) {
    }

    private Map<String, CommitSnapshot> commitSnapshot(UUID repositoryId) {
        List<Map<String, Object>> changes = jdbc.queryForList("""
                SELECT external_id, title, parent_commit_sha
                  FROM git_changes
                 WHERE repository_id = ? AND change_type = 'COMMIT'
                """, repositoryId);
        Map<String, CommitSnapshot> result = new java.util.LinkedHashMap<>();
        for (Map<String, Object> change : changes) {
            String sha = (String) change.get("external_id");
            List<String> diffs = jdbc.query("""
                    SELECT path, old_path, change_type, additions, deletions,
                           binary_file, patch_excerpt
                      FROM git_file_diffs
                     WHERE git_change_id = (SELECT id FROM git_changes
                                             WHERE repository_id = ?
                                               AND change_type = 'COMMIT'
                                               AND external_id = ?)
                     ORDER BY path
                    """, (rs, row) -> String.join("|",
                            rs.getString("path"), String.valueOf(rs.getString("old_path")),
                            rs.getString("change_type"),
                            String.valueOf(rs.getInt("additions")),
                            String.valueOf(rs.getInt("deletions")),
                            String.valueOf(rs.getBoolean("binary_file")),
                            String.valueOf(rs.getString("patch_excerpt"))
                    ), repositoryId, sha);
            result.put(sha, new CommitSnapshot(
                    (String) change.get("title"), (String) change.get("parent_commit_sha"),
                    diffs
            ));
        }
        return result;
    }

    /**
     * Files and code graph must be exactly equivalent; the commit set of the
     * stepped sync must contain every commit of the one-shot sync with
     * identical fields (it may additionally keep older observed history).
     */
    private void assertEquivalent(UUID stepped, UUID oneShot) {
        assertThat(fileSnapshot(stepped)).isEqualTo(fileSnapshot(oneShot));
        assertThat(graphSnapshot(stepped)).isEqualTo(graphSnapshot(oneShot));
        Map<String, CommitSnapshot> steppedCommits = commitSnapshot(stepped);
        Map<String, CommitSnapshot> oneShotCommits = commitSnapshot(oneShot);
        assertThat(oneShotCommits).allSatisfy((sha, commit) -> {
            assertThat(steppedCommits).containsKey(sha);
            assertThat(steppedCommits.get(sha)).isEqualTo(commit);
        });
    }

    // ---- tests ----

    @Test
    void incrementalFileAndCommitSyncsMatchOneShotProjection()
            throws Exception {
        UUID steppedWorkspace = UUID.randomUUID();
        UUID steppedRepository = registerRepository(steppedWorkspace);
        UUID oneShotWorkspace = UUID.randomUUID();
        UUID oneShotRepository = registerRepository(oneShotWorkspace);

        makeCommits(0, 5);
        syncOnce(steppedWorkspace, steppedRepository);
        makeCommits(5, 4);
        syncOnce(steppedWorkspace, steppedRepository);
        syncOnce(oneShotWorkspace, oneShotRepository);

        assertEquivalent(steppedRepository, oneShotRepository);
    }

    @Test
    void forcePushToUnrelatedHistoryStaysEquivalent() throws Exception {
        UUID steppedWorkspace = UUID.randomUUID();
        UUID steppedRepository = registerRepository(steppedWorkspace);
        UUID oneShotWorkspace = UUID.randomUUID();
        UUID oneShotRepository = registerRepository(oneShotWorkspace);

        makeCommits(0, 5);
        syncOnce(steppedWorkspace, steppedRepository);
        forcePushNewHistory(3);
        syncOnce(steppedWorkspace, steppedRepository);
        syncOnce(oneShotWorkspace, oneShotRepository);

        assertEquivalent(steppedRepository, oneShotRepository);
    }

    @Test
    void newCommitsBeyondShallowBoundaryFallBackToFullScan() throws Exception {
        UUID steppedWorkspace = UUID.randomUUID();
        UUID steppedRepository = registerRepository(steppedWorkspace);
        UUID oneShotWorkspace = UUID.randomUUID();
        UUID oneShotRepository = registerRepository(oneShotWorkspace);

        makeCommits(0, 10);
        syncOnce(steppedWorkspace, steppedRepository);
        makeCommits(10, 20); // 30 total: old head falls below the shallow edge
        syncOnce(steppedWorkspace, steppedRepository);
        syncOnce(oneShotWorkspace, oneShotRepository);

        assertEquivalent(steppedRepository, oneShotRepository);
    }
}
