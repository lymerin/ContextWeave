package com.devcollab.mcp.application;

import com.devcollab.mcp.client.KnowledgeCoreGateway;
import com.devcollab.mcp.config.McpProperties;
import com.devcollab.mcp.error.McpToolErrorCode;
import com.devcollab.mcp.error.McpToolException;
import com.devcollab.mcp.governance.ContextBudgetPolicy;
import com.devcollab.mcp.security.McpUserIdentity;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CodeReadApplicationServiceTests {

    private final KnowledgeCoreGateway gateway = mock(KnowledgeCoreGateway.class);
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID repositoryId = UUID.randomUUID();
    private final McpUserIdentity identity =
            new McpUserIdentity(UUID.randomUUID(), UUID.randomUUID(), "member", "token");

    @Test
    void rejectsBinaryAndReportsUnavailableBindingsHonestly() {
        when(gateway.readRepositorySource(eq(workspaceId), eq(repositoryId), anyString(), any()))
                .thenReturn(new KnowledgeCoreGateway.RepositorySource(
                        repositoryId, "abc", "image.png", 100, "PNG", false, null
                ));
        CodeReadApplicationService service = service(400, 40_000);

        assertThatThrownBy(() -> service.read(
                workspaceId, repositoryId, "image.png", null, null, false, identity
        )).isInstanceOfSatisfying(McpToolException.class,
                error -> assertThat(error.code()).isEqualTo(McpToolErrorCode.UNSUPPORTED_FILE_TYPE));

        when(gateway.readRepositorySource(eq(workspaceId), eq(repositoryId), eq("App.java"), any()))
                .thenReturn(new KnowledgeCoreGateway.RepositorySource(
                        repositoryId, "abc", "App.java", 20, "Java", true, "one\ntwo"
                ));
        Map<String, Object> result = service.read(
                workspaceId, repositoryId, "App.java", null, null, true, identity
        );
        assertThat(result.get("existingBindings")).isEqualTo(List.of());
        assertThat(result.get("existingBindingsAvailable")).isEqualTo(false);
        assertThat(result.get("existingBindingsRequested")).isEqualTo(true);
    }

    @Test
    void appliesCentralLineAndCharacterBudgets() {
        String content = java.util.stream.IntStream.rangeClosed(1, 500)
                .mapToObj(line -> "line-" + line + "-" + "x".repeat(120))
                .collect(java.util.stream.Collectors.joining("\n"));
        when(gateway.readRepositorySource(eq(workspaceId), eq(repositoryId), anyString(), any()))
                .thenReturn(new KnowledgeCoreGateway.RepositorySource(
                        repositoryId, "abc", "App.java", content.length(), "Java", true, content
                ));
        CodeReadApplicationService service = service(400, 40_000);

        Map<String, Object> result = service.read(
                workspaceId, repositoryId, "App.java", null, null, false, identity
        );

        assertThat(result.get("truncated")).isEqualTo(true);
        assertThat((int) result.get("endLine")).isLessThanOrEqualTo(400);
        assertThat(((String) result.get("content")).length()).isLessThanOrEqualTo(40_000);
        assertThat((int) result.get("omittedLineCount")).isGreaterThan(0);
        assertThat((int) result.get("omittedCharacterCount")).isGreaterThan(0);
    }

    @Test
    void readBatchAcceptsEmptyPathsWithoutCallingCore() {
        CodeReadApplicationService service = service(400, 40_000);

        Map<String, Object> result = service.readBatch(
                workspaceId, repositoryId, List.of(), "abc", identity
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> files = (Map<String, Object>) result.get("files");
        assertThat(files).isEmpty();
        assertThat(result.get("truncated")).isEqualTo(false);
        verifyNoInteractions(gateway);
    }

    @Test
    void readBatchTruncatesAtTotalByteLimitBoundary() {
        String exact = "a".repeat(200 * 1024);
        when(gateway.readRepositorySource(eq(workspaceId), eq(repositoryId), eq("A.java"), any()))
                .thenReturn(new KnowledgeCoreGateway.RepositorySource(
                        repositoryId, "abc", "A.java", exact.length(), "Java", true, exact
                ));
        CodeReadApplicationService service = service(400, 40_000);

        Map<String, Object> exactResult = service.readBatch(
                workspaceId, repositoryId, List.of("A.java"), "abc", identity
        );

        assertThat(exactResult.get("truncated")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> exactFiles =
                (Map<String, Map<String, Object>>) exactResult.get("files");
        assertThat(exactFiles.get("A.java").get("content")).isEqualTo(exact);

        when(gateway.readRepositorySource(eq(workspaceId), eq(repositoryId), eq("B.java"), any()))
                .thenReturn(new KnowledgeCoreGateway.RepositorySource(
                        repositoryId, "abc", "B.java", exact.length() + 1, "Java",
                        true, exact + "b"
                ));
        Map<String, Object> truncatedResult = service.readBatch(
                workspaceId, repositoryId, List.of("B.java"), "abc", identity
        );

        assertThat(truncatedResult.get("truncated")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> truncatedFiles =
                (Map<String, Map<String, Object>>) truncatedResult.get("files");
        assertThat((String) truncatedFiles.get("B.java").get("content")).hasSize(200 * 1024);
        assertThat(truncatedFiles.get("B.java").get("truncated")).isEqualTo(true);
    }

    @Test
    void readBatchRejectsTooManyPaths() {
        CodeReadApplicationService service = service(400, 40_000);
        List<String> paths = java.util.stream.IntStream.range(0, 101)
                .mapToObj(index -> "src/File" + index + ".java")
                .toList();

        assertThatThrownBy(() -> service.readBatch(
                workspaceId, repositoryId, paths, "abc", identity
        )).isInstanceOfSatisfying(McpToolException.class,
                error -> assertThat(error.code()).isEqualTo(McpToolErrorCode.INVALID_ARGUMENT));
    }

    private CodeReadApplicationService service(int lines, int characters) {
        McpProperties properties = new McpProperties(
                "/mcp", "test", "1", lines, characters, 2_048,
                100, 30000, 50, 20, 500, 200, 100,
                List.of("http://localhost:*"), List.of("localhost:*"),
                URI.create("http://localhost:8080"), Duration.ofSeconds(1)
        );
        return new CodeReadApplicationService(gateway, new ContextBudgetPolicy(properties));
    }
}
