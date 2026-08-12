package com.devcollab.knowledgecore.common.outbox.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * Routes semantic outbox events to Kafka topics.
 *
 * <p>One outbox event may be published to more than one topic. The relay marks
 * the outbox row as published only after all target topic sends have been
 * acknowledged by Kafka. If a later topic send fails, replay may duplicate an
 * already-sent record, and consumers rely on {@code consumer_inbox} idempotency.
 */
@Component
public class OutboxTopicRouter {

    private final String documentTopic;
    private final String cacheTopic;
    private final String reviewTopic;
    private final String notificationTopic;
    private final String gitTopic;

    public OutboxTopicRouter(
            @Value("${devcollab.outbox.kafka.document-topic:devcollab.document.events}")
            String documentTopic,
            @Value("${devcollab.outbox.kafka.cache-topic:devcollab.cache.events}")
            String cacheTopic,
            @Value("${devcollab.outbox.kafka.review-topic:devcollab.review.events}")
            String reviewTopic,
            @Value("${devcollab.outbox.kafka.notification-topic:devcollab.notification.events}")
            String notificationTopic,
            @Value("${devcollab.outbox.kafka.git-topic:devcollab.git.events}")
            String gitTopic
    ) {
        this.documentTopic = documentTopic;
        this.cacheTopic = cacheTopic;
        this.reviewTopic = reviewTopic;
        this.notificationTopic = notificationTopic;
        this.gitTopic = gitTopic;
    }

    public List<String> route(OutboxKafkaMessage event) {
        LinkedHashSet<String> topics = new LinkedHashSet<>();
        switch (event.eventType()) {
            case OutboxEventTypes.CACHE_INVALIDATED -> topics.add(cacheTopic);
            case OutboxEventTypes.REVIEW_REQUESTED,
                 OutboxEventTypes.REVIEW_COMPLETED,
                 OutboxEventTypes.REVIEW_FAILED -> {
                topics.add(reviewTopic);
                topics.add(notificationTopic);
            }
            case OutboxEventTypes.GIT_CHANGE_SYNCED,
                 OutboxEventTypes.GIT_REPOSITORY_SYNC_REQUESTED,
                 OutboxEventTypes.GIT_REPOSITORY_DELETE_REQUESTED ->
                    topics.add(gitTopic);
            case "DOCUMENT_REVIEW_SUBMITTED",
                 "DOCUMENT_REVIEW_APPROVED",
                 "DOCUMENT_REVIEW_REJECTED" -> topics.add(reviewTopic);
            default -> topics.add(documentTopic);
        }
        return List.copyOf(topics);
    }
}
