package com.devcollab.mcp.application;

import com.devcollab.mcp.client.KnowledgeCoreGateway;
import com.devcollab.mcp.config.ReviewSubmissionProperties;
import com.devcollab.mcp.error.McpToolException;
import com.devcollab.mcp.security.McpUserIdentity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewSubmissionApplicationServiceTests {

    private final KnowledgeCoreGateway gateway =
            mock(KnowledgeCoreGateway.class);
    private final ReviewSubmissionApplicationService service =
            new ReviewSubmissionApplicationService(
                    gateway,
                    new ReviewSubmissionProperties(
                            50, 50, 300, 10_000, 20_000, 1000
                    )
            );
    private final McpUserIdentity identity = new McpUserIdentity(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "member",
            "token"
    );

    @Test
    void forwardsValidatedRequestWithoutWorkspaceIdInBody() {
        UUID workspaceId = UUID.randomUUID();
        Map<String, Object> arguments = validArguments(workspaceId);
        when(gateway.submitDocumentChange(
                eq(workspaceId), any(), eq(identity)
        )).thenReturn(Map.of(
                "changeRequestId", UUID.randomUUID().toString(),
                "status", "PENDING",
                "createdAt", "2026-07-26T10:00:00Z",
                "idempotentReplay", false
        ));

        Map<String, Object> result = service.submit(
                workspaceId, arguments, identity
        );

        assertThat(result)
                .containsEntry("status", "PENDING")
                .containsEntry("workspaceId", workspaceId.toString())
                .containsEntry("operationCount", 1)
                .containsEntry("bindingProposalCount", 0)
                .containsEntry("repositoryId", null)
                .containsEntry("documentId", null);
        verify(gateway).submitDocumentChange(
                eq(workspaceId),
                org.mockito.ArgumentMatchers.argThat(body ->
                        !body.containsKey("workspaceId")
                                && body.containsKey("clientRequestId")
                                && ((List<?>) body.get("operations")).stream()
                                .map(item -> (Map<?, ?>) item)
                                .anyMatch(item -> "MARKDOWN".equals(
                                        item.get("proposedContentFormat")
                                ))
                ),
                eq(identity)
        );
    }

    @Test
    void rejectsEmptyOperationsAndBudgetOverflow() {
        UUID workspaceId = UUID.randomUUID();
        Map<String, Object> empty = new java.util.LinkedHashMap<>(
                validArguments(workspaceId)
        );
        empty.put("operations", List.of());

        assertThatThrownBy(() -> service.submit(
                workspaceId, empty, identity
        )).isInstanceOf(McpToolException.class);

        Map<String, Object> oversized = new java.util.LinkedHashMap<>(
                validArguments(workspaceId)
        );
        oversized.put("summary", "x".repeat(301));
        assertThatThrownBy(() -> service.submit(
                workspaceId, oversized, identity
        )).isInstanceOf(McpToolException.class);
    }

    @Test
    void validatesEvidenceFilePathLength() {
        UUID workspaceId = UUID.randomUUID();
        
        // 1000 characters should pass
        Map<String, Object> exactLength = new java.util.LinkedHashMap<>(validArguments(workspaceId));
        exactLength.put("evidence", List.of(Map.of(
                "repositoryId", UUID.randomUUID().toString(),
                "filePath", "a".repeat(1000),
                "description", "Valid length"
        )));
        
        when(gateway.submitDocumentChange(eq(workspaceId), any(), eq(identity)))
                .thenReturn(Map.of("changeRequestId", UUID.randomUUID().toString(), "status", "PENDING"));
        
        service.submit(workspaceId, exactLength, identity);
        
        // 1001 characters should fail at MCP layer
        Map<String, Object> overLength = new java.util.LinkedHashMap<>(validArguments(workspaceId));
        overLength.put("evidence", List.of(Map.of(
                "repositoryId", UUID.randomUUID().toString(),
                "filePath", "a".repeat(1001),
                "description", "Over length"
        )));
        
        assertThatThrownBy(() -> service.submit(workspaceId, overLength, identity))
                .isInstanceOf(McpToolException.class)
                .hasMessageContaining("evidence.filePath");
    }

    private Map<String, Object> validArguments(UUID workspaceId) {
        return Map.of(
                "workspaceId", workspaceId.toString(),
                "clientRequestId", "request-1",
                "summary", "Update API docs",
                "rationale", "Code behavior changed",
                "operations", List.of(Map.of(
                        "clientOperationId", "create-document",
                        "sequenceNumber", 1,
                        "operationType", "CREATE_DOCUMENT",
                        "proposedDocumentTitle", "API Design",
                        "proposedPlainText", "## 入门说明",
                        "proposedContentFormat", "MARKDOWN"
                ))
        );
    }
}
