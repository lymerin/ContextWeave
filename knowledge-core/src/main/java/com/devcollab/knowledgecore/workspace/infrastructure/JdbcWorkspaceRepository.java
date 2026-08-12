package com.devcollab.knowledgecore.workspace.infrastructure;

import com.devcollab.knowledgecore.workspace.domain.Workspace;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcWorkspaceRepository {

    private static final RowMapper<Workspace> WORKSPACE_ROW_MAPPER =
            (rs, rowNum) -> new Workspace(
                    rs.getObject("id", UUID.class),
                    rs.getString("name"),
                    rs.getObject("created_by", UUID.class),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant()
            );

    private final JdbcTemplate jdbcTemplate;

    public JdbcWorkspaceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Workspace save(Workspace workspace) {
        int updated = jdbcTemplate.update("""
                        UPDATE workspaces SET name = ?, updated_at = ? WHERE id = ?
                        """,
                workspace.name(), Timestamp.from(workspace.updatedAt()),
                workspace.id());

        if (updated == 0) {
            jdbcTemplate.update("""
                            INSERT INTO workspaces
                                (id, name, created_by, created_at, updated_at)
                            VALUES (?, ?, ?, ?, ?)
                            """,
                    workspace.id(), workspace.name(), workspace.createdBy(),
                    Timestamp.from(workspace.createdAt()),
                    Timestamp.from(workspace.updatedAt()));
        }
        return workspace;
    }

    public Optional<Workspace> findById(UUID workspaceId) {
        return jdbcTemplate.query(
                "SELECT * FROM workspaces WHERE id = ?",
                WORKSPACE_ROW_MAPPER,
                workspaceId
        ).stream().findFirst();
    }

    public List<Workspace> findAllByUserId(UUID userId) {
        return jdbcTemplate.query("""
                        SELECT w.*
                          FROM workspaces w
                          JOIN workspace_members wm ON wm.workspace_id = w.id
                         WHERE wm.user_id = ?
                         ORDER BY w.created_at, w.id
                        """,
                WORKSPACE_ROW_MAPPER,
                userId
        );
    }

    public void deleteById(UUID workspaceId) {
        jdbcTemplate.update(
                "DELETE FROM workspaces WHERE id = ?",
                workspaceId
        );
    }
}
