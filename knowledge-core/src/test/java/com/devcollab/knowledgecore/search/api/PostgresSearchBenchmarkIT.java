package com.devcollab.knowledgecore.search.api;

import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.datasource.url=${devcollab.benchmark.db.url:jdbc:postgresql://localhost:5432/devcollab}",
        "spring.datasource.username=${devcollab.benchmark.db.username:devcollab}",
        "spring.datasource.password=${devcollab.benchmark.db.password:devcollab}",
        "devcollab.search.elasticsearch.enabled=false"
})
@AutoConfigureMockMvc
@EnabledIfSystemProperty(
        named = "devcollab.search.benchmark",
        matches = "true"
)
class PostgresSearchBenchmarkIT extends AbstractSearchBenchmarkIT {

    @Override
    protected String engineName() {
        return "postgres";
    }
}
