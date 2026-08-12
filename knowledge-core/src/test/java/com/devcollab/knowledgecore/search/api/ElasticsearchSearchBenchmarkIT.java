package com.devcollab.knowledgecore.search.api;

import com.devcollab.knowledgecore.common.outbox.application.OutboxRelayResult;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.client.RestClient;

@SpringBootTest(properties = {
        "spring.datasource.url=${devcollab.benchmark.db.url:jdbc:postgresql://localhost:5432/devcollab}",
        "spring.datasource.username=${devcollab.benchmark.db.username:devcollab}",
        "spring.datasource.password=${devcollab.benchmark.db.password:devcollab}",
        "devcollab.search.elasticsearch.enabled=true",
        "devcollab.search.elasticsearch.url=${devcollab.benchmark.elasticsearch.url:http://localhost:9200}",
        "devcollab.search.elasticsearch.index-name=${devcollab.benchmark.elasticsearch.index:devcollab-search-benchmark}"
})
@AutoConfigureMockMvc
@EnabledIfSystemProperty(
        named = "devcollab.search.benchmark",
        matches = "true"
)
class ElasticsearchSearchBenchmarkIT extends AbstractSearchBenchmarkIT {

    private final RestClient elasticsearch = RestClient.builder()
            .baseUrl(System.getProperty(
                    "devcollab.benchmark.elasticsearch.url",
                    "http://localhost:9200"
            ))
            .build();

    @Override
    protected String engineName() {
        return "elasticsearch";
    }

    @Override
    protected OutboxRelayResult relayOutboxIfNeeded() {
        // maxRetryCount=0 keeps the old pending-only relay semantics
        // (FAILED events require retry_count < 0, which never holds).
        return outboxRelayService.relayRetryableEvents(
                Integer.MAX_VALUE,
                0
        );
    }

    @Override
    protected void refreshSearchIndexIfNeeded() {
        elasticsearch.post()
                .uri("/{indexName}/_refresh", System.getProperty(
                        "devcollab.benchmark.elasticsearch.index",
                        "devcollab-search-benchmark"
                ))
                .retrieve()
                .toBodilessEntity();
    }
}
