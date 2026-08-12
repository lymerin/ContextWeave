package com.devcollab.knowledgecore.common.outbox;

import com.devcollab.knowledgecore.common.outbox.application.OutboxEventTypes;
import com.devcollab.knowledgecore.common.outbox.application.OutboxKafkaMessage;
import com.devcollab.knowledgecore.common.outbox.application.OutboxTopicRouter;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxTopicRouterTests {

    private final OutboxTopicRouter router = new OutboxTopicRouter(
            "devcollab.document.events",
            "devcollab.cache.events",
            "devcollab.review.events",
            "devcollab.notification.events",
            "devcollab.git.events"
    );

    @Test
    void routesDocumentEventsToDocumentTopic() {
        assertThat(route("DOCUMENT_CREATED"))
                .containsExactly("devcollab.document.events");
        assertThat(route(OutboxEventTypes.DOCUMENT_OPERATION_APPLIED))
                .containsExactly("devcollab.document.events");
        assertThat(route(OutboxEventTypes.SNAPSHOT_REQUESTED))
                .containsExactly("devcollab.document.events");
    }

    @Test
    void routesCacheInvalidationToCacheTopic() {
        assertThat(route(OutboxEventTypes.CACHE_INVALIDATED))
                .containsExactly("devcollab.cache.events");
    }

    @Test
    void routesSemanticReviewEventsToReviewAndNotificationTopics() {
        assertThat(route(OutboxEventTypes.REVIEW_REQUESTED))
                .containsExactly(
                        "devcollab.review.events",
                        "devcollab.notification.events"
                );
    }

    @Test
    void routesLegacyReviewEventsOnlyToReviewTopic() {
        assertThat(route("DOCUMENT_REVIEW_SUBMITTED"))
                .containsExactly("devcollab.review.events");
    }

    @Test
    void routesGitChangesToGitTopic() {
        assertThat(route(OutboxEventTypes.GIT_CHANGE_SYNCED))
                .containsExactly("devcollab.git.events");
        assertThat(route(OutboxEventTypes.GIT_REPOSITORY_SYNC_REQUESTED))
                .containsExactly("devcollab.git.events");
        assertThat(route(OutboxEventTypes.GIT_REPOSITORY_DELETE_REQUESTED))
                .containsExactly("devcollab.git.events");
    }

    private java.util.List<String> route(String eventType) {
        return router.route(new OutboxKafkaMessage(
                UUID.randomUUID(),
                "DOCUMENT",
                UUID.randomUUID(),
                eventType,
                "{}",
                Instant.now()
        ));
    }
}
