package com.devcollab.knowledgecore.common.outbox.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Publishes outbox events to Kafka.
 *
 * <p>This is the only exit point out of Core. There is no fallback to local
 * handlers when Kafka is unavailable: events stay in {@code outbox_events}
 * with FAILED status and are retried by the Outbox Relay.
 */
@Component
public class OutboxKafkaPublisher {

    private static final Logger log =
            LoggerFactory.getLogger(OutboxKafkaPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxTopicRouter topicRouter;
    private final ObjectMapper objectMapper;

    public OutboxKafkaPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            OutboxTopicRouter topicRouter,
            ObjectMapper objectMapper
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.topicRouter = topicRouter;
        this.objectMapper = objectMapper;
    }

    /**
     * Publishes a single outbox event to Kafka synchronously.
     * Kafka ACK must succeed before the caller marks published_at.
     *
     * @param event the outbox event to publish
     * @throws RuntimeException wrapping the original failure if Kafka send fails
     */
    public void publish(OutboxKafkaMessage event) {
        String key = event.kafkaKey();
        String value;
        try {
            value = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Failed to serialize outbox event to JSON: eventId="
                            + event.eventId(),
                    e
            );
        }

        List<String> topics = topicRouter.route(event);
        for (String topic : topics) {
            publishToTopic(event, topic, key, value);
        }
    }

    private void publishToTopic(
            OutboxKafkaMessage event,
            String topic,
            String key,
            String value
    ) {
        try {
            var future = kafkaTemplate.send(topic, key, value);
            future.get();
            log.debug(
                    "Outbox event {} published to Kafka topic {}",
                    event.eventId(),
                    topic
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(
                    "Kafka send interrupted for event " + event.eventId()
                            + " topic=" + topic,
                    e
            );
        } catch (ExecutionException e) {
            throw new RuntimeException(
                    "Kafka send failed for event " + event.eventId()
                            + " topic=" + topic,
                    e.getCause()
            );
        }
    }
}
