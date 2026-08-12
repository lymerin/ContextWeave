package com.devcollab.knowledgecore.auth.infrastructure;

import com.devcollab.knowledgecore.auth.domain.UserAccount;
import com.devcollab.knowledgecore.auth.domain.UserStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcUserRepository {

    private static final RowMapper<UserAccount> USER_ROW_MAPPER = (rs, rowNum) ->
            new UserAccount(
                    rs.getObject("id", UUID.class),
                    rs.getString("username"),
                    rs.getString("normalized_username"),
                    rs.getString("display_name"),
                    rs.getString("password_hash"),
                    UserStatus.valueOf(rs.getString("status")),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant()
            );

    private final JdbcTemplate jdbcTemplate;

    public JdbcUserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<UserAccount> findById(UUID userId) {
        return jdbcTemplate.query(
                "SELECT * FROM user_accounts WHERE id = ?",
                USER_ROW_MAPPER,
                userId
        ).stream().findFirst();
    }

    public Optional<UserAccount> findByNormalizedUsername(String username) {
        return jdbcTemplate.query(
                "SELECT * FROM user_accounts WHERE normalized_username = ?",
                USER_ROW_MAPPER,
                username
        ).stream().findFirst();
    }

    public boolean existsByNormalizedUsername(String username) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_accounts WHERE normalized_username = ?",
                Long.class,
                username
        );
        return count != null && count > 0;
    }

    public UserAccount save(UserAccount user) {
        int updated = jdbcTemplate.update("""
                        UPDATE user_accounts
                           SET username = ?, normalized_username = ?, display_name = ?,
                               password_hash = ?, status = ?, updated_at = ?
                         WHERE id = ?
                        """,
                user.username(), user.normalizedUsername(), user.displayName(),
                user.passwordHash(), user.status().name(),
                Timestamp.from(user.updatedAt()), user.id());

        if (updated == 0) {
            jdbcTemplate.update("""
                            INSERT INTO user_accounts
                                (id, username, normalized_username, display_name,
                                 password_hash, status, created_at, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    user.id(), user.username(), user.normalizedUsername(),
                    user.displayName(), user.passwordHash(), user.status().name(),
                    Timestamp.from(user.createdAt()), Timestamp.from(user.updatedAt()));
        }
        return user;
    }
}
