package com.devcollab.worker.git;

import com.devcollab.worker.observability.RuntimeMemoryProfiler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public class GitRepositoryProjectionStore {

    /**
     * Projection format version. Bump when code graph semantics, commit
     * projection semantics, the effective {@code max-commits} range, or a
     * schema/algorithm upgrade requires rebuilding projections for the same
     * HEAD.
     */
    public static final int CURRENT_PROJECTION_VERSION = 1;

    private static final int BATCH_CHUNK_SIZE = 500;

    private final JdbcTemplate jdbcTemplate;
    private RuntimeMemoryProfiler memoryProfiler;

    public GitRepositoryProjectionStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Autowired(required = false)
    void setMemoryProfiler(RuntimeMemoryProfiler memoryProfiler) {
        this.memoryProfiler = memoryProfiler;
    }

    /**
     * Checkpoint used by the fast-return path: the last successfully
     * projected HEAD and the projection version it was built with.
     */
    public record ProjectionCheckpoint(String lastSyncedCommit, Integer projectionVersion) {
    }

    public ProjectionCheckpoint readProjectionCheckpoint(UUID repositoryId) {
        return jdbcTemplate.queryForObject("""
                SELECT last_synced_commit, projection_version
                  FROM git_repositories
                 WHERE id = ?
                """, (rs, rowNum) -> new ProjectionCheckpoint(
                        rs.getString("last_synced_commit"),
                        (Integer) rs.getObject("projection_version")
                ), repositoryId);
    }

    /**
     * Current file fingerprints ({@code path -> blob_sha}) for incremental
     * scanning: files whose SHA matches are skipped (content, decoding and
     * DB rewrite), anything else is treated as changed.
     */
    public Map<String, String> loadFileFingerprints(UUID repositoryId) {
        return jdbcTemplate.query("""
                SELECT path, blob_sha
                  FROM git_repository_files
                 WHERE repository_id = ?
                """, (rs, rowNum) -> Map.entry(
                        rs.getString("path"), rs.getString("blob_sha")
                ), repositoryId).stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey, Map.Entry::getValue
                ));
    }

    public boolean markSyncing(UUID repositoryId) {
        return jdbcTemplate.update("""
                UPDATE git_repositories
                   SET sync_status = 'SYNCING', last_sync_error = NULL,
                       updated_at = ?
                 WHERE id = ?
                """, Timestamp.from(Instant.now()), repositoryId) == 1;
    }

    /**
     * Publish a projection in a single transaction. Files are applied
     * incrementally: only {@code changedFiles} are upserted and only
     * {@code deletedPaths} are removed; every other row keeps its content.
     * Commits are upserted by SHA and history is never deleted. Readers only
     * see the previous projection (and previous checkpoint) until the commit
     * succeeds; on failure everything rolls back together.
     */
    @Transactional
    public void publishProjection(
            UUID repositoryId,
            List<GitRepositoryFileProjection> changedFiles,
            List<String> deletedPaths,
            List<GitCommitProjection> commits,
            CodeGraphProjection graph,
            String headCommit
    ) {
        stageFiles(repositoryId, changedFiles, deletedPaths, headCommit);
        stageCommits(repositoryId, commits, headCommit);
        stageGraph(repositoryId, graph, headCommit);
        markReady(repositoryId, headCommit);
    }

    private void stageFiles(
            UUID repositoryId,
            List<GitRepositoryFileProjection> changedFiles,
            List<String> deletedPaths,
            String head
    ) {
        RuntimeMemoryProfiler.Stage stage = stage("DB_FILES", repositoryId, head);
        try {
            applyFileChanges(repositoryId, changedFiles, deletedPaths);
        } catch (RuntimeException exception) {
            if (stage != null) stage.failed(exception);
            throw exception;
        } finally {
            if (stage != null) stage.close();
        }
    }

    private void stageCommits(
            UUID repositoryId,
            List<GitCommitProjection> commits,
            String head
    ) {
        RuntimeMemoryProfiler.Stage stage = stage("DB_COMMITS", repositoryId, head);
        try {
            saveCommits(repositoryId, commits);
        } catch (RuntimeException exception) {
            if (stage != null) stage.failed(exception);
            throw exception;
        } finally {
            if (stage != null) stage.close();
        }
    }

    private void stageGraph(
            UUID repositoryId,
            CodeGraphProjection graph,
            String head
    ) {
        RuntimeMemoryProfiler.Stage stage = stage("DB_CODE_GRAPH", repositoryId, head);
        try {
            replaceCodeGraph(repositoryId, graph);
        } catch (RuntimeException exception) {
            if (stage != null) stage.failed(exception);
            throw exception;
        } finally {
            if (stage != null) stage.close();
        }
    }

    private RuntimeMemoryProfiler.Stage stage(
            String name,
            UUID repositoryId,
            String revision
    ) {
        return memoryProfiler == null ? null : memoryProfiler.stage(
                name, null, repositoryId.toString(), revision, null
        );
    }

    /**
     * Upsert changed rows and delete removed paths; untouched rows stay
     * byte-identical, including {@code content_text}.
     */
    private void applyFileChanges(
            UUID repositoryId,
            List<GitRepositoryFileProjection> changed,
            List<String> deletedPaths
    ) {
        Map<String, UUID> existingIds = new HashMap<>();
        for (int start = 0; start < changed.size(); start += BATCH_CHUNK_SIZE) {
            List<GitRepositoryFileProjection> chunk = changed.subList(
                    start, Math.min(start + BATCH_CHUNK_SIZE, changed.size())
            );
            String placeholders = chunk.stream().map(id -> "?")
                    .collect(Collectors.joining(", "));
            Object[] args = new Object[chunk.size() + 1];
            args[0] = repositoryId;
            for (int i = 0; i < chunk.size(); i++) {
                args[i + 1] = chunk.get(i).path();
            }
            jdbcTemplate.query("""
                    SELECT path, id
                      FROM git_repository_files
                     WHERE repository_id = ? AND path IN (%s)
                    """.formatted(placeholders),
                    (rs, rowNum) -> Map.entry(
                            rs.getString("path"), rs.getObject("id", UUID.class)
                    ), args)
                    .forEach(entry -> existingIds.put(
                            entry.getKey(), entry.getValue()
                    ));
        }
        List<Object[]> inserts = new ArrayList<>();
        List<Object[]> updates = new ArrayList<>();
        for (GitRepositoryFileProjection file : changed) {
            UUID id = existingIds.get(file.path());
            if (id == null) {
                inserts.add(new Object[]{
                        UUID.randomUUID(), repositoryId, file.path(),
                        file.blobSha(), file.sizeBytes(), file.language(),
                        file.contentText()
                });
            } else {
                updates.add(new Object[]{
                        file.blobSha(), file.sizeBytes(), file.language(),
                        file.contentText(), id
                });
            }
        }
        if (!inserts.isEmpty()) {
            batchInsert("""
                    INSERT INTO git_repository_files
                        (id, repository_id, path, blob_sha, size_bytes, language,
                         content_text)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, inserts);
        }
        if (!updates.isEmpty()) {
            jdbcTemplate.batchUpdate("""
                    UPDATE git_repository_files
                       SET blob_sha = ?, size_bytes = ?, language = ?,
                           content_text = ?
                     WHERE id = ?
                    """, updates);
        }
        for (int start = 0; start < deletedPaths.size();
             start += BATCH_CHUNK_SIZE) {
            List<String> chunk = deletedPaths.subList(
                    start, Math.min(start + BATCH_CHUNK_SIZE, deletedPaths.size())
            );
            String placeholders = chunk.stream().map(id -> "?")
                    .collect(Collectors.joining(", "));
            Object[] args = new Object[chunk.size() + 1];
            args[0] = repositoryId;
            System.arraycopy(chunk.toArray(), 0, args, 1, chunk.size());
            jdbcTemplate.update("""
                    DELETE FROM git_repository_files
                     WHERE repository_id = ? AND path IN (%s)
                    """.formatted(placeholders), args);
        }
    }

    private void saveCommits(
            UUID repositoryId,
            List<GitCommitProjection> commits
    ) {
        // One query resolves every already-projected commit SHA for this
        // repository; no per-commit SELECT.
        Map<String, UUID> existingBySha = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT external_id, id
                  FROM git_changes
                 WHERE repository_id = ? AND change_type = 'COMMIT'
                """, (rs, rowNum) -> Map.entry(
                        rs.getString("external_id"),
                        rs.getObject("id", UUID.class)
                ), repositoryId)
                .forEach(entry -> existingBySha.put(entry.getKey(), entry.getValue()));

        List<Object[]> inserts = new ArrayList<>();
        List<Object[]> updates = new ArrayList<>();
        List<UUID> reinsertChangeIds = new ArrayList<>();
        List<Object[]> diffInserts = new ArrayList<>();
        for (GitCommitProjection commit : commits) {
            UUID changeId = existingBySha.get(commit.commitSha());
            if (changeId == null) {
                changeId = UUID.randomUUID();
                inserts.add(new Object[]{
                        changeId, repositoryId, commit.commitSha(),
                        commit.title(), commit.commitSha(), commit.authorName(),
                        commit.authorEmail(), timestamp(commit.authoredAt()),
                        commit.committerName(), commit.committerEmail(),
                        commit.parentCommitSha(),
                        Timestamp.from(commit.committedAt()),
                        Timestamp.from(Instant.now())
                });
            } else {
                updates.add(new Object[]{
                        commit.title(), commit.commitSha(), commit.authorName(),
                        commit.authorEmail(), timestamp(commit.authoredAt()),
                        commit.committerName(), commit.committerEmail(),
                        commit.parentCommitSha(),
                        Timestamp.from(commit.committedAt()),
                        changeId
                });
                reinsertChangeIds.add(changeId);
            }
            for (GitDiffProjection file : commit.files()) {
                diffInserts.add(new Object[]{
                        UUID.randomUUID(), changeId, file.path(),
                        file.oldPath(), file.changeType(), file.additions(),
                        file.deletions(), file.binaryFile(), file.patchExcerpt()
                });
            }
        }
        if (!inserts.isEmpty()) {
            batchInsert("""
                    INSERT INTO git_changes
                        (id, repository_id, change_type, external_id, title,
                         commit_sha, author_name, author_email, authored_at,
                         committer_name, committer_email, parent_commit_sha,
                         occurred_at, created_at)
                    VALUES (?, ?, 'COMMIT', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, inserts);
        }
        if (!updates.isEmpty()) {
            jdbcTemplate.batchUpdate("""
                    UPDATE git_changes
                       SET title = ?, commit_sha = ?, author_name = ?,
                           author_email = ?, authored_at = ?,
                           committer_name = ?, committer_email = ?,
                           parent_commit_sha = ?, occurred_at = ?
                     WHERE id = ?
                    """, updates);
        }
        if (!reinsertChangeIds.isEmpty()) {
            String placeholders = reinsertChangeIds.stream()
                    .map(id -> "?")
                    .collect(Collectors.joining(", "));
            jdbcTemplate.update(
                    "DELETE FROM git_file_diffs WHERE git_change_id IN ("
                            + placeholders + ")",
                    reinsertChangeIds.toArray()
            );
        }
        if (!diffInserts.isEmpty()) {
            batchInsert("""
                    INSERT INTO git_file_diffs
                        (id, git_change_id, path, old_path, change_type,
                         additions, deletions, binary_file, patch_excerpt)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, diffInserts);
        }
    }

    private void replaceCodeGraph(
            UUID repositoryId,
            CodeGraphProjection graph
    ) {
        jdbcTemplate.update(
                "DELETE FROM code_symbol_dependencies WHERE repository_id = ?",
                repositoryId
        );
        jdbcTemplate.update(
                "DELETE FROM code_file_dependencies WHERE repository_id = ?",
                repositoryId
        );
        jdbcTemplate.update(
                "DELETE FROM code_symbols WHERE repository_id = ?",
                repositoryId
        );
        batchInsert("""
                INSERT INTO code_symbols
                    (id, repository_id, file_path, symbol_key, language,
                     symbol_kind, qualified_name, simple_name, signature,
                     parent_symbol_key, start_line, end_line)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, graph.symbols().stream()
                .map(symbol -> new Object[]{
                        UUID.randomUUID(), repositoryId, symbol.filePath(),
                        symbol.symbolKey(), symbol.language(), symbol.symbolKind(),
                        symbol.qualifiedName(), symbol.simpleName(), symbol.signature(),
                        symbol.parentSymbolKey(), symbol.startLine(), symbol.endLine()
                })
                .toList());
        batchInsert("""
                INSERT INTO code_symbol_dependencies
                    (id, repository_id, source_symbol_key, target_symbol_key,
                     relation_type, evidence_file_path)
                VALUES (?, ?, ?, ?, ?, ?)
                """, graph.symbolDependencies().stream()
                .map(dependency -> new Object[]{
                        UUID.randomUUID(), repositoryId,
                        dependency.sourceSymbolKey(), dependency.targetSymbolKey(),
                        dependency.relationType(), dependency.evidenceFilePath()
                })
                .toList());
        batchInsert("""
                INSERT INTO code_file_dependencies
                    (id, repository_id, source_path, target_path, relation_type)
                VALUES (?, ?, ?, ?, ?)
                """, graph.fileDependencies().stream()
                .map(dependency -> new Object[]{
                        UUID.randomUUID(), repositoryId,
                        dependency.sourcePath(), dependency.targetPath(),
                        dependency.relationType()
                })
                .toList());
    }

    /**
     * Execute one batch INSERT in bounded chunks so large projections stay
     * well below driver parameter limits.
     */
    private void batchInsert(String sql, List<Object[]> rows) {
        for (int start = 0; start < rows.size(); start += BATCH_CHUNK_SIZE) {
            jdbcTemplate.batchUpdate(
                    sql,
                    rows.subList(
                            start,
                            Math.min(start + BATCH_CHUNK_SIZE, rows.size())
                    )
            );
        }
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    public void markReady(UUID repositoryId, String headCommit) {
        jdbcTemplate.update("""
                UPDATE git_repositories
                   SET sync_status = 'READY', last_synced_commit = ?,
                       projection_version = ?,
                       last_synced_at = ?, last_sync_error = NULL,
                       updated_at = ?
                 WHERE id = ?
                """, headCommit, CURRENT_PROJECTION_VERSION,
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
                repositoryId);
    }

    public void markFailed(UUID repositoryId, String error) {
        String safeError = error == null ? "未知同步错误"
                : error.substring(0, Math.min(error.length(), 1000));
        jdbcTemplate.update("""
                UPDATE git_repositories
                   SET sync_status = 'FAILED', last_sync_error = ?,
                       updated_at = ?
                 WHERE id = ?
                """, safeError, Timestamp.from(Instant.now()), repositoryId);
    }
}
