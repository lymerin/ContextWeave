package com.devcollab.worker.search.projection;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocProjectionServiceTests {

    private final ElasticsearchSearchIndexGateway searchIndexGateway =
            mock(ElasticsearchSearchIndexGateway.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final DocProjectionService projectionService =
            new DocProjectionService(searchIndexGateway, jdbcTemplate);

    @Test
    void missingRequiredPayloadFieldFailsWithReadableError() {
        UUID eventId = UUID.randomUUID();

        assertThatThrownBy(() -> projectionService.project(
                eventId,
                "DOCUMENT_CREATED",
                "{\"documentId\":\"6ed37fd7-39bb-4b81-ae0d-59b7533ed6d0\"}"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing required field")
                .hasMessageContaining("workspaceId");
    }

    @Test
    void invalidUuidPayloadFieldFailsWithReadableError() {
        UUID eventId = UUID.randomUUID();

        assertThatThrownBy(() -> projectionService.project(
                eventId,
                "DOCUMENT_CREATED",
                """
                {
                  "workspaceId": "bad-uuid",
                  "documentId": "6ed37fd7-39bb-4b81-ae0d-59b7533ed6d0",
                  "title": "示例文档",
                  "updatedAt": "2026-07-15T10:00:00Z"
                }
                """
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a UUID")
                .hasMessageContaining("workspaceId");
    }

    @Test
    void placeholderBlockIsDeletedFromSearchIndexInsteadOfUpserted() {
        UUID workspaceId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID blockId = UUID.randomUUID();
        Instant updatedAt = Instant.parse("2026-08-12T00:00:00Z");

        when(jdbcTemplate.queryForMap(
                "SELECT workspace_id, title FROM documents WHERE id = ?",
                documentId
        )).thenReturn(Map.of(
                "workspace_id", workspaceId,
                "title", "Order module"
        ));
        when(jdbcTemplate.queryForMap(
                "SELECT text, sort_order, updated_at FROM document_blocks WHERE id = ?",
                blockId
        )).thenReturn(Map.of(
                "text",
                "Module overview\n"
                        + "\u23f3 \u672c\u6761\u76ee\u7531\u7cfb\u7edf"
                        + "\u81ea\u52a8\u751f\u6210\u4e2d\uff08\u6279\u6b21"
                        + "\u586b\u5145\u540e\u81ea\u52a8\u66ff\u6362\uff09",
                "sort_order", 0,
                "updated_at", Timestamp.from(updatedAt)
        ));

        projectionService.project(
                UUID.randomUUID(),
                "DOCUMENT_BLOCK_CREATED",
                """
                {
                  "documentId": "%s",
                  "blockId": "%s"
                }
                """.formatted(documentId, blockId)
        );

        verify(searchIndexGateway).ensureIndex();
        verify(searchIndexGateway).deleteByIndexId(
                SearchIndexEntry.blockIndexId(blockId)
        );
        verify(searchIndexGateway, never()).upsert(any());
    }
}
