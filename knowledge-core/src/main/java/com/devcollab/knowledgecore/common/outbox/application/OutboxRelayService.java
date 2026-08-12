package com.devcollab.knowledgecore.common.outbox.application;

import com.devcollab.knowledgecore.common.outbox.domain.OutboxEvent;
import com.devcollab.knowledgecore.common.outbox.domain.OutboxEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class OutboxRelayService {

    private final OutboxEventRepository eventRepository;
    private final OutboxKafkaPublisher messagePublisher;

    public OutboxRelayService(
            OutboxEventRepository eventRepository,
            OutboxKafkaPublisher messagePublisher
    ) {
        this.eventRepository = eventRepository;
        this.messagePublisher = messagePublisher;
    }

    @Transactional
    public OutboxRelayResult relayRetryableEvents(
            int batchSize,
            int maxRetryCount
    ) {
        List<OutboxEvent> pendingEvents = eventRepository.findRetryable(
                maxRetryCount,
                batchSize
        );
        return relayEvents(pendingEvents);
    }

    private OutboxRelayResult relayEvents(List<OutboxEvent> pendingEvents) {
        int published = 0;
        int failed = 0;

        for (OutboxEvent event : pendingEvents) {
            try {
                messagePublisher.publish(OutboxKafkaMessage.from(event));
                eventRepository.markPublished(event.id());
                published++;
            } catch (RuntimeException exception) {
                eventRepository.markFailed(event.id(), safeMessage(exception));
                failed++;
            }
        }

        return new OutboxRelayResult(
                pendingEvents.size(),
                published,
                failed
        );
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() <= 500
                ? message
                : message.substring(0, 500);
    }
}
