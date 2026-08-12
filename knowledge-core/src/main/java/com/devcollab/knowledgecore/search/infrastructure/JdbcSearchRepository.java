package com.devcollab.knowledgecore.search.infrastructure;

import com.devcollab.knowledgecore.search.domain.SearchHit;
import com.devcollab.knowledgecore.search.domain.SearchHitType;
import com.devcollab.knowledgecore.search.domain.SearchSnippetHighlighter;
import com.devcollab.knowledgecore.search.domain.SearchRepository;
import com.devcollab.knowledgecore.search.domain.SearchScope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

@Repository
@ConditionalOnProperty(
        name = "devcollab.search.elasticsearch.enabled",
        havingValue = "false",
        matchIfMissing = true
)
public class JdbcSearchRepository implements SearchRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcSearchRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<SearchHit> searchWorkspace(
            UUID workspaceId,
            String keyword,
            SearchScope scope,
            int limit
    ) {
        String pattern = "%" + keyword.toLowerCase() + "%";

        List<SearchHit> titleHits = scope.includesTitle()
                ? searchTitleHits(workspaceId, keyword, pattern)
                : List.of();

        List<SearchHit> blockHits = scope.includesContent()
                ? searchBlockHits(workspaceId, keyword, pattern)
                : List.of();

        return Stream.concat(titleHits.stream(), blockHits.stream())
                .sorted(Comparator
                        .comparingInt(JdbcSearchRepository::typeRank)
                        .thenComparing(SearchHit::updatedAt, Comparator.reverseOrder())
                        .thenComparing(SearchHit::documentTitle))
                .limit(limit)
                .toList();
    }

    private List<SearchHit> searchTitleHits(
            UUID workspaceId,
            String keyword,
            String pattern
    ) {
        return jdbcTemplate.query("""
                        SELECT id, title, updated_at
                          FROM documents
                         WHERE workspace_id = ?
                           AND LOWER(title) LIKE ?
                        """,
                (rs, rowNum) -> {
                    SearchSnippetHighlighter.Result snippet =
                            SearchSnippetHighlighter.create(
                                    rs.getString("title"),
                                    keyword
                            );
                    return new SearchHit(
                            SearchHitType.DOCUMENT_TITLE,
                            rs.getObject("id", UUID.class),
                            rs.getString("title"),
                            null,
                            snippet.snippet(),
                            snippet.highlights(),
                            rs.getTimestamp("updated_at").toInstant()
                    );
                },
                workspaceId,
                pattern
        );
    }

    private List<SearchHit> searchBlockHits(
            UUID workspaceId,
            String keyword,
            String pattern
    ) {
        return jdbcTemplate.query("""
                        SELECT d.id AS document_id,
                               d.title AS document_title,
                               b.id AS block_id,
                               b.text AS block_text,
                               b.updated_at AS updated_at
                          FROM document_blocks b
                          JOIN documents d ON d.id = b.document_id
                         WHERE d.workspace_id = ?
                           AND LOWER(b.text) LIKE ?
                         ORDER BY b.sort_order, b.id
                        """,
                (rs, rowNum) -> {
                    SearchSnippetHighlighter.Result snippet =
                            SearchSnippetHighlighter.create(
                                    rs.getString("block_text"),
                                    keyword
                            );
                    return new SearchHit(
                            SearchHitType.BLOCK_CONTENT,
                            rs.getObject("document_id", UUID.class),
                            rs.getString("document_title"),
                            rs.getObject("block_id", UUID.class),
                            snippet.snippet(),
                            snippet.highlights(),
                            rs.getTimestamp("updated_at").toInstant()
                    );
                },
                workspaceId,
                pattern
        );
    }

    private static int typeRank(SearchHit hit) {
        return hit.type() == SearchHitType.DOCUMENT_TITLE ? 0 : 1;
    }
}
