package com.devcollab.mcp.application;

import com.devcollab.mcp.client.KnowledgeCoreGateway;
import com.devcollab.mcp.config.McpProperties;
import com.devcollab.mcp.error.McpToolErrorCode;
import com.devcollab.mcp.error.McpToolException;
import com.devcollab.mcp.security.McpUserIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RepositoryScopeApplicationServiceTests {

    private KnowledgeCoreGateway gateway;
    private McpProperties properties;
    private McpUserIdentity identity;
    private UUID workspaceId;
    private UUID repositoryId;

    @BeforeEach
    void setUp() {
        gateway = mock(KnowledgeCoreGateway.class);
        properties = new McpProperties(
                "/mcp", "test", "1", 400, 40000, 2048,
                100, 30000, 50, 20, 500, 3, 3,
                List.of("http://localhost:*"), List.of("localhost:*"),
                URI.create("http://localhost:8080"), Duration.ofSeconds(1)
        );
        identity = new McpUserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), "member", "token");
        workspaceId = UUID.randomUUID();
        repositoryId = UUID.randomUUID();
    }

    @Test
    void listFilesNormalizesPrefixAndReturnsMetadataOnly() {
        when(gateway.listRepositoryFiles(
                eq(workspaceId), eq(repositoryId), eq("src/main"), eq(true),
                eq(null), eq(2), any()
        )).thenReturn(new KnowledgeCoreGateway.RepositoryFilePage(
                workspaceId, repositoryId, "abc123", "src/main", true,
                List.of(new KnowledgeCoreGateway.RepositoryFileInfo(
                        "src/main/App.java", "App.java", "java",
                        12, "Java", true, false
                )), null, false
        ));
        Map<String, Object> result = new RepositoryFilesApplicationService(
                gateway, properties
        ).listFiles(
                workspaceId, repositoryId, "abc123", "src\\main", true,
                null, 2, identity
        );
        List<?> files = (List<?>) result.get("files");
        assertThat(files).hasSize(1);
        Map<?, ?> file = (Map<?, ?>) files.get(0);
        assertThat(file.get("filePath")).isEqualTo("src/main/App.java");
        assertThat(file.containsKey("content")).isFalse();
    }

    @Test
    void pathPolicyRejectsTraversalAbsoluteDriveAndRootAsFile() {
        RepositoryFilesApplicationService service =
                new RepositoryFilesApplicationService(gateway, properties);
        for (String invalid : List.of("../a", "a/../b", "/etc/passwd", "C:\\file")) {
            assertThatThrownBy(() -> service.listFiles(
                    workspaceId, repositoryId, null, invalid, true, null, 2, identity
            )).isInstanceOf(McpToolException.class)
                    .extracting("code")
                    .isEqualTo(McpToolErrorCode.INVALID_REPOSITORY_PATH);
        }
        assertThatThrownBy(() -> RepositoryPathPolicy.normalize(".", false))
                .isInstanceOf(McpToolException.class);
    }

    @Test
    void changesPreserveRenameAndDeleteMetadata() {
        when(gateway.listRepositoryChanges(
                eq(workspaceId), eq(repositoryId), eq(null), eq(2), any()
        )).thenReturn(new KnowledgeCoreGateway.RepositoryChangePage(
                workspaceId, repositoryId, UUID.randomUUID(), "COMMIT", "abc",
                List.of(
                        new KnowledgeCoreGateway.RepositoryChangedFile(
                                "RENAMED", "src/New.java", "src/Old.java", false),
                        new KnowledgeCoreGateway.RepositoryChangedFile(
                                "DELETED", "src/Gone.java", null, false)
                ), null, false
        ));
        Map<String, Object> result = new RepositoryChangesApplicationService(
                gateway, properties
        ).listChanges(workspaceId, repositoryId, null, 2, identity);
        List<?> files = (List<?>) result.get("files");
        Map<?, ?> renamed = (Map<?, ?>) files.get(0);
        Map<?, ?> deleted = (Map<?, ?>) files.get(1);
        assertThat(renamed.get("status")).isEqualTo("RENAMED");
        assertThat(renamed.get("oldPath")).isEqualTo("src/Old.java");
        assertThat(deleted.get("status")).isEqualTo("DELETED");
    }

    @Test
    void bindingBatchNormalizesDeduplicatesAndKeepsEmptyGroups() {
        UUID documentId = UUID.randomUUID();
        when(gateway.getFileBindingsBatch(
                eq(workspaceId), eq(repositoryId),
                eq(List.of("src/A.java", "src/Empty.java")), any()
        )).thenReturn(new KnowledgeCoreGateway.BindingBatchResult(
                workspaceId, repositoryId, List.of(
                new KnowledgeCoreGateway.FileBindingGroup(
                        "src/A.java", List.of(new KnowledgeCoreGateway.BatchBindingInfo(
                        UUID.randomUUID(), repositoryId, documentId, "认证设计",
                        null, "src/A.java",
                        null, null, null, null, null, null, 1))),
                new KnowledgeCoreGateway.FileBindingGroup("src/Empty.java", List.of())
        )));
        Map<String, Object> result = new BindingListBatchApplicationService(
                gateway, properties
        ).listBindings(
                workspaceId, repositoryId,
                List.of("src\\A.java", "src/A.java", "src/Empty.java"), identity
        );
        List<?> files = (List<?>) result.get("files");
        assertThat(files).hasSize(2);
        assertThat((List<?>) ((Map<?, ?>) files.get(1)).get("bindings")).isEmpty();
    }

    @Test
    void rejectsPageAndBatchLimits() {
        assertThatThrownBy(() -> new RepositoryFilesApplicationService(
                gateway, properties
        ).listFiles(workspaceId, repositoryId, null, "", true, null, 4, identity))
                .isInstanceOf(McpToolException.class);
        assertThatThrownBy(() -> new BindingListBatchApplicationService(
                gateway, properties
        ).listBindings(
                workspaceId, repositoryId,
                List.of("a.java", "b.java", "c.java", "d.java"), identity
        )).isInstanceOf(McpToolException.class);
    }

    @Test
    void metadataInspectionKeepsDelegatedRepositoryAndRevisionScope() {
        McpUserIdentity delegated = delegatedIdentity("abc123");
        when(gateway.inspectCodeMetadata(
                eq(workspaceId), eq(repositoryId), eq("abc123"),
                eq(List.of("src/App.java")), any()
        )).thenReturn(new KnowledgeCoreGateway.CodeMetadataBatch(
                workspaceId,
                repositoryId,
                "abc123",
                List.of(new KnowledgeCoreGateway.CodeMetadataInfo(
                        "src/App.java", "Java", "com.example", "src",
                        "CONTROLLER", List.of("com.example.Service"),
                        List.of(), List.of("App"), List.of("RestController"),
                        List.of("/app"), List.of("CONTROLLER"), "PARSED", null
                ))
        ));

        Map<String, Object> result = new RepositoryCodeMetadataApplicationService(
                gateway, properties
        ).inspect(
                workspaceId, repositoryId, "abc123",
                List.of("src\\App.java", "src/App.java"), delegated
        );

        assertThat(result.get("revision")).isEqualTo("abc123");
        Map<?, ?> file = (Map<?, ?>) ((List<?>) result.get("files")).get(0);
        assertThat(file.get("moduleName")).isEqualTo("src");
        assertThat(file.containsKey("content")).isFalse();
        assertThatThrownBy(() -> new RepositoryCodeMetadataApplicationService(
                gateway, properties
        ).inspect(
                workspaceId, repositoryId, "different",
                List.of("src/App.java"), delegated
        )).isInstanceOf(McpToolException.class)
                .extracting("code")
                .isEqualTo(McpToolErrorCode.PERMISSION_DENIED);
    }

    @Test
    void fileDiscoveryRejectsRevisionDriftForDelegatedJobs() {
        when(gateway.listRepositoryFiles(
                eq(workspaceId), eq(repositoryId), eq(""), eq(true),
                eq(null), eq(2), any()
        )).thenReturn(new KnowledgeCoreGateway.RepositoryFilePage(
                workspaceId, repositoryId, "new-revision", "", true,
                List.of(), null, false
        ));

        assertThatThrownBy(() -> new RepositoryFilesApplicationService(
                gateway, properties
        ).listFiles(
                workspaceId, repositoryId, "job-revision", "", true,
                null, 2, delegatedIdentity("job-revision")
        )).isInstanceOf(McpToolException.class)
                .extracting("code")
                .isEqualTo(McpToolErrorCode.INVALID_ARGUMENT);
    }

    private McpUserIdentity delegatedIdentity(String revision) {
        return new McpUserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), "agent", "delegated-token",
                "agent_delegation", workspaceId, repositoryId, UUID.randomUUID(),
                revision, Set.of(
                        "devcollab.repository.list_files",
                        "devcollab.repository.inspect_code_metadata"
                )
        );
    }
}
