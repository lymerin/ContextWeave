package com.devcollab.mcp.application;

import com.devcollab.mcp.client.KnowledgeCoreGateway;
import com.devcollab.mcp.config.McpProperties;
import com.devcollab.mcp.error.McpToolErrorCode;
import com.devcollab.mcp.error.McpToolException;
import com.devcollab.mcp.governance.ContextBudgetPolicy;
import com.devcollab.mcp.security.McpUserIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FindCandidatesApplicationServiceTests {

    private KnowledgeCoreGateway gateway;
    private FindCandidatesApplicationService service;
    private McpUserIdentity identity;
    private UUID workspaceId;
    private UUID repositoryId;

    @BeforeEach
    void setUp() {
        gateway = mock(KnowledgeCoreGateway.class);
        McpProperties properties = properties();
        service = new FindCandidatesApplicationService(
                gateway, properties, new ContextBudgetPolicy(properties)
        );
        identity = new McpUserIdentity(UUID.randomUUID(), UUID.randomUUID(), "member", "token");
        workspaceId = UUID.randomUUID();
        repositoryId = UUID.randomUUID();
    }

    @Test
    void forwardsTrustedCoreCandidateResultWithoutRecomputingIt() {
        UUID documentId = UUID.randomUUID();
        UUID blockId = UUID.randomUUID();
        KnowledgeCoreGateway.DocumentCandidateResult coreResult =
                new KnowledgeCoreGateway.DocumentCandidateResult(
                        workspaceId, repositoryId, "src/order/OrderController.java", "order",
                        List.of(new KnowledgeCoreGateway.DocumentCandidate(
                                documentId, "Order API", 155,
                                List.of(
                                        new KnowledgeCoreGateway.DocumentCandidateMatchReason(
                                                "DIRECT_BINDING", 100, "src/order/OrderController.java",
                                                List.of(blockId)
                                        ),
                                        new KnowledgeCoreGateway.DocumentCandidateMatchReason(
                                                "TITLE_EXACT", 60, "order", List.of()
                                        )
                                ),
                                List.of(blockId), 2
                        )),
                        true, 3
                );
        when(gateway.findDocumentCandidates(
                workspaceId, repositoryId, "src/order/OrderController.java", "order", 10, identity
        )).thenReturn(coreResult);

        Map<String, Object> result = service.findCandidates(
                workspaceId, repositoryId, "src/order/OrderController.java", " order ", 10, identity
        );

        assertThat(result).containsEntry("truncated", true)
                .containsEntry("omittedCandidateCount", 3)
                .containsEntry("repositoryId", repositoryId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) result.get("candidates");
        assertThat(candidates).singleElement().satisfies(candidate -> {
            assertThat(candidate).containsEntry("documentId", documentId)
                    .containsEntry("score", 155)
                    .containsEntry("existingBindingCount", 2);
            assertThat((List<?>) candidate.get("matchReasons")).hasSize(2);
            assertThat(candidate.get("matchedBlockIds")).isEqualTo(List.of(blockId));
        });
    }

    @Test
    void fileOnlyQueryIsAllowedWithRepository() {
        when(gateway.findDocumentCandidates(
                workspaceId, repositoryId, "src/App.java", null, 20, identity
        )).thenReturn(emptyResult(repositoryId, "src/App.java", null));

        Map<String, Object> result = service.findCandidates(
                workspaceId, repositoryId, "src\\App.java", null, null, identity
        );

        assertThat(result.get("filePath")).isEqualTo("src/App.java");
    }

    @Test
    void queryOnlyIsAllowed() {
        when(gateway.findDocumentCandidates(
                workspaceId, null, null, "design", 20, identity
        )).thenReturn(emptyResult(null, null, "design"));

        Map<String, Object> result = service.findCandidates(
                workspaceId, null, null, " design ", null, identity
        );

        assertThat(result.get("query")).isEqualTo("design");
    }

    @Test
    void missingInputsAndFileWithoutRepositoryAreRejected() {
        assertInvalid(() -> service.findCandidates(
                workspaceId, null, null, null, null, identity
        ));
        assertInvalid(() -> service.findCandidates(
                workspaceId, null, "src/App.java", null, null, identity
        ));
    }

    @Test
    void blankAndOversizedQueryAreRejected() {
        assertInvalid(() -> service.findCandidates(
                workspaceId, null, null, "   ", null, identity
        ));
        assertInvalid(() -> service.findCandidates(
                workspaceId, null, null, "x".repeat(501), null, identity
        ));
    }

    @Test
    void limitCannotExpandServerBudget() {
        assertInvalid(() -> service.findCandidates(
                workspaceId, null, null, "query", 0, identity
        ));
        assertInvalid(() -> service.findCandidates(
                workspaceId, null, null, "query", 21, identity
        ));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "../a", "a/../b", "a\\..\\b", "/etc/passwd", "\\absolute",
            "C:\\file", "C:/file", "C:file", "\\\\server\\share"
    })
    void unsafeFilePathsAreRejected(String path) {
        assertThatThrownBy(() -> service.findCandidates(
                workspaceId, repositoryId, path, null, null, identity
        )).isInstanceOf(McpToolException.class)
                .extracting("code").isEqualTo(McpToolErrorCode.INVALID_REPOSITORY_PATH);
    }

    @Test
    void corePermissionAndAvailabilityErrorsPropagate() {
        when(gateway.findDocumentCandidates(any(), any(), any(), eq("denied"), anyInt(), any()))
                .thenThrow(new McpToolException(McpToolErrorCode.PERMISSION_DENIED, "denied"));
        when(gateway.findDocumentCandidates(any(), any(), any(), eq("down"), anyInt(), any()))
                .thenThrow(new McpToolException(McpToolErrorCode.CORE_UNAVAILABLE, "down"));

        assertThatThrownBy(() -> service.findCandidates(
                workspaceId, null, null, "denied", null, identity
        )).extracting("code").isEqualTo(McpToolErrorCode.PERMISSION_DENIED);
        assertThatThrownBy(() -> service.findCandidates(
                workspaceId, null, null, "down", null, identity
        )).extracting("code").isEqualTo(McpToolErrorCode.CORE_UNAVAILABLE);
    }

    @Test
    void batchAcceptsEmptyPathsWithoutCallingCore() {
        Map<String, Object> result = service.findCandidatesBatch(
                workspaceId, repositoryId, List.of(), null, identity
        );

        assertThat(result.get("workspaceId")).isEqualTo(workspaceId);
        @SuppressWarnings("unchecked")
        Map<String, Object> candidatesByPath = (Map<String, Object>) result.get("candidatesByPath");
        assertThat(candidatesByPath).isEmpty();
        verifyNoInteractions(gateway);
    }

    @Test
    void batchReturnsCandidatesByPath() {
        UUID documentId = UUID.randomUUID();
        when(gateway.findDocumentCandidates(
                workspaceId, repositoryId, "src/App.java", null, 20, identity
        )).thenReturn(new KnowledgeCoreGateway.DocumentCandidateResult(
                workspaceId, repositoryId, "src/App.java", null,
                List.of(new KnowledgeCoreGateway.DocumentCandidate(
                        documentId, "App", 100, List.of(), List.of(), 0
                )),
                false, 0
        ));

        Map<String, Object> result = service.findCandidatesBatch(
                workspaceId, repositoryId, List.of("src\\App.java"), null, identity
        );

        @SuppressWarnings("unchecked")
        Map<String, List<Map<String, Object>>> candidatesByPath =
                (Map<String, List<Map<String, Object>>>) result.get("candidatesByPath");
        assertThat(candidatesByPath).containsOnlyKeys("src/App.java");
        assertThat(candidatesByPath.get("src/App.java")).singleElement()
                .satisfies(candidate -> assertThat(candidate.get("documentId")).isEqualTo(documentId));
    }

    @Test
    void batchRejectsTooManyPaths() {
        List<String> paths = java.util.stream.IntStream.range(0, 101)
                .mapToObj(index -> "src/File" + index + ".java")
                .toList();

        assertInvalid(() -> service.findCandidatesBatch(
                workspaceId, repositoryId, paths, null, identity
        ));
    }

    private KnowledgeCoreGateway.DocumentCandidateResult emptyResult(
            UUID repository, String filePath, String query
    ) {
        return new KnowledgeCoreGateway.DocumentCandidateResult(
                workspaceId, repository, filePath, query, List.of(), false, 0
        );
    }

    private void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable).isInstanceOf(McpToolException.class)
                .extracting("code").isEqualTo(McpToolErrorCode.INVALID_DOCUMENT_QUERY);
    }

    private McpProperties properties() {
        return new McpProperties(
                "/mcp", "test", "1", 400, 40000, 2048,
                100, 30000, 50, 20, 500, 200, 100,
                List.of("http://localhost:*"), List.of("localhost:*"),
                URI.create("http://localhost:8080"), Duration.ofSeconds(1)
        );
    }
}
