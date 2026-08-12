package com.devcollab.knowledgecore.common.outbox.infrastructure;

import com.devcollab.knowledgecore.common.outbox.domain.OutboxEvent;
import com.devcollab.knowledgecore.common.outbox.domain.OutboxEventRepository;
import com.devcollab.knowledgecore.common.outbox.domain.OutboxEventStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcOutboxEventRepository implements OutboxEventRepository {

    private static final RowMapper<OutboxEvent> OUTBOX_EVENT_ROW_MAPPER =
            (rs, rowNum) -> new OutboxEvent(
                    rs.getObject("id", UUID.class),
                    rs.getString("aggregate_type"),
                    rs.getObject("aggregate_id", UUID.class),
                    rs.getString("event_type"),
                    rs.getString("payload"),
                    OutboxEventStatus.valueOf(rs.getString("status")),
                    rs.getTimestamp("occurred_at").toInstant(),
                    toInstant(rs.getTimestamp("published_at")),
                    rs.getInt("retry_count"),
                    rs.getString("last_error")
            );

    private final JdbcTemplate jdbcTemplate;

    public JdbcOutboxEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public OutboxEvent save(OutboxEvent event) {
        jdbcTemplate.update("""
                        INSERT INTO outbox_events
                            (id, aggregate_type, aggregate_id, event_type,
                             payload, status, occurred_at, published_at,
                             retry_count, last_error)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                event.id(),
                event.aggregateType(),
                event.aggregateId(),
                event.eventType(),
                event.payload(),
                event.status().name(),
                Timestamp.from(event.occurredAt()),
                event.publishedAt() == null
                        ? null
                        : Timestamp.from(event.publishedAt()),
                event.retryCount(),
                event.lastError());
        return event;
    }

    @Override
    public List<OutboxEvent> findPending(int limit) {
        return jdbcTemplate.query("""
                        SELECT * FROM outbox_events
                         WHERE status = ?
                         ORDER BY occurred_at, id
                         LIMIT ?
                        """,
                OUTBOX_EVENT_ROW_MAPPER,
                OutboxEventStatus.PENDING.name(),
                limit
        );
    }

    @Override
    public List<OutboxEvent> findRetryable(int maxRetryCount, int limit) {
        return jdbcTemplate.query("""
                        SELECT * FROM outbox_events
                         WHERE status = ?
                            OR (status = ? AND retry_count < ?)
                         ORDER BY occurred_at, id
                         LIMIT ?
                        """,
                OUTBOX_EVENT_ROW_MAPPER,
                OutboxEventStatus.PENDING.name(),
                OutboxEventStatus.FAILED.name(),
                maxRetryCount,
                limit
        );
    }

    @Override
    public void markPublished(UUID eventId) {
        jdbcTemplate.update("""
                        UPDATE outbox_events
                           SET status = ?,
                               published_at = ?,
                               last_error = NULL
                         WHERE id = ?
                        """,
                OutboxEventStatus.PUBLISHED.name(),
                Timestamp.from(Instant.now()),
                eventId
        );
    }

    @Override
    public void markFailed(UUID eventId, String errorMessage) {
        jdbcTemplate.update("""
                        UPDATE outbox_events
                           SET status = ?,
                               retry_count = retry_count + 1,
                               last_error = ?
                         WHERE id = ?
                        """,
                OutboxEventStatus.FAILED.name(),
                errorMessage,
                eventId
        );
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
