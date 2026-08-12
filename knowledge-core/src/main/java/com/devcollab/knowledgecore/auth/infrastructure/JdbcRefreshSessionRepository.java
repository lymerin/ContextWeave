package com.devcollab.knowledgecore.auth.infrastructure;

import com.devcollab.knowledgecore.auth.domain.RefreshSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcRefreshSessionRepository {

    private static final RowMapper<RefreshSession> SESSION_ROW_MAPPER =
            (rs, rowNum) -> new RefreshSession(
                    rs.getObject("id", UUID.class),
                    rs.getObject("user_id", UUID.class),
                    rs.getString("refresh_token_hash"),
                    rs.getString("csrf_token_hash"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("expires_at").toInstant()
            );

    private final JdbcTemplate jdbcTemplate;

    public JdbcRefreshSessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public RefreshSession save(RefreshSession session) {
        jdbcTemplate.update("""
                        INSERT INTO refresh_sessions
                            (id, user_id, refresh_token_hash, csrf_token_hash,
                             created_at, expires_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                session.id(), session.userId(), session.refreshTokenHash(),
                session.csrfTokenHash(), Timestamp.from(session.createdAt()),
                Timestamp.from(session.expiresAt()));
        return session;
    }

    public Optional<RefreshSession> findActiveByTokenHash(
            String refreshTokenHash,
            Instant now
    ) {
        return jdbcTemplate.query("""
                        SELECT * FROM refresh_sessions
                         WHERE refresh_token_hash = ? AND expires_at > ?
                        """,
                SESSION_ROW_MAPPER,
                refreshTokenHash,
                Timestamp.from(now)
        ).stream().findFirst();
    }

    public boolean consume(String refreshTokenHash, UUID sessionId) {
        return jdbcTemplate.update("""
                        DELETE FROM refresh_sessions
                         WHERE refresh_token_hash = ? AND id = ?
                        """,
                refreshTokenHash,
                sessionId
        ) == 1;
    }

    public void revoke(String refreshTokenHash) {
        jdbcTemplate.update(
                "DELETE FROM refresh_sessions WHERE refresh_token_hash = ?",
                refreshTokenHash
        );
    }
}
