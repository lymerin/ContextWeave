package com.devcollab.knowledgecore.git.infrastructure;

import com.devcollab.knowledgecore.git.domain.CodeDocumentBinding;
import com.devcollab.knowledgecore.git.domain.CodeAnchorKind;
import com.devcollab.knowledgecore.git.domain.BindingRole;
import com.devcollab.knowledgecore.git.domain.CodeFileDependency;
import com.devcollab.knowledgecore.git.domain.CodeSymbol;
import com.devcollab.knowledgecore.git.domain.CodeSymbolDependency;
import com.devcollab.knowledgecore.git.domain.GitChange;
import com.devcollab.knowledgecore.git.domain.GitChangeType;
import com.devcollab.knowledgecore.git.domain.GitFileChangeType;
import com.devcollab.knowledgecore.git.domain.GitFileDiff;
import com.devcollab.knowledgecore.git.domain.GitKnowledgeRepository;
import com.devcollab.knowledgecore.git.domain.GitProvider;
import com.devcollab.knowledgecore.git.domain.GitRepository;
import com.devcollab.knowledgecore.git.domain.GitRepositoryFile;
import com.devcollab.knowledgecore.git.domain.GitRepositoryStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcGitKnowledgeRepository implements GitKnowledgeRepository {

    private static final RowMapper<GitRepository> REPOSITORY_MAPPER =
            (rs, rowNum) -> new GitRepository(
                    rs.getObject("id", UUID.class),
                    rs.getObject("workspace_id", UUID.class),
                    rs.getString("name"),
                    GitProvider.valueOf(rs.getString("provider")),
                    rs.getString("remote_url"),
                    rs.getString("default_branch"),
                    rs.getObject("created_by", UUID.class),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant(),
                    GitRepositoryStatus.valueOf(rs.getString("sync_status")),
                    rs.getString("last_synced_commit"),
                    rs.getTimestamp("last_synced_at") == null
                            ? null : rs.getTimestamp("last_synced_at").toInstant(),
                    rs.getString("last_sync_error")
            );

    private static final RowMapper<GitRepositoryFile> FILE_MAPPER =
            (rs, rowNum) -> new GitRepositoryFile(
                    rs.getObject("id", UUID.class),
                    rs.getObject("repository_id", UUID.class),
                    rs.getString("path"),
                    rs.getString("blob_sha"),
                    rs.getLong("size_bytes"),
                    rs.getString("language"),
                    rs.getString("content_text")
            );

    private static final RowMapper<CodeSymbol> SYMBOL_MAPPER =
            (rs, rowNum) -> new CodeSymbol(
                    rs.getObject("id", UUID.class),
                    rs.getObject("repository_id", UUID.class),
                    rs.getString("file_path"),
                    rs.getString("symbol_key"),
                    rs.getString("language"),
                    rs.getString("symbol_kind"),
                    rs.getString("qualified_name"),
                    rs.getString("simple_name"),
                    rs.getString("signature"),
                    rs.getString("parent_symbol_key"),
                    (Integer) rs.getObject("start_line"),
                    (Integer) rs.getObject("end_line")
            );

    private static final RowMapper<CodeSymbolDependency> SYMBOL_DEPENDENCY_MAPPER =
            (rs, rowNum) -> new CodeSymbolDependency(
                    rs.getObject("id", UUID.class),
                    rs.getObject("repository_id", UUID.class),
                    rs.getString("source_symbol_key"),
                    rs.getString("target_symbol_key"),
                    rs.getString("relation_type"),
                    rs.getString("evidence_file_path")
            );

    private static final RowMapper<CodeFileDependency> FILE_DEPENDENCY_MAPPER =
            (rs, rowNum) -> new CodeFileDependency(
                    rs.getObject("id", UUID.class),
                    rs.getObject("repository_id", UUID.class),
                    rs.getString("source_path"),
                    rs.getString("target_path"),
                    rs.getString("relation_type")
            );

    private static final RowMapper<GitChange> CHANGE_MAPPER =
            (rs, rowNum) -> new GitChange(
                    rs.getObject("id", UUID.class),
                    rs.getObject("repository_id", UUID.class),
                    GitChangeType.valueOf(rs.getString("change_type")),
                    rs.getString("external_id"),
                    rs.getString("title"),
                    rs.getString("commit_sha"),
                    rs.getString("base_ref"),
                    rs.getString("head_ref"),
                    rs.getString("author_name"),
                    rs.getString("author_email"),
                    rs.getTimestamp("authored_at") == null
                            ? null : rs.getTimestamp("authored_at").toInstant(),
                    rs.getString("committer_name"),
                    rs.getString("committer_email"),
                    rs.getString("parent_commit_sha"),
                    rs.getString("web_url"),
                    rs.getTimestamp("occurred_at").toInstant(),
                    rs.getTimestamp("created_at").toInstant()
            );

    private static final RowMapper<GitFileDiff> DIFF_MAPPER =
            (rs, rowNum) -> new GitFileDiff(
                    rs.getObject("id", UUID.class),
                    rs.getObject("git_change_id", UUID.class),
                    rs.getString("path"),
                    rs.getString("old_path"),
                    GitFileChangeType.valueOf(rs.getString("change_type")),
                    rs.getInt("additions"),
                    rs.getInt("deletions"),
                    rs.getBoolean("binary_file"),
                    rs.getString("patch_excerpt")
            );

    private static final RowMapper<CodeDocumentBinding> BINDING_MAPPER =
            (rs, rowNum) -> new CodeDocumentBinding(
                    rs.getObject("id", UUID.class),
                    rs.getObject("workspace_id", UUID.class),
                    rs.getObject("repository_id", UUID.class),
                    rs.getObject("document_id", UUID.class),
                    rs.getObject("block_id", UUID.class),
                    rs.getString("target_key"),
                    rs.getString("path_pattern"),
                    rs.getString("revision"),
                    CodeAnchorKind.valueOf(rs.getString("anchor_kind")),
                    rs.getString("symbol_key"),
                    (Integer) rs.getObject("start_line"),
                    (Integer) rs.getObject("end_line"),
                    BindingRole.valueOf(rs.getString("binding_role")),
                    rs.getInt("binding_ordinal"),
                    rs.getObject("created_by", UUID.class),
                    rs.getTimestamp("created_at").toInstant()
            );

    private final JdbcTemplate jdbcTemplate;

    public JdbcGitKnowledgeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public GitRepository saveRepository(GitRepository repository) {
        jdbcTemplate.update("""
                        INSERT INTO git_repositories
                            (id, workspace_id, name, provider, remote_url,
                             default_branch, created_by, created_at, updated_at,
                             sync_status, last_synced_commit, last_synced_at,
                             last_sync_error)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                repository.id(), repository.workspaceId(), repository.name(),
                repository.provider().name(), repository.remoteUrl(),
                repository.defaultBranch(), repository.createdBy(),
                Timestamp.from(repository.createdAt()),
                Timestamp.from(repository.updatedAt()),
                repository.syncStatus().name(), repository.lastSyncedCommit(),
                repository.lastSyncedAt() == null ? null
                        : Timestamp.from(repository.lastSyncedAt()),
                repository.lastSyncError());
        return repository;
    }

    @Override
    public Optional<GitRepository> findRepositoryById(UUID repositoryId) {
        return jdbcTemplate.query(
                "SELECT * FROM git_repositories WHERE id = ?",
                REPOSITORY_MAPPER,
                repositoryId
        ).stream().findFirst();
    }

    @Override
    public Optional<GitRepository> findRepositoryByRemoteUrl(
            UUID workspaceId,
            String remoteUrl
    ) {
        return jdbcTemplate.query("""
                        SELECT * FROM git_repositories
                         WHERE workspace_id = ? AND remote_url = ?
                        """, REPOSITORY_MAPPER, workspaceId, remoteUrl)
                .stream().findFirst();
    }

    @Override
    public List<GitRepository> findRepositoriesByWorkspaceId(UUID workspaceId) {
        return jdbcTemplate.query("""
                        SELECT * FROM git_repositories
                         WHERE workspace_id = ? ORDER BY created_at DESC
                        """, REPOSITORY_MAPPER, workspaceId);
    }

    @Override
    public void markRepositorySyncPending(UUID repositoryId, java.time.Instant updatedAt) {
        jdbcTemplate.update("""
                UPDATE git_repositories
                   SET sync_status = 'SYNC_PENDING',
                       last_sync_error = NULL,
                       updated_at = ?
                 WHERE id = ?
                """, Timestamp.from(updatedAt), repositoryId);
    }

    @Override
    public void deleteRepository(UUID repositoryId) {
        jdbcTemplate.update("DELETE FROM git_repositories WHERE id = ?", repositoryId);
    }

    @Override
    public List<GitRepositoryFile> findFilesByRepositoryId(UUID repositoryId) {
        return jdbcTemplate.query("""
                SELECT * FROM git_repository_files
                 WHERE repository_id = ?
                 ORDER BY path
                """, FILE_MAPPER, repositoryId);
    }

    @Override
    public Optional<GitRepositoryFile> findFileByRepositoryIdAndPath(
            UUID repositoryId,
            String path
    ) {
        return jdbcTemplate.query("""
                SELECT * FROM git_repository_files
                 WHERE repository_id = ? AND path = ?
                """, FILE_MAPPER, repositoryId, path).stream().findFirst();
    }

    @Override
    public List<CodeSymbol> findSymbolsByRepositoryId(
            UUID repositoryId,
            String filePath
    ) {
        if (filePath == null) {
            return jdbcTemplate.query("""
                    SELECT * FROM code_symbols
                     WHERE repository_id = ?
                     ORDER BY file_path, start_line, symbol_key
                    """, SYMBOL_MAPPER, repositoryId);
        }
        return jdbcTemplate.query("""
                SELECT * FROM code_symbols
                 WHERE repository_id = ? AND file_path = ?
                 ORDER BY start_line, symbol_key
                """, SYMBOL_MAPPER, repositoryId, filePath);
    }

    @Override
    public List<CodeSymbolDependency> findSymbolDependenciesByRepositoryId(
            UUID repositoryId,
            String filePath
    ) {
        if (filePath == null) {
            return jdbcTemplate.query("""
                    SELECT * FROM code_symbol_dependencies
                     WHERE repository_id = ?
                     ORDER BY source_symbol_key, target_symbol_key
                    """, SYMBOL_DEPENDENCY_MAPPER, repositoryId);
        }
        return jdbcTemplate.query("""
                SELECT * FROM code_symbol_dependencies
                 WHERE repository_id = ? AND evidence_file_path = ?
                 ORDER BY source_symbol_key, target_symbol_key
                """, SYMBOL_DEPENDENCY_MAPPER, repositoryId, filePath);
    }

    @Override
    public List<CodeFileDependency> findFileDependenciesByRepositoryId(
            UUID repositoryId,
            String filePath
    ) {
        if (filePath == null) {
            return jdbcTemplate.query("""
                    SELECT * FROM code_file_dependencies
                     WHERE repository_id = ?
                     ORDER BY source_path, target_path
                    """, FILE_DEPENDENCY_MAPPER, repositoryId);
        }
        return jdbcTemplate.query("""
                SELECT * FROM code_file_dependencies
                 WHERE repository_id = ?
                   AND (source_path = ? OR target_path = ?)
                 ORDER BY source_path, target_path
                """, FILE_DEPENDENCY_MAPPER, repositoryId, filePath, filePath);
    }

    @Override
    public GitChange saveChange(GitChange change) {
        jdbcTemplate.update("""
                        INSERT INTO git_changes
                            (id, repository_id, change_type, external_id, title,
                             commit_sha, base_ref, head_ref, author_name,
                             author_email, authored_at, committer_name,
                             committer_email, parent_commit_sha, web_url,
                             occurred_at, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                change.id(), change.repositoryId(), change.changeType().name(),
                change.externalId(), change.title(), change.commitSha(),
                change.baseRef(), change.headRef(), change.authorName(),
                change.authorEmail(), change.authoredAt() == null ? null
                        : Timestamp.from(change.authoredAt()),
                change.committerName(), change.committerEmail(),
                change.parentCommitSha(), change.webUrl(),
                Timestamp.from(change.occurredAt()),
                Timestamp.from(change.createdAt()));
        return change;
    }

    @Override
    public Optional<GitChange> findChangeById(UUID changeId) {
        return jdbcTemplate.query(
                "SELECT * FROM git_changes WHERE id = ?",
                CHANGE_MAPPER,
                changeId
        ).stream().findFirst();
    }

    @Override
    public Optional<GitChange> findChangeByExternalIdentity(
            UUID repositoryId,
            GitChangeType type,
            String externalId
    ) {
        return jdbcTemplate.query("""
                        SELECT * FROM git_changes
                         WHERE repository_id = ? AND change_type = ?
                           AND external_id = ?
                        """, CHANGE_MAPPER, repositoryId, type.name(), externalId)
                .stream().findFirst();
    }

    @Override
    public List<GitChange> findChangesByRepositoryId(UUID repositoryId) {
        return jdbcTemplate.query("""
                        SELECT * FROM git_changes
                         WHERE repository_id = ?
                         ORDER BY occurred_at DESC, created_at DESC
                        """, CHANGE_MAPPER, repositoryId);
    }

    @Override
    public void saveDiffs(List<GitFileDiff> diffs) {
        for (GitFileDiff diff : diffs) {
            jdbcTemplate.update("""
                            INSERT INTO git_file_diffs
                                (id, git_change_id, path, old_path, change_type,
                                 additions, deletions, binary_file, patch_excerpt)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    diff.id(), diff.gitChangeId(), diff.path(), diff.oldPath(),
                    diff.changeType().name(), diff.additions(), diff.deletions(),
                    diff.binaryFile(), diff.patchExcerpt());
        }
    }

    @Override
    public List<GitFileDiff> findDiffsByChangeId(UUID changeId) {
        return jdbcTemplate.query("""
                        SELECT * FROM git_file_diffs
                         WHERE git_change_id = ? ORDER BY path
                        """, DIFF_MAPPER, changeId);
    }

    @Override
    public CodeDocumentBinding saveBinding(CodeDocumentBinding binding) {
        jdbcTemplate.update("""
                        INSERT INTO code_document_bindings
                            (id, workspace_id, repository_id, document_id,
                             block_id, target_key, path_pattern, revision,
                             anchor_kind, symbol_key, start_line, end_line,
                             revision_key, symbol_key_identity, start_line_key,
                             end_line_key, binding_role, binding_ordinal, created_by, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                binding.id(), binding.workspaceId(), binding.repositoryId(),
                binding.documentId(), binding.blockId(), binding.targetKey(),
                binding.pathPattern(), binding.revision(), binding.anchorKind().name(),
                binding.symbolKey(), binding.startLine(), binding.endLine(),
                binding.revision() == null ? "" : binding.revision(),
                binding.symbolKey() == null ? "" : binding.symbolKey(),
                binding.startLine() == null ? 0 : binding.startLine(),
                binding.endLine() == null ? 0 : binding.endLine(),
                binding.bindingRole().name(), binding.bindingOrdinal(),
                binding.createdBy(), Timestamp.from(binding.createdAt()));
        return binding;
    }

    @Override
    public Optional<CodeDocumentBinding> findBindingById(UUID bindingId) {
        return jdbcTemplate.query(
                "SELECT * FROM code_document_bindings WHERE id = ?",
                BINDING_MAPPER,
                bindingId
        ).stream().findFirst();
    }

    @Override
    public Optional<CodeDocumentBinding> findBindingByIdForUpdate(UUID bindingId) {
        return jdbcTemplate.query(
                "SELECT * FROM code_document_bindings WHERE id = ? FOR UPDATE",
                BINDING_MAPPER,
                bindingId
        ).stream().findFirst();
    }

    @Override
    public Optional<CodeDocumentBinding> findExactBinding(
            UUID repositoryId,
            UUID documentId,
            UUID blockId,
            String pathPattern,
            String revision,
            CodeAnchorKind anchorKind,
            String symbolKey,
            Integer startLine,
            Integer endLine
    ) {
        String targetKey = blockId == null ? "DOCUMENT" : blockId.toString();
        return jdbcTemplate.query("""
                        SELECT * FROM code_document_bindings
                         WHERE repository_id = ?
                           AND document_id = ?
                           AND target_key = ?
                           AND path_pattern = ?
                           AND revision_key = ?
                           AND anchor_kind = ?
                           AND symbol_key_identity = ?
                           AND start_line_key = ?
                           AND end_line_key = ?
                        """,
                BINDING_MAPPER,
                repositoryId,
                documentId,
                targetKey,
                pathPattern,
                revision == null ? "" : revision,
                anchorKind.name(),
                symbolKey == null ? "" : symbolKey,
                startLine == null ? 0 : startLine,
                endLine == null ? 0 : endLine
        ).stream().findFirst();
    }

    @Override
    public List<CodeDocumentBinding> findBindingsByDocumentId(UUID documentId) {
                return jdbcTemplate.query("""
                        SELECT * FROM code_document_bindings
                         WHERE document_id = ? ORDER BY created_at DESC, id
                        """, BINDING_MAPPER, documentId);
    }

    @Override
    public List<CodeDocumentBinding> findBindingsByBlockId(UUID blockId) {
        return jdbcTemplate.query("""
                SELECT * FROM code_document_bindings
                 WHERE block_id = ? ORDER BY created_at DESC, id
                """, BINDING_MAPPER, blockId);
    }

    @Override
    public List<CodeDocumentBinding> findBindingsByRepositoryId(UUID repositoryId) {
                return jdbcTemplate.query("""
                        SELECT * FROM code_document_bindings
                         WHERE repository_id = ? ORDER BY created_at DESC, id
                        """, BINDING_MAPPER, repositoryId);
    }

    @Override
    public boolean deleteBinding(UUID bindingId) {
        return jdbcTemplate.update(
                "DELETE FROM code_document_bindings WHERE id = ?",
                bindingId
        ) == 1;
    }
}
