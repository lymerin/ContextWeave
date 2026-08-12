package com.devcollab.worker.git;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class GitRepositoryProjectionStore {

    /**
     * Projection format version. Bump when code graph semantics, commit
     * projection semantics, the effective {@code max-commits} range, or a
     * schema/algorithm upgrade requires rebuilding projections for the same
     * HEAD.
     */
    public static final int CURRENT_PROJECTION_VERSION = 1;

    private final JdbcTemplate jdbcTemplate;

    public GitRepositoryProjectionStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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

    public boolean markSyncing(UUID repositoryId) {
        return jdbcTemplate.update("""
                UPDATE git_repositories
                   SET sync_status = 'SYNCING', last_sync_error = NULL,
                       updated_at = ?
                 WHERE id = ?
                """, Timestamp.from(Instant.now()), repositoryId) == 1;
    }

    @Transactional
    public void replaceFiles(
            UUID repositoryId,
            List<GitRepositoryFileProjection> files
    ) {
        jdbcTemplate.update(
                "DELETE FROM git_repository_files WHERE repository_id = ?",
                repositoryId
        );
        for (GitRepositoryFileProjection file : files) {
            jdbcTemplate.update("""
                    INSERT INTO git_repository_files
                        (id, repository_id, path, blob_sha, size_bytes, language,
                         content_text)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), repositoryId, file.path(),
                    file.blobSha(), file.sizeBytes(), file.language(),
                    file.contentText());
        }
    }

    @Transactional
    public void saveCommits(UUID repositoryId, List<GitCommitProjection> commits) {
        for (GitCommitProjection commit : commits) {
            List<UUID> existingIds = jdbcTemplate.query("""
                    SELECT id FROM git_changes
                     WHERE repository_id = ? AND change_type = 'COMMIT'
                       AND external_id = ?
                    """, (rs, rowNum) -> rs.getObject("id", UUID.class),
                    repositoryId, commit.commitSha());
            UUID changeId;
            if (existingIds.isEmpty()) {
                changeId = UUID.randomUUID();
                jdbcTemplate.update("""
                        INSERT INTO git_changes
                            (id, repository_id, change_type, external_id, title,
                             commit_sha, author_name, author_email, authored_at,
                             committer_name, committer_email, parent_commit_sha,
                             occurred_at, created_at)
                        VALUES (?, ?, 'COMMIT', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, changeId, repositoryId, commit.commitSha(),
                        commit.title(), commit.commitSha(), commit.authorName(),
                        commit.authorEmail(), timestamp(commit.authoredAt()),
                        commit.committerName(), commit.committerEmail(),
                        commit.parentCommitSha(), Timestamp.from(commit.committedAt()),
                        Timestamp.from(Instant.now()));
            } else {
                changeId = existingIds.getFirst();
                jdbcTemplate.update("""
                        UPDATE git_changes
                           SET title = ?, commit_sha = ?, author_name = ?,
                               author_email = ?, authored_at = ?,
                               committer_name = ?, committer_email = ?,
                               parent_commit_sha = ?, occurred_at = ?
                         WHERE id = ?
                        """, commit.title(), commit.commitSha(), commit.authorName(),
                        commit.authorEmail(), timestamp(commit.authoredAt()),
                        commit.committerName(), commit.committerEmail(),
                        commit.parentCommitSha(), Timestamp.from(commit.committedAt()),
                        changeId);
                jdbcTemplate.update(
                        "DELETE FROM git_file_diffs WHERE git_change_id = ?",
                        changeId
                );
            }
            for (GitDiffProjection file : commit.files()) {
                jdbcTemplate.update("""
                        INSERT INTO git_file_diffs
                            (id, git_change_id, path, old_path, change_type,
                             additions, deletions, binary_file, patch_excerpt)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, UUID.randomUUID(), changeId, file.path(),
                        file.oldPath(), file.changeType(), file.additions(),
                        file.deletions(), file.binaryFile(), file.patchExcerpt());
            }
        }
    }

    @Transactional
    public void replaceCodeGraph(
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
        for (CodeSymbolProjection symbol : graph.symbols()) {
            jdbcTemplate.update("""
                    INSERT INTO code_symbols
                        (id, repository_id, file_path, symbol_key, language,
                         symbol_kind, qualified_name, simple_name, signature,
                         parent_symbol_key, start_line, end_line)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), repositoryId, symbol.filePath(),
                    symbol.symbolKey(), symbol.language(), symbol.symbolKind(),
                    symbol.qualifiedName(), symbol.simpleName(), symbol.signature(),
                    symbol.parentSymbolKey(), symbol.startLine(), symbol.endLine());
        }
        for (CodeSymbolDependencyProjection dependency
                : graph.symbolDependencies()) {
            jdbcTemplate.update("""
                    INSERT INTO code_symbol_dependencies
                        (id, repository_id, source_symbol_key, target_symbol_key,
                         relation_type, evidence_file_path)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), repositoryId,
                    dependency.sourceSymbolKey(), dependency.targetSymbolKey(),
                    dependency.relationType(), dependency.evidenceFilePath());
        }
        for (CodeFileDependencyProjection dependency : graph.fileDependencies()) {
            jdbcTemplate.update("""
                    INSERT INTO code_file_dependencies
                        (id, repository_id, source_path, target_path, relation_type)
                    VALUES (?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), repositoryId,
                    dependency.sourcePath(), dependency.targetPath(),
                    dependency.relationType());
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
