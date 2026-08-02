package com.devcollab.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.devcollab.mcp.client.KnowledgeCoreGateway;
import com.devcollab.mcp.config.McpProperties;
import com.devcollab.mcp.error.McpToolErrorCode;
import com.devcollab.mcp.error.McpToolException;
import com.devcollab.mcp.security.McpUserIdentity;
import java.net.URI;
import java.time.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BindingListApplicationServiceTests {

    private KnowledgeCoreGateway gateway;
    private BindingListApplicationService service;
    private McpUserIdentity identity;
    private UUID workspaceId;
    private UUID repositoryId;

    @BeforeEach
    void setUp() {
        gateway = mock(KnowledgeCoreGateway.class);
        McpProperties properties = new McpProperties(
                "/mcp", "test", "1", 400, 40000, 2048,
                100, 30000, 50, 20, 500, 200, 100,
                List.of("http://localhost:*"), List.of("localhost:*"),
                URI.create("http://localhost:8080"), Duration.ofSeconds(1)
        );
        service = new BindingListApplicationService(gateway, properties);
        identity = new McpUserIdentity(UUID.randomUUID(), UUID.randomUUID(), "member", "token");
        workspaceId = UUID.randomUUID();
        repositoryId = UUID.randomUUID();
    }

    @Test
    void fileHasNoBindings_returnsEmpty() {
        when(gateway.getFileBindings(eq(workspaceId), eq(repositoryId), eq("src/Main.java"), any(Integer.class), any()))
                .thenReturn(queryResult(false, List.of(), false, 0));

        Map<String, Object> result = service.getFileBindings(workspaceId, repositoryId, "src/Main.java", identity);

        assertThat(result.get("fileHasBindings")).isEqualTo(false);
        assertThat((List<?>) result.get("bindings")).isEmpty();
        assertThat(result.get("truncated")).isEqualTo(false);
        assertThat(result.get("omittedBindingCount")).isEqualTo(0);
    }

    @Test
    void singleBindingReturnsCorrectly() {
        UUID bindingId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        when(gateway.getFileBindings(eq(workspaceId), eq(repositoryId), eq("src/Main.java"), any(Integer.class), any()))
                .thenReturn(queryResult(true, List.of(
                        bindingInfo(bindingId, "src/Main.java", documentId, "My Document", null)
                ), false, 0));

        Map<String, Object> result = service.getFileBindings(workspaceId, repositoryId, "src/Main.java", identity);

        assertThat(result.get("fileHasBindings")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bindings = (List<Map<String, Object>>) result.get("bindings");
        assertThat(bindings).hasSize(1);
        assertThat(bindings.get(0).get("bindingId")).isEqualTo(bindingId);
        assertThat(bindings.get(0).get("documentTitle")).isEqualTo("My Document");
    }

    @Test
    void twoBindingsSameDocumentDifferentBlocks_bothPreserved() {
        UUID bindingId1 = UUID.randomUUID();
        UUID bindingId2 = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID blockId1 = UUID.randomUUID();
        UUID blockId2 = UUID.randomUUID();
        when(gateway.getFileBindings(eq(workspaceId), eq(repositoryId), eq("src/Main.java"), any(Integer.class), any()))
                .thenReturn(queryResult(true, List.of(
                        bindingInfo(bindingId1, "src/Main.java", documentId, "My Doc", blockId1),
                        bindingInfo(bindingId2, "src/Main.java", documentId, "My Doc", blockId2)
                ), false, 0));

        Map<String, Object> result = service.getFileBindings(workspaceId, repositoryId, "src/Main.java", identity);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bindings = (List<Map<String, Object>>) result.get("bindings");
        assertThat(bindings).hasSize(2);
    }

    @Test
    void dockingBindingReturnsNullTitle() {
        UUID bindingId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        when(gateway.getFileBindings(eq(workspaceId), eq(repositoryId), eq("src/Main.java"), any(Integer.class), any()))
                .thenReturn(queryResult(true, List.of(
                        bindingInfo(bindingId, "src/Main.java", documentId, null, null)
                ), false, 0));

        Map<String, Object> result = service.getFileBindings(workspaceId, repositoryId, "src/Main.java", identity);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bindings = (List<Map<String, Object>>) result.get("bindings");
        assertThat(bindings).hasSize(1);
        assertThat(bindings.get(0).get("documentId")).isEqualTo(documentId);
        assertThat(bindings.get(0).get("documentTitle")).isNull();
    }

    @Test
    void maxBindingsTruncation() {
        List<KnowledgeCoreGateway.BindingInfo> list = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            list.add(bindingInfo(UUID.randomUUID(), "src/Main.java", UUID.randomUUID(), "Doc-" + i, null));
        }
        when(gateway.getFileBindings(eq(workspaceId), eq(repositoryId), eq("src/Main.java"), any(Integer.class), any()))
                .thenReturn(queryResult(true, list.subList(0, 3), true, 7));

        Map<String, Object> result = service.getFileBindings(workspaceId, repositoryId, "src/Main.java", identity);

        assertThat(result.get("truncated")).isEqualTo(true);
        assertThat(result.get("omittedBindingCount")).isEqualTo(7);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bindings = (List<Map<String, Object>>) result.get("bindings");
        assertThat(bindings).hasSize(3);
    }

    @Test
    void fileHasBindingsTrueEvenWhenTruncated() {
        List<KnowledgeCoreGateway.BindingInfo> list = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            list.add(bindingInfo(UUID.randomUUID(), "src/Main.java", UUID.randomUUID(), "Doc-" + i, null));
        }
        when(gateway.getFileBindings(eq(workspaceId), eq(repositoryId), eq("src/Main.java"), any(Integer.class), any()))
                .thenReturn(queryResult(true, list.subList(0, 2), true, 3));

        Map<String, Object> result = service.getFileBindings(workspaceId, repositoryId, "src/Main.java", identity);

        assertThat(result.get("fileHasBindings")).isEqualTo(true);
    }

    @Test
    void coreUnavailableMapped() {
        when(gateway.getFileBindings(eq(workspaceId), eq(repositoryId), eq("src/Main.java"), any(Integer.class), any()))
                .thenThrow(new McpToolException(McpToolErrorCode.CORE_UNAVAILABLE, "unavailable"));

        assertThatThrownBy(() -> service.getFileBindings(workspaceId, repositoryId, "src/Main.java", identity))
                .isInstanceOf(McpToolException.class)
                .extracting("code")
                .isEqualTo(McpToolErrorCode.CORE_UNAVAILABLE);
    }

    @Test
    void permissionDeniedMapped() {
        when(gateway.getFileBindings(eq(workspaceId), eq(repositoryId), eq("src/Main.java"), any(Integer.class), any()))
                .thenThrow(new McpToolException(McpToolErrorCode.PERMISSION_DENIED, "denied"));

        assertThatThrownBy(() -> service.getFileBindings(workspaceId, repositoryId, "src/Main.java", identity))
                .isInstanceOf(McpToolException.class)
                .extracting("code")
                .isEqualTo(McpToolErrorCode.PERMISSION_DENIED);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "src/Main.java",
            "src/main/java/App.java",
            "report..md",
            "dir/file..txt",
            ".github/workflows/test.yml",
            "a.b/c..d.txt"
    })
    void validPathsAccepted(String path) {
        when(gateway.getFileBindings(eq(workspaceId), eq(repositoryId), eq(path), any(Integer.class), any()))
                .thenReturn(queryResult(false, List.of(), false, 0));

        Map<String, Object> result = service.getFileBindings(workspaceId, repositoryId, path, identity);
        assertThat(result.get("fileHasBindings")).isEqualTo(false);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "../a",
            "a/../b",
            "/etc/passwd",
            "C:\\file",
            "C:/file",
            "\\\\server\\share",
            "a\\..\\b"
    })
    void invalidPathsRejected(String path) {
        assertThatThrownBy(() -> service.getFileBindings(workspaceId, repositoryId, path, identity))
                .isInstanceOf(McpToolException.class);
    }

    @Test
    void nullAndEmptyPathsRejected() {
        assertThatThrownBy(() -> service.getFileBindings(workspaceId, repositoryId, null, identity))
                .isInstanceOf(McpToolException.class);
        assertThatThrownBy(() -> service.getFileBindings(workspaceId, repositoryId, "", identity))
                .isInstanceOf(McpToolException.class);
        assertThatThrownBy(() -> service.getFileBindings(workspaceId, repositoryId, "   ", identity))
                .isInstanceOf(McpToolException.class);
    }

    private KnowledgeCoreGateway.BindingQueryResult queryResult(
            boolean fileHasBindings, List<KnowledgeCoreGateway.BindingInfo> bindings,
            boolean truncated, int omittedBindingCount) {
        return new KnowledgeCoreGateway.BindingQueryResult(
                workspaceId, repositoryId, "src/Main.java", fileHasBindings,
                bindings, truncated, omittedBindingCount
        );
    }

    private KnowledgeCoreGateway.BindingInfo bindingInfo(
            UUID bindingId, String pathPattern, UUID documentId,
            String documentTitle, UUID blockId) {
        return new KnowledgeCoreGateway.BindingInfo(
                bindingId, repositoryId, pathPattern, documentId, documentTitle, blockId,
                null, null, null, null, null, null, 1
        );
    }

    @Test
    void preservesPrecisionFieldsWhenGatewayReturnsThem() {
        KnowledgeCoreGateway.BindingInfo precise = new KnowledgeCoreGateway.BindingInfo(
                UUID.randomUUID(), repositoryId,
                "src/Main.java", UUID.randomUUID(),
                "Design Doc", UUID.randomUUID(),
                "abc123",         // revision
                "SYMBOL",          // anchorKind
                "PYTHON:src/Main.java:main:FUNCTION", // symbolKey
                10,                // startLine
                25,                // endLine
                "PRIMARY",         // bindingRole
                1                  // bindingOrdinal
        );
        when(gateway.getFileBindings(
                eq(workspaceId), eq(repositoryId), eq("src/Main.java"),
                any(Integer.class), any()
        )).thenReturn(queryResult(true, List.of(precise), false, 0));

        Map<String, Object> result = service.getFileBindings(
                workspaceId, repositoryId, "src/Main.java", identity
        );

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bindings =
                (List<Map<String, Object>>) result.get("bindings");
        assertThat(bindings).hasSize(1);

        Map<String, Object> binding = bindings.get(0);
        assertThat(binding)
                .as("revision must survive MCP gateway unmarshalling")
                .containsEntry("revision", "abc123");
        assertThat(binding)
                .as("anchorKind must survive MCP gateway unmarshalling")
                .containsEntry("anchorKind", "SYMBOL");
        assertThat(binding)
                .as("symbolKey must survive MCP gateway unmarshalling")
                .containsEntry("symbolKey", "PYTHON:src/Main.java:main:FUNCTION");
        assertThat(binding)
                .as("startLine must survive MCP gateway unmarshalling")
                .containsEntry("startLine", 10);
        assertThat(binding)
                .as("endLine must survive MCP gateway unmarshalling")
                .containsEntry("endLine", 25);
        assertThat(binding)
                .as("bindingRole must survive MCP gateway unmarshalling")
                .containsEntry("bindingRole", "PRIMARY");
        assertThat(binding)
                .as("bindingOrdinal must survive MCP gateway unmarshalling")
                .containsEntry("bindingOrdinal", 1);
    }
}
