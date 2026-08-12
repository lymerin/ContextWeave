package com.devcollab.knowledgecore.common.outbox.domain;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository {

    OutboxEvent save(OutboxEvent event);

    List<OutboxEvent> findPending(int limit);

    List<OutboxEvent> findRetryable(int maxRetryCount, int limit);

    void markPublished(UUID eventId);

    void markFailed(UUID eventId, String errorMessage);
}
