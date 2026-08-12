package com.devcollab.knowledgecore.common.outbox;

import com.devcollab.knowledgecore.common.outbox.application.OutboxKafkaPublisher;
import com.devcollab.knowledgecore.common.outbox.application.OutboxRelayResult;
import com.devcollab.knowledgecore.common.outbox.application.OutboxRelayService;
import com.devcollab.knowledgecore.common.outbox.domain.OutboxEvent;
import com.devcollab.knowledgecore.common.outbox.domain.OutboxEventRepository;
import com.devcollab.knowledgecore.common.outbox.domain.OutboxEventStatus;
import com.devcollab.knowledgecore.common.outbox.worker.OutboxWorkerProperties;
import com.devcollab.knowledgecore.common.outbox.worker.OutboxWorkerScheduler;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OutboxWorkerSchedulerTests {

    @Test
    void shouldRelayPendingEventsWithConfiguredBatchSize() {
        InMemoryOutboxEventRepository repository =
                new InMemoryOutboxEventRepository();
        OutboxEvent first = repository.save(pendingEvent(
                "DOCUMENT_CREATED",
                Instant.parse("2026-07-14T00:00:00Z")
        ));
        OutboxEvent second = repository.save(pendingEvent(
                "DOCUMENT_UPDATED",
                Instant.parse("2026-07-14T00:00:01Z")
        ));
        OutboxRelayService relayService = new OutboxRelayService(
                repository,
                mock(OutboxKafkaPublisher.class)
        );
        OutboxWorkerScheduler scheduler = new OutboxWorkerScheduler(
                relayService,
                new OutboxWorkerProperties(true, 1, 3)
        );

        OutboxRelayResult result = scheduler.runOnce();

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.published()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        assertThat(repository.findById(first.id()).status())
                .isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(repository.findById(second.id()).status())
                .isEqualTo(OutboxEventStatus.PENDING);
    }

    @Test
    void shouldRetryFailedEventsBelowMaxRetryCount() {
        InMemoryOutboxEventRepository repository =
                new InMemoryOutboxEventRepository();
        OutboxEvent failed = repository.save(failedEvent(
                "DOCUMENT_UPDATED",
                Instant.parse("2026-07-14T00:00:00Z"),
                1
        ));
        OutboxRelayService relayService = new OutboxRelayService(
                repository,
                mock(OutboxKafkaPublisher.class)
        );
        OutboxWorkerScheduler scheduler = new OutboxWorkerScheduler(
                relayService,
                new OutboxWorkerProperties(true, 10, 3)
        );

        OutboxRelayResult result = scheduler.runOnce();

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.published()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        assertThat(repository.findById(failed.id()).status())
                .isEqualTo(OutboxEventStatus.PUBLISHED);
    }

    @Test
    void shouldSkipFailedEventsAtMaxRetryCount() {
        InMemoryOutboxEventRepository repository =
                new InMemoryOutboxEventRepository();
        OutboxEvent exhausted = repository.save(failedEvent(
                "DOCUMENT_UPDATED",
                Instant.parse("2026-07-14T00:00:00Z"),
                3
        ));
        OutboxRelayService relayService = new OutboxRelayService(
                repository,
                mock(OutboxKafkaPublisher.class)
        );
        OutboxWorkerScheduler scheduler = new OutboxWorkerScheduler(
                relayService,
                new OutboxWorkerProperties(true, 10, 3)
        );

        OutboxRelayResult result = scheduler.runOnce();

        assertThat(result.scanned()).isZero();
        assertThat(result.published()).isZero();
        assertThat(result.failed()).isZero();
        assertThat(repository.findById(exhausted.id()).status())
                .isEqualTo(OutboxEventStatus.FAILED);
        assertThat(repository.findById(exhausted.id()).retryCount())
                .isEqualTo(3);
    }

    private OutboxEvent pendingEvent(String eventType, Instant occurredAt) {
        return OutboxEvent.pending(
                "DOCUMENT",
                UUID.randomUUID(),
                eventType,
                "{}",
                occurredAt
        );
    }

    private OutboxEvent failedEvent(
            String eventType,
            Instant occurredAt,
            int retryCount
    ) {
        return new OutboxEvent(
                UUID.randomUUID(),
                "DOCUMENT",
                UUID.randomUUID(),
                eventType,
                "{}",
                OutboxEventStatus.FAILED,
                occurredAt,
                null,
                retryCount,
                "previous failure"
        );
    }

    private static final class InMemoryOutboxEventRepository
            implements OutboxEventRepository {

        private final List<OutboxEvent> events = new ArrayList<>();

        @Override
        public OutboxEvent save(OutboxEvent event) {
            events.add(event);
            return event;
        }

        @Override
        public List<OutboxEvent> findPending(int limit) {
            return events.stream()
                    .filter(event -> event.status()
                            == OutboxEventStatus.PENDING)
                    .sorted(Comparator
                            .comparing(OutboxEvent::occurredAt)
                            .thenComparing(OutboxEvent::id))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<OutboxEvent> findRetryable(int maxRetryCount, int limit) {
            return events.stream()
                    .filter(event -> event.status()
                            == OutboxEventStatus.PENDING
                            || (event.status() == OutboxEventStatus.FAILED
                            && event.retryCount() < maxRetryCount))
                    .sorted(Comparator
                            .comparing(OutboxEvent::occurredAt)
                            .thenComparing(OutboxEvent::id))
                    .limit(limit)
                    .toList();
        }

        @Override
        public void markPublished(UUID eventId) {
            replace(eventId, current -> new OutboxEvent(
                    current.id(),
                    current.aggregateType(),
                    current.aggregateId(),
                    current.eventType(),
                    current.payload(),
                    OutboxEventStatus.PUBLISHED,
                    current.occurredAt(),
                    Instant.now(),
                    current.retryCount(),
                    null
            ));
        }

        @Override
        public void markFailed(UUID eventId, String errorMessage) {
            replace(eventId, current -> new OutboxEvent(
                    current.id(),
                    current.aggregateType(),
                    current.aggregateId(),
                    current.eventType(),
                    current.payload(),
                    OutboxEventStatus.FAILED,
                    current.occurredAt(),
                    current.publishedAt(),
                    current.retryCount() + 1,
                    errorMessage
            ));
        }

        private OutboxEvent findById(UUID eventId) {
            return events.stream()
                    .filter(event -> event.id().equals(eventId))
                    .findFirst()
                    .orElseThrow();
        }

        private void replace(
                UUID eventId,
                java.util.function.Function<OutboxEvent, OutboxEvent> mapper
        ) {
            for (int index = 0; index < events.size(); index++) {
                OutboxEvent current = events.get(index);
                if (current.id().equals(eventId)) {
                    events.set(index, mapper.apply(current));
                    return;
                }
            }
            throw new IllegalArgumentException("Event not found");
        }
    }
}
