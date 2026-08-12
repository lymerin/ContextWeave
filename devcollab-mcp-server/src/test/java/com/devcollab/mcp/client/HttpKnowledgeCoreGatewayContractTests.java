package com.devcollab.mcp.client;

import com.devcollab.mcp.security.McpUserIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpKnowledgeCoreGatewayContractTests {

    private MockRestServiceServer server;
    private HttpKnowledgeCoreGateway gateway;
    private McpUserIdentity identity;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://knowledge-core");
        server = MockRestServiceServer.bindTo(builder).build();
        gateway = new HttpKnowledgeCoreGateway(builder.build());
        identity = new McpUserIdentity(UUID.randomUUID(), UUID.randomUUID(), "member", "access-token");
    }

    @Test
    void documentStructureJsonDeserializesWithoutDefaults() {
        UUID workspaceId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID blockId = UUID.randomUUID();
        String json = """
                {
                  "documentId":"%s",
                  "workspaceId":"%s",
                  "title":"Design",
                  "documentType":"REQUIREMENT",
                  "reviewStatus":"DRAFT",
                  "updatedAt":"2026-07-26T00:00:00Z",
                  "blocks":[{
                    "blockId":"%s",
                    "blockType":"PARAGRAPH",
                    "sortOrder":0,
                    "version":7,
                    "plainText":"hello",
                    "content":null,
                    "contentTruncated":true
                  }],
                  "truncated":true,
                  "omittedBlockCount":1,
                  "omittedCharacterCount":9
                }
                """.formatted(documentId, workspaceId, blockId);
        server.expect(requestTo(org.hamcrest.Matchers.containsString(
                        "/api/v1/workspaces/" + workspaceId + "/documents/" + documentId
                                + "/structure?includeBlockContent=true&includeInternalBlocks=true")))
                .andExpect(header("Authorization", "Bearer access-token"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        KnowledgeCoreGateway.DocumentStructure result = gateway.getDocumentStructure(
                workspaceId, documentId, true, 10, 100, identity
        );

        assertThat(result.isTruncated()).isTrue();
        assertThat(result.omittedBlockCount()).isOne();
        assertThat(result.omittedCharacterCount()).isEqualTo(9);
        assertThat(result.blocks()).singleElement().satisfies(block -> {
            assertThat(block.blockType()).isEqualTo("PARAGRAPH");
            assertThat(block.version()).isEqualTo(7);
            assertThat(block.isContentTruncated()).isTrue();
            assertThat(block.content()).isNull();
        });
        server.verify();
    }

    @Test
    void bindingJsonDeserializesNullTitleAndTruncation() {
        UUID workspaceId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID bindingId = UUID.randomUUID();
        String json = """
                {
                  "workspaceId":"%s",
                  "repositoryId":"%s",
                  "filePath":"src/App.java",
                  "fileHasBindings":true,
                  "bindings":[{
                    "bindingId":"%s",
                    "pathPattern":"src/App.java",
                    "documentId":"%s",
                    "documentTitle":null,
                    "blockId":null
                  }],
                  "truncated":true,
                  "omittedBindingCount":2
                }
                """.formatted(workspaceId, repositoryId, bindingId, documentId);
        server.expect(requestTo(org.hamcrest.Matchers.containsString(
                        "/api/v1/workspaces/" + workspaceId + "/repositories/" + repositoryId + "/code-bindings")))
                .andExpect(header("Authorization", "Bearer access-token"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        KnowledgeCoreGateway.BindingQueryResult result = gateway.getFileBindings(
                workspaceId, repositoryId, "src/App.java", 1, identity
        );

        assertThat(result.isTruncated()).isTrue();
        assertThat(result.omittedBindingCount()).isEqualTo(2);
        assertThat(result.bindings()).singleElement().satisfies(binding -> {
            assertThat(binding.documentId()).isEqualTo(documentId);
            assertThat(binding.documentTitle()).isNull();
            assertThat(binding.blockId()).isNull();
        });
        server.verify();
    }

    @Test
    void candidateJsonDeserializesScoresReasonsAndCoreTruncation() {
        UUID workspaceId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID blockId = UUID.randomUUID();
        String json = """
                {
                  "workspaceId":"%s",
                  "repositoryId":"%s",
                  "filePath":"src/App.java",
                  "query":null,
                  "candidates":[{
                    "documentId":"%s",
                    "title":"App Design",
                    "score":140,
                    "matchReasons":[{
                      "code":"DIRECT_BINDING",
                      "weight":100,
                      "matchedTerm":"src/App.java",
                      "matchedBlockIds":["%s"]
                    }],
                    "matchedBlockIds":["%s"],
                    "existingBindingCount":1
                  }],
                  "truncated":true,
                  "omittedCandidateCount":4
                }
                """.formatted(workspaceId, repositoryId, documentId, blockId, blockId);
        server.expect(requestTo(org.hamcrest.Matchers.containsString(
                        "/api/v1/workspaces/" + workspaceId + "/document-candidates")))
                .andExpect(header("Authorization", "Bearer access-token"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        KnowledgeCoreGateway.DocumentCandidateResult result = gateway.findDocumentCandidates(
                workspaceId, repositoryId, "src/App.java", null, 10, identity
        );

        assertThat(result.truncated()).isTrue();
        assertThat(result.omittedCandidateCount()).isEqualTo(4);
        assertThat(result.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.documentId()).isEqualTo(documentId);
            assertThat(candidate.score()).isEqualTo(140);
            assertThat(candidate.existingBindingCount()).isOne();
            assertThat(candidate.matchReasons()).singleElement().satisfies(reason -> {
                assertThat(reason.code()).isEqualTo("DIRECT_BINDING");
                assertThat(reason.weight()).isEqualTo(100);
            });
        });
        server.verify();
    }

    @Test
    void codeMetadataJsonDeserializesBlobSha() {
        UUID workspaceId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        String json = """
                {
                  "workspaceId":"%s",
                  "repositoryId":"%s",
                  "revision":"abc123",
                  "files":[{
                    "filePath":"src/App.java",
                    "blobSha":"blob-abc",
                    "language":"Java",
                    "packageName":"com.example",
                    "moduleKey":"src",
                    "layerHint":"CONTROLLER",
                    "imports":[],
                    "exportedSymbols":["App"],
                    "topLevelSymbols":["App"],
                    "annotations":[],
                    "routeHints":[],
                    "roleHints":[],
                    "parseStatus":"PARSED",
                    "errorCode":null
                  }]
                }
                """.formatted(workspaceId, repositoryId);
        server.expect(requestTo(org.hamcrest.Matchers.containsString(
                        "/api/v1/workspaces/" + workspaceId + "/repositories/" + repositoryId + "/code-metadata/batch")))
                .andExpect(header("Authorization", "Bearer access-token"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        KnowledgeCoreGateway.CodeMetadataBatch result = gateway.inspectCodeMetadata(
                workspaceId, repositoryId, "abc123", java.util.List.of("src/App.java"), identity
        );

        assertThat(result.files()).singleElement()
                .satisfies(file -> assertThat(file.blobSha()).isEqualTo("blob-abc"));
        server.verify();
    }
}
