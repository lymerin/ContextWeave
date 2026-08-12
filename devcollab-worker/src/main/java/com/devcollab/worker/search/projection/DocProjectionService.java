package com.devcollab.worker.search.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Projects outbox events to Elasticsearch search index.
 *
 * <p>This is a Worker-local replica of the projection logic
 * that previously lived in knowledge-core's
 * {@code SearchIndexProjectionService}. It queries the
 * {@code documents} and {@code document_blocks} tables directly
 * via JDBC to avoid depending on knowledge-core domain objects.
 */
@Service
@ConditionalOnProperty(
        name = "devcollab.search.elasticsearch.enabled",
        havingValue = "true"
)
public class DocProjectionService {

    private static final Logger log =
            LoggerFactory.getLogger(DocProjectionService.class);
    private static final String AGENT_PLACEHOLDER_MARKER =
            "\u23f3 \u672c\u6761\u76ee\u7531\u7cfb\u7edf\u81ea\u52a8\u751f\u6210\u4e2d"
                    + "\uff08\u6279\u6b21\u586b\u5145\u540e\u81ea\u52a8\u66ff\u6362\uff09";

    private final ElasticsearchSearchIndexGateway searchIndexGateway;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public DocProjectionService(
            ElasticsearchSearchIndexGateway searchIndexGateway,
            JdbcTemplate jdbcTemplate
    ) {
        this.searchIndexGateway = searchIndexGateway;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = new ObjectMapper();
    }

    public void project(UUID eventId, String eventType, String payload) {
        searchIndexGateway.ensureIndex();
        JsonNode json = readPayload(eventId, payload);

        switch (eventType) {
            case "DOCUMENT_CREATED",
                 "DOCUMENT_UPDATED",
                 "DOCUMENT_MOVED" -> projectDocument(json);
            case "DOCUMENT_DELETED" -> deleteDocument(json);
            case "DOCUMENT_OPERATION_APPLIED" ->
                    projectDocumentOperationApplied(json);
            case "DOCUMENT_BLOCK_CREATED",
                 "DOCUMENT_BLOCK_UPDATED",
                 "DOCUMENT_BLOCK_MOVED" -> projectBlock(json);
            case "DOCUMENT_BLOCK_DELETED" -> deleteBlock(json);
            default -> log.debug(
                    "Search projection ignoring event type {} (eventId={})",
                    eventType,
                    eventId
            );
        }
    }

    private void projectDocument(JsonNode payload) {
        UUID workspaceId = uuid(payload, "workspaceId");
        UUID documentId = uuid(payload, "documentId");
        String title = text(payload, "title");
        Instant updatedAt = instant(payload, "updatedAt");

        searchIndexGateway.upsert(SearchIndexEntry.documentTitle(
                workspaceId,
                documentId,
                title,
                updatedAt
        ));
        searchIndexGateway.updateDocumentTitle(documentId, title, updatedAt);
    }

    private void deleteDocument(JsonNode payload) {
        searchIndexGateway.deleteByDocumentId(uuid(payload, "documentId"));
    }

    private void projectBlock(JsonNode payload) {
        UUID documentId = uuid(payload, "documentId");
        UUID blockId = uuid(payload, "blockId");

        Map<String, Object> docRow = queryOneRow(
                "SELECT workspace_id, title FROM documents WHERE id = ?",
                documentId
        );
        if (docRow == null) return;

        Map<String, Object> blockRow = queryOneRow(
                "SELECT text, sort_order, updated_at FROM document_blocks WHERE id = ?",
                blockId
        );
        if (blockRow == null) return;

        UUID workspaceId = (UUID) docRow.get("workspace_id");
        String title = (String) docRow.get("title");
        String text = (String) blockRow.get("text");
        int sortOrder = ((Number) blockRow.get("sort_order")).intValue();
        Instant updatedAt = ((java.sql.Timestamp) blockRow.get("updated_at")).toInstant();
        if (isAgentPlaceholderText(text)) {
            deleteBlock(payload);
            return;
        }

        searchIndexGateway.upsert(SearchIndexEntry.blockContent(
                workspaceId,
                documentId,
                title,
                blockId,
                text,
                sortOrder,
                updatedAt
        ));
    }

    private void deleteBlock(JsonNode payload) {
        searchIndexGateway.deleteByIndexId(
                SearchIndexEntry.blockIndexId(uuid(payload, "blockId"))
        );
    }

    private JsonNode readPayload(UUID eventId, String payloadStr) {
        try {
            return objectMapper.readTree(payloadStr);
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                    "Outbox event payload cannot be parsed: eventId=" + eventId,
                    exception
            );
        }
    }

    private static UUID uuid(JsonNode payload, String field) {
        String value = requiredText(payload, field);
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Outbox event payload field is not a UUID: field=" + field,
                    exception
            );
        }
    }

    private void projectDocumentOperationApplied(JsonNode payload) {
        String operationType = text(payload, "operationType");
        String targetType = text(payload, "targetType");
        if ("DOCUMENT".equals(targetType)) {
            if ("DOCUMENT_DELETED".equals(operationType)) {
                deleteDocument(payload);
                return;
            }
            projectDocument(payload);
            return;
        }

        if ("DOCUMENT_BLOCK".equals(targetType)) {
            if ("DOCUMENT_BLOCK_DELETED".equals(operationType)) {
                deleteBlock(payload);
                return;
            }
            projectBlock(payload);
        }
    }

    private static String text(JsonNode payload, String field) {
        return requiredText(payload, field);
    }

    private static boolean isAgentPlaceholderText(String text) {
        return text != null && text.contains(AGENT_PLACEHOLDER_MARKER);
    }

    private static Instant instant(JsonNode payload, String field) {
        String value = requiredText(payload, field);
        try {
            return Instant.parse(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                    "Outbox event payload field is not an Instant: field=" + field,
                    exception
            );
        }
    }

    private static String requiredText(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw new IllegalArgumentException(
                    "Outbox event payload missing required field: field=" + field
            );
        }
        return value.asText();
    }

    private Map<String, Object> queryOneRow(String sql, Object... args) {
        try {
            return jdbcTemplate.queryForMap(sql, args);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }
}
