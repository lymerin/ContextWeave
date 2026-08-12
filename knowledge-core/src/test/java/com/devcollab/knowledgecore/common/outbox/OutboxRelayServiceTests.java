package com.devcollab.knowledgecore.common.outbox;

import com.devcollab.knowledgecore.common.outbox.application.OutboxKafkaMessage;
import com.devcollab.knowledgecore.common.outbox.application.OutboxKafkaPublisher;
import com.devcollab.knowledgecore.common.outbox.application.OutboxRelayResult;
import com.devcollab.knowledgecore.common.outbox.application.OutboxRelayService;
import com.devcollab.knowledgecore.common.outbox.domain.OutboxEvent;
import com.devcollab.knowledgecore.common.outbox.domain.OutboxEventRepository;
import com.devcollab.knowledgecore.common.outbox.domain.OutboxEventStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class OutboxRelayServiceTests {

    @Test
    void shouldPublishPendingEventsWhenPublisherSucceeds() {
        InMemoryOutboxEventRepository repository =
                new InMemoryOutboxEventRepository();
        OutboxEvent event = repository.save(pendingEvent("DOCUMENT_CREATED"));
        OutboxKafkaPublisher noopPublisher = mock(OutboxKafkaPublisher.class);
        OutboxRelayService relayService = new OutboxRelayService(
                repository,
                noopPublisher
        );

        OutboxRelayResult result = relayService.relayRetryableEvents(50, 3);

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.published()).isEqualTo(1);
        assertThat(result.failed()).isZero();

        OutboxEvent published = repository.findById(event.id());
        assertThat(published.status()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(published.publishedAt()).isNotNull();
        assertThat(published.lastError()).isNull();
    }

    @Test
    void shouldMarkEventFailedWhenPublisherThrows() {
        InMemoryOutboxEventRepository repository =
                new InMemoryOutboxEventRepository();
        OutboxEvent event = repository.save(pendingEvent("DOCUMENT_DELETED"));
        OutboxKafkaPublisher failingPublisher = mock(OutboxKafkaPublisher.class);
        doThrow(new IllegalStateException("simulated kafka failure"))
                .when(failingPublisher).publish(any());
        OutboxRelayService relayService = new OutboxRelayService(
                repository,
                failingPublisher
        );

        OutboxRelayResult result = relayService.relayRetryableEvents(50, 3);

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.published()).isZero();
        assertThat(result.failed()).isEqualTo(1);

        OutboxEvent failed = repository.findById(event.id());
        assertThat(failed.status()).isEqualTo(OutboxEventStatus.FAILED);
        assertThat(failed.retryCount()).isEqualTo(1);
        assertThat(failed.lastError())
                .isEqualTo("simulated kafka failure");
        assertThat(failed.publishedAt()).isNull();
    }

    private OutboxEvent pendingEvent(String eventType) {
        return OutboxEvent.pending(
                "DOCUMENT",
                UUID.randomUUID(),
                eventType,
                "{}",
                Instant.now()
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
