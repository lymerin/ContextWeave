package com.devcollab.mcp;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.devcollab.mcp.client.KnowledgeCoreGateway;
import com.devcollab.mcp.error.McpToolErrorCode;
import com.devcollab.mcp.error.McpToolException;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpServerProtocolTests {

    private static final String SECRET = "devcollab-local-development-secret-change-me";

    @LocalServerPort
    private int port;

    @MockitoBean
    private KnowledgeCoreGateway coreGateway;

    @Test
    void officialSdkCanInitializeAndListTenTools() {
        try (McpSyncClient client = client()) {
            assertThat(client.initialize().serverInfo().name()).isEqualTo("devcollab-context-server");
            List<McpSchema.Tool> tools = client.listTools().tools();
            assertThat(tools)
                    .extracting(McpSchema.Tool::name)
                    .containsExactlyInAnyOrder(
                            "devcollab.workspace.get_context",
                            "devcollab.code.read",
                            "devcollab.document.get_structure",
                            "devcollab.binding.list",
                            "devcollab.document.find_candidates",
                            "devcollab.repository.list_files",
                            "devcollab.repository.list_changes",
                            "devcollab.binding.list_batch",
                            "devcollab.repository.inspect_code_metadata",
                            "devcollab.review.submit_document_change"
                    );
            for (McpSchema.Tool tool : tools) {
                assertThat(tool.inputSchema())
                        .containsEntry("type", "object")
                        .containsEntry("additionalProperties", false);
                assertThat(tool.annotations().readOnlyHint())
                        .isEqualTo(!tool.name().equals(
                                "devcollab.review.submit_document_change"
                        ));
                assertThat(tool.annotations().destructiveHint()).isFalse();
                assertThat(tool.annotations().idempotentHint()).isTrue();
                assertThat(tool.annotations().openWorldHint()).isFalse();
            }
            McpSchema.Tool workspaceTool = tools.stream()
                    .filter(tool -> tool.name().equals("devcollab.workspace.get_context"))
                    .findFirst()
                    .orElseThrow();
            assertThat(workspaceTool.inputSchema().get("required"))
                    .isEqualTo(List.of("workspaceId"));
            McpSchema.Tool codeTool = tools.stream()
                    .filter(tool -> tool.name().equals("devcollab.code.read"))
                    .findFirst()
                    .orElseThrow();
            assertThat(codeTool.inputSchema().get("required"))
                    .isEqualTo(List.of("workspaceId", "repositoryId", "path"));
            assertThat(workspaceTool.outputSchema())
                    .containsKeys("type", "properties", "oneOf", "additionalProperties");
            assertThat(codeTool.outputSchema())
                    .containsKeys("type", "properties", "oneOf", "additionalProperties");
            McpSchema.Tool candidateTool = tools.stream()
                    .filter(tool -> tool.name().equals("devcollab.document.find_candidates"))
                    .findFirst()
                    .orElseThrow();
            assertThat(candidateTool.inputSchema().get("required"))
                    .isEqualTo(List.of("workspaceId"));
            assertThat(candidateTool.inputSchema()).containsKey("allOf");
            Map<?, ?> candidateInputProperties =
                    (Map<?, ?>) candidateTool.inputSchema().get("properties");
            List<String> candidateInputKeys = candidateInputProperties.keySet().stream()
                    .map(Object::toString)
                    .toList();
            assertThat(candidateInputKeys)
                    .containsExactlyInAnyOrder(
                            "workspaceId", "repositoryId", "filePath", "query", "limit"
                    );
            assertThat(candidateInputKeys).doesNotContain("scope", "maxResults");
            Map<?, ?> candidateOutputProperties =
                    (Map<?, ?>) candidateTool.outputSchema().get("properties");
            List<String> candidateOutputKeys = candidateOutputProperties.keySet().stream()
                    .map(Object::toString)
                    .toList();
            assertThat(candidateOutputKeys).contains(
                    "workspaceId", "repositoryId", "filePath", "query", "candidates",
                    "truncated", "omittedCandidateCount", "error"
            );
            assertThat(((Map<?, ?>) workspaceTool.outputSchema().get("properties")).containsKey("error"))
                    .isTrue();
            assertThat(((Map<?, ?>) codeTool.outputSchema().get("properties")).containsKey("error"))
                    .isTrue();
            McpSchema.Tool submitTool = tools.stream()
                    .filter(tool -> tool.name().equals(
                            "devcollab.review.submit_document_change"
                    ))
                    .findFirst()
                    .orElseThrow();
            assertThat(submitTool.inputSchema().get("required")).isEqualTo(
                    List.of(
                            "workspaceId", "clientRequestId", "summary",
                            "rationale", "operations"
                    )
            );
            Map<?, ?> submitProperties = (Map<?, ?>) submitTool.inputSchema().get("properties");
            Map<?, ?> bindingProposals = (Map<?, ?>) submitProperties.get("bindingProposals");
            Map<?, ?> bindingItems = (Map<?, ?>) bindingProposals.get("items");
            Map<?, ?> bindingProperties = (Map<?, ?>) bindingItems.get("properties");
            assertThat(bindingProperties.keySet().stream().map(Object::toString).toList())
                    .contains("bindingRole", "bindingOrdinal");
            assertThat(submitTool.outputSchema())
                    .containsKeys(
                            "type", "properties", "oneOf",
                            "additionalProperties"
                    );
        }
    }

    @Test
    void officialClientCanSubmitPendingDocumentChange() {
        UUID workspaceId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        when(coreGateway.submitDocumentChange(
                eq(workspaceId), any(), any()
        )).thenReturn(Map.of(
                "changeRequestId", requestId.toString(),
                "status", "PENDING",
                "createdAt", "2026-07-26T10:00:00Z",
                "idempotentReplay", false
        ));

        try (McpSyncClient client = client()) {
            client.initialize();
            McpSchema.CallToolResult result = client.callTool(
                    new McpSchema.CallToolRequest(
                            "devcollab.review.submit_document_change",
                            Map.of(
                                    "workspaceId", workspaceId.toString(),
                                    "clientRequestId", "agent-run-1",
                                    "summary", "Update API docs",
                                    "rationale", "Code behavior changed",
                                    "operations", List.of(Map.of(
                                            "clientOperationId", "create-doc",
                                            "sequenceNumber", 1,
                                            "operationType", "CREATE_DOCUMENT",
                                            "proposedDocumentTitle", "API Design",
                                            "proposedPlainText", "## 面向初学者的说明",
                                            "proposedContentFormat", "MARKDOWN"
                                    ))
                            )
                    )
            );

            assertThat(result.isError()).isFalse();
            @SuppressWarnings("unchecked")
            Map<String, Object> structuredContent = (Map<String, Object>) result.structuredContent();
            assertThat(structuredContent)
                    .containsEntry("changeRequestId", requestId.toString())
                    .containsEntry("status", "PENDING")
                    .containsEntry("idempotentReplay", false)
                    .containsEntry("workspaceId", workspaceId.toString())
                    .containsEntry("operationCount", 1)
                    .containsEntry("bindingProposalCount", 0)
                    .containsEntry("repositoryId", null)
                    .containsEntry("documentId", null);
        }
    }

    @Test
    void authenticatedMemberCanReadWorkspaceAndCodeThroughOfficialClient() {
        UUID workspaceId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        when(coreGateway.getWorkspaceContext(eq(workspaceId), any())).thenReturn(
                new KnowledgeCoreGateway.WorkspaceContext(
                        workspaceId,
                        "MCP Workspace",
                        "MEMBER",
                        List.of(new KnowledgeCoreGateway.RepositoryContext(
                                repositoryId,
                                "devcollab",
                                "GITHUB",
                                "https://github.com/example/devcollab",
                                "main",
                                "SYNCED",
                                "abc123"
                        ))
                )
        );
        when(coreGateway.readRepositorySource(eq(workspaceId), eq(repositoryId), eq("src/App.java"), any()))
                .thenReturn(new KnowledgeCoreGateway.RepositorySource(
                        repositoryId,
                        "abc123",
                        "src/App.java",
                        30,
                        "Java",
                        true,
                        "line one\nline two\nline three"
                ));

        try (McpSyncClient client = client()) {
            client.initialize();
            McpSchema.CallToolResult workspace = client.callTool(new McpSchema.CallToolRequest(
                    "devcollab.workspace.get_context",
                    Map.of("workspaceId", workspaceId.toString())
            ));
            McpSchema.CallToolResult code = client.callTool(new McpSchema.CallToolRequest(
                    "devcollab.code.read",
                    Map.of(
                            "workspaceId", workspaceId.toString(),
                            "repositoryId", repositoryId.toString(),
                            "path", "src/App.java",
                            "startLine", 2,
                            "endLine", 3
                    )
            ));

            assertThat(workspace.isError()).isFalse();
            assertThat(((Map<?, ?>) workspace.structuredContent()).get("currentUserRole"))
                    .isEqualTo("MEMBER");
            assertThat(code.isError()).isFalse();
            assertThat(((Map<?, ?>) code.structuredContent()).get("content"))
                    .isEqualTo("line two\nline three");
            assertThat(((Map<?, ?>) code.structuredContent()).get("truncated"))
                    .isEqualTo(false);
        }
    }

    @Test
    void officialClientCanCallAllPhaseTwoTools() {
        UUID workspaceId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID blockId = UUID.randomUUID();
        UUID bindingId = UUID.randomUUID();
        when(coreGateway.getDocumentStructure(
                eq(workspaceId), eq(documentId), eq(false), anyInt(), anyInt(), any()
        )).thenReturn(new KnowledgeCoreGateway.DocumentStructure(
                documentId, workspaceId, "Design", "REQUIREMENT", "DRAFT",
                java.time.Instant.parse("2026-07-26T00:00:00Z"),
                List.of(new KnowledgeCoreGateway.BlockInfo(
                        blockId, "PARAGRAPH", 0, 1, null, null, false
                )),
                false, 0, 0
        ));
        when(coreGateway.getFileBindings(
                eq(workspaceId), eq(repositoryId), eq("src/App.java"), anyInt(), any()
        )).thenReturn(new KnowledgeCoreGateway.BindingQueryResult(
                workspaceId, repositoryId, "src/App.java", true,
                List.of(new KnowledgeCoreGateway.BindingInfo(
                        bindingId, "src/App.java", documentId, "Design", blockId
                )),
                false, 0
        ));
        when(coreGateway.findDocumentCandidates(
                eq(workspaceId), eq(repositoryId), eq("src/App.java"), isNull(), eq(5), any()
        )).thenReturn(new KnowledgeCoreGateway.DocumentCandidateResult(
                workspaceId, repositoryId, "src/App.java", null,
                List.of(new KnowledgeCoreGateway.DocumentCandidate(
                        documentId, "Design", 100,
                        List.of(new KnowledgeCoreGateway.DocumentCandidateMatchReason(
                                "DIRECT_BINDING", 100, "src/App.java", List.of(blockId)
                        )),
                        List.of(blockId), 1
                )),
                false, 0
        ));

        try (McpSyncClient client = client()) {
            client.initialize();
            McpSchema.CallToolResult structure = client.callTool(new McpSchema.CallToolRequest(
                    "devcollab.document.get_structure",
                    Map.of(
                            "workspaceId", workspaceId.toString(),
                            "documentId", documentId.toString()
                    )
            ));
            McpSchema.CallToolResult bindings = client.callTool(new McpSchema.CallToolRequest(
                    "devcollab.binding.list",
                    Map.of(
                            "workspaceId", workspaceId.toString(),
                            "repositoryId", repositoryId.toString(),
                            "filePath", "src/App.java"
                    )
            ));
            McpSchema.CallToolResult candidates = client.callTool(new McpSchema.CallToolRequest(
                    "devcollab.document.find_candidates",
                    Map.of(
                            "workspaceId", workspaceId.toString(),
                            "repositoryId", repositoryId.toString(),
                            "filePath", "src/App.java",
                            "limit", 5
                    )
            ));

            assertThat(structure.isError()).isFalse();
            assertThat(((Map<?, ?>) structure.structuredContent()).get("documentId"))
                    .isEqualTo(documentId.toString());
            assertThat(bindings.isError()).isFalse();
            assertThat(((List<?>) ((Map<?, ?>) bindings.structuredContent()).get("bindings")))
                    .hasSize(1);
            assertThat(candidates.isError()).isFalse();
            Map<?, ?> candidateContent = (Map<?, ?>) candidates.structuredContent();
            assertThat(candidateContent.get("omittedCandidateCount")).isEqualTo(0);
            assertThat((List<?>) candidateContent.get("candidates")).hasSize(1);
        }
    }

    @Test
    void corePermissionFailureIsStructured() {
        UUID workspaceId = UUID.randomUUID();
        when(coreGateway.getWorkspaceContext(eq(workspaceId), any())).thenThrow(
                new McpToolException(McpToolErrorCode.PERMISSION_DENIED, "Workspace access was denied")
        );

        try (McpSyncClient client = client()) {
            client.initialize();
            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(
                    "devcollab.workspace.get_context",
                    Map.of("workspaceId", workspaceId.toString())
            ));

            assertThat(result.isError()).isTrue();
            Map<?, ?> error = (Map<?, ?>) ((Map<?, ?>) result.structuredContent()).get("error");
            assertThat(error.get("code")).isEqualTo("PERMISSION_DENIED");
            assertThat(error.get("retryable")).isEqualTo(false);
        }
    }

    @Test
    void anonymousMcpRequestIsRejected() throws Exception {
        String initializeRequest = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                  "protocolVersion":"2025-06-18",
                  "capabilities":{},
                  "clientInfo":{"name":"security-test","version":"1.0.0"}
                }}
                """;
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString(initializeRequest))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertThat(response.statusCode()).isEqualTo(401);
    }

    private String issueAccessToken() {
        Instant now = Instant.now();
        return JWT.create()
                .withIssuer("devcollab-knowledge-core")
                .withAudience("devcollab-web")
                .withSubject(UUID.randomUUID().toString())
                .withJWTId(UUID.randomUUID().toString())
                .withClaim("sid", UUID.randomUUID().toString())
                .withClaim("username", "mcp-protocol-test")
                .withIssuedAt(Date.from(now))
                .withExpiresAt(Date.from(now.plusSeconds(300)))
                .sign(Algorithm.HMAC256(SECRET));
    }

    private McpSyncClient client() {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://localhost:" + port)
                .endpoint("/mcp")
                .requestBuilder(HttpRequest.newBuilder()
                        .header("Authorization", "Bearer " + issueAccessToken()))
                .build();
        return McpClient.sync(transport)
                .initializationTimeout(Duration.ofSeconds(5))
                .requestTimeout(Duration.ofSeconds(5))
                .build();
    }
}
