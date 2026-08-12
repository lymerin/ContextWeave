package com.devcollab.knowledgecore.search.projection;

import com.devcollab.knowledgecore.search.domain.SearchHit;
import com.devcollab.knowledgecore.search.domain.SearchScope;

import java.util.List;
import java.util.UUID;

public interface SearchIndexGateway {

    void ensureIndex();

    List<SearchHit> searchWorkspace(
            UUID workspaceId,
            String keyword,
            SearchScope scope,
            int limit
    );
}
