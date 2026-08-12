package com.devcollab.knowledgecore.search.infrastructure;

import com.devcollab.knowledgecore.search.domain.SearchHit;
import com.devcollab.knowledgecore.search.domain.SearchRepository;
import com.devcollab.knowledgecore.search.domain.SearchScope;
import com.devcollab.knowledgecore.search.projection.SearchIndexGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
@ConditionalOnProperty(
        name = "devcollab.search.elasticsearch.enabled",
        havingValue = "true"
)
public class ElasticsearchSearchRepository implements SearchRepository {

    private final SearchIndexGateway searchIndexGateway;

    public ElasticsearchSearchRepository(SearchIndexGateway searchIndexGateway) {
        this.searchIndexGateway = searchIndexGateway;
    }

    @Override
    public List<SearchHit> searchWorkspace(
            UUID workspaceId,
            String keyword,
            SearchScope scope,
            int limit
    ) {
        searchIndexGateway.ensureIndex();
        return searchIndexGateway.searchWorkspace(
                workspaceId,
                keyword,
                scope,
                limit
        );
    }
}
