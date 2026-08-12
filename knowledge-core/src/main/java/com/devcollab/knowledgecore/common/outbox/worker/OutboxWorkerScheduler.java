package com.devcollab.knowledgecore.common.outbox.worker;

import com.devcollab.knowledgecore.common.outbox.application.OutboxRelayResult;
import com.devcollab.knowledgecore.common.outbox.application.OutboxRelayService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "devcollab.worker.outbox.enabled",
        havingValue = "true"
)
public class OutboxWorkerScheduler {

    private final OutboxRelayService relayService;
    private final OutboxWorkerProperties properties;

    public OutboxWorkerScheduler(
            OutboxRelayService relayService,
            OutboxWorkerProperties properties
    ) {
        this.relayService = relayService;
        this.properties = properties;
    }

    public OutboxRelayResult runOnce() {
        return relayService.relayRetryableEvents(
                properties.batchSize(),
                properties.maxRetryCount()
        );
    }

    @Scheduled(
            fixedDelayString = "${devcollab.worker.outbox.fixed-delay-ms:5000}",
            initialDelayString = "${devcollab.worker.outbox.initial-delay-ms:5000}"
    )
    public void relayOutboxEvents() {
        runOnce();
    }
}
