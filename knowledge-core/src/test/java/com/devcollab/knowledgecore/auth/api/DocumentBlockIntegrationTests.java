package com.devcollab.knowledgecore.auth.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DocumentBlockIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldCreateAndListParagraphBlocksInOrder() throws Exception {
        String token = registerAndGetAccessToken();
        String workspaceId = createWorkspace(token).get("id").asText();
        String documentId = createDocument(token, workspaceId)
                .get("id")
                .asText();

        JsonNode first = createBlock(token, documentId, "First paragraph");
        JsonNode second = createBlock(token, documentId, "Second paragraph");

        mockMvc.perform(get("/api/v1/documents/{id}/blocks", documentId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(first.get("id").asText()))
                .andExpect(jsonPath("$[0].type").value("PARAGRAPH"))
                .andExpect(jsonPath("$[0].content.text")
                        .value("First paragraph"))
                .andExpect(jsonPath("$[0].content.schemaVersion").value(1))
                .andExpect(jsonPath("$[0].content.document.type").value("doc"))
                .andExpect(jsonPath("$[0].content.document.content[0].type")
                        .value("paragraph"))
                .andExpect(jsonPath("$[0].sortOrder").value(0))
                .andExpect(jsonPath("$[0].version").value(0))
                .andExpect(jsonPath("$[1].id").value(second.get("id").asText()))
                .andExpect(jsonPath("$[1].sortOrder").value(1))
                .andExpect(jsonPath("$[1].version").value(0));
    }

    @Test
    void shouldHideAgentPlaceholderBlocksFromUserFacingReads()
            throws Exception {
        String token = registerAndGetAccessToken();
        String workspaceId = createWorkspace(token).get("id").asText();
        String documentId = createDocument(token, workspaceId)
                .get("id")
                .asText();

        JsonNode visible = createBlock(token, documentId, "Ready content");
        createBlock(
                token,
                documentId,
                "Module overview\n⏳ 本条目由系统自动生成中（批次填充后自动替换）：模块总览"
        );

        mockMvc.perform(get("/api/v1/documents/{id}/blocks", documentId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(visible.get("id").asText()))
                .andExpect(jsonPath("$[0].content.text")
                        .value("Ready content"));

        mockMvc.perform(get(
                        "/api/v1/workspaces/{workspaceId}/documents/{documentId}/structure",
                        workspaceId,
                        documentId
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .param("includeBlockContent", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocks.length()").value(1))
                .andExpect(jsonPath("$.blocks[0].plainText")
                        .value("Ready content"));

        mockMvc.perform(get(
                        "/api/v1/workspaces/{workspaceId}/documents/{documentId}/structure",
                        workspaceId,
                        documentId
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .param("includeBlockContent", "true")
                        .param("includeInternalBlocks", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocks.length()").value(2))
                .andExpect(jsonPath("$.blocks[1].plainText")
                        .value(org.hamcrest.Matchers.containsString("⏳")));
    }

    @Test
    void shouldPersistStructuredTiptapJsonAndDeriveSearchText() throws Exception {
        String token = registerAndGetAccessToken();
        String workspaceId = createWorkspace(token).get("id").asText();
        String documentId = createDocument(token, workspaceId).get("id").asText();
        JsonNode block = createBlock(token, documentId, "Legacy text");

        String body = """
                {
                  "content": {
                    "schemaVersion": 1,
                    "document": {
                      "type": "doc",
                      "content": [
                        {"type":"paragraph","content":[{"type":"text","text":"First line"}]},
                        {"type":"paragraph"},
                        {"type":"paragraph","content":[{"type":"text","text":"Second line"}]}
                      ]
                    }
                  },
                  "expectedVersion": 0
                }
                """;

        mockMvc.perform(patch(
                        "/api/v1/documents/{documentId}/blocks/{blockId}",
                        documentId,
                        block.get("id").asText()
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.text")
                        .value("First line\n\nSecond line"))
                .andExpect(jsonPath("$.content.schemaVersion").value(1))
                .andExpect(jsonPath("$.content.document.content.length()")
                        .value(3))
                .andExpect(jsonPath("$.version").value(1));

        String storedJson = jdbcTemplate.queryForObject(
                "SELECT content_json FROM document_blocks WHERE id = ?",
                String.class,
                UUID.fromString(block.get("id").asText())
        );
        org.assertj.core.api.Assertions.assertThat(objectMapper.readTree(storedJson))
                .isEqualTo(objectMapper.readTree(body).path("content").path("document"));
    }

    @Test
    void shouldMapBusinessBlockTypesToDedicatedTiptapNodes() throws Exception {
        String token = registerAndGetAccessToken();
        String workspaceId = createWorkspace(token).get("id").asText();
        String documentId = createDocument(token, workspaceId).get("id").asText();

        createStructuredBlock(token, documentId, """
                {"type":"HEADING","content":{"schemaVersion":1,"document":{
                  "type":"doc","content":[{"type":"heading","attrs":{"level":1},
                  "content":[{"type":"text","text":"Contract title"}]}]}}}
                """)
                .andExpect(jsonPath("$.content.text").value("Contract title"))
                .andExpect(jsonPath("$.content.document.content[0].type")
                        .value("heading"))
                .andExpect(jsonPath("$.content.document.content[0].attrs.level")
                        .value(1));

        createStructuredBlock(token, documentId, """
                {"type":"CODE","content":{"schemaVersion":1,"document":{
                  "type":"doc","content":[{"type":"codeBlock",
                  "content":[{"type":"text","text":"line 1\\nline 2"}]}]}}}
                """)
                .andExpect(jsonPath("$.content.text").value("line 1\nline 2"))
                .andExpect(jsonPath("$.content.document.content[0].type")
                        .value("codeBlock"));

        createStructuredBlock(token, documentId, """
                {"type":"TODO","content":{"schemaVersion":1,"document":{
                  "type":"doc","content":[{"type":"taskList","content":[{
                    "type":"taskItem","attrs":{"checked":true},"content":[{
                      "type":"paragraph","content":[{"type":"text","text":"Ship it"}]
                    }]
                  }]}]}}}
                """)
                .andExpect(jsonPath("$.content.text").value("Ship it"))
                .andExpect(jsonPath("$.content.document.content[0].type")
                        .value("taskList"))
                .andExpect(jsonPath("$.content.document.content[0].content[0].attrs.checked")
                        .value(true));
    }

    @Test
    void shouldRejectBusinessTypeAndTiptapNodeMismatch() throws Exception {
        String token = registerAndGetAccessToken();
        String workspaceId = createWorkspace(token).get("id").asText();
        String documentId = createDocument(token, workspaceId).get("id").asText();

        mockMvc.perform(post("/api/v1/documents/{id}/blocks", documentId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"HEADING","content":{"schemaVersion":1,
                                  "document":{"type":"doc","content":[{
                                    "type":"paragraph","content":[{
                                      "type":"text","text":"Wrong shape"
                                    }]
                                  }]}
                                }}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void shouldSynthesizeDedicatedNodeForLegacySemanticTextRequest() throws Exception {
        String token = registerAndGetAccessToken();
        String workspaceId = createWorkspace(token).get("id").asText();
        String documentId = createDocument(token, workspaceId).get("id").asText();

        mockMvc.perform(post("/api/v1/documents/{id}/blocks", documentId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(blockBody("HEADING", "Legacy heading")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.content.document.content[0].type")
                        .value("heading"))
                .andExpect(jsonPath("$.content.document.content[0].attrs.level")
                        .value(2));
    }

    @Test
    void shouldReadKnownLegacyParagraphShapeAsDedicatedSemanticNode() throws Exception {
        String token = registerAndGetAccessToken();
        String workspaceId = createWorkspace(token).get("id").asText();
        String documentId = createDocument(token, workspaceId).get("id").asText();

        MvcResult created = mockMvc.perform(post(
                        "/api/v1/documents/{id}/blocks",
                        documentId
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(blockBody("HEADING", "Old semantic heading")))
                .andExpect(status().isCreated())
                .andReturn();
        UUID blockId = UUID.fromString(responseJson(created).get("id").asText());
        jdbcTemplate.update(
                "UPDATE document_blocks SET content_json = ? WHERE id = ?",
                """
                        {"type":"doc","content":[{"type":"paragraph",
                        "content":[{"type":"text","text":"Old semantic heading"}]}]}
                        """,
                blockId
        );

        mockMvc.perform(get("/api/v1/documents/{id}/blocks", documentId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].content.document.content[0].type")
                        .value("heading"))
                .andExpect(jsonPath("$[0].content.document.content[0].attrs.level")
                        .value(2));
    }

    @Test
    void shouldRejectUnsupportedStructuredContentNodeOrSchema() throws Exception {
        String token = registerAndGetAccessToken();
        String workspaceId = createWorkspace(token).get("id").asText();
        String documentId = createDocument(token, workspaceId).get("id").asText();

        mockMvc.perform(post("/api/v1/documents/{id}/blocks", documentId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type":"PARAGRAPH",
                                  "content":{
                                    "schemaVersion":2,
                                    "document":{"type":"doc","content":[]}
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        mockMvc.perform(post("/api/v1/documents/{id}/blocks", documentId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type":"PARAGRAPH",
                                  "content":{
                                    "schemaVersion":1,
                                    "document":{
                                      "type":"doc",
                                      "content":[{"type":"image","attrs":{"src":"x"}}]
                                    }
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void shouldSynthesizeStructuredResponseForPreMigrationTextRow() throws Exception {
        String token = registerAndGetAccessToken();
        String workspaceId = createWorkspace(token).get("id").asText();
        String documentId = createDocument(token, workspaceId).get("id").asText();
        JsonNode block = createBlock(token, documentId, "Old row text");
        jdbcTemplate.update(
                "UPDATE document_blocks SET content_json = NULL WHERE id = ?",
                UUID.fromString(block.get("id").asText())
        );

        mockMvc.perform(get("/api/v1/documents/{id}/blocks", documentId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].content.text").value("Old row text"))
                .andExpect(jsonPath("$[0].content.schemaVersion").value(1))
                .andExpect(jsonPath("$[0].content.document.content[0].content[0].text")
                        .value("Old row text"));
    }

    @Test
    void shouldRejectBlockAccessFromWorkspaceOutsider() throws Exception {
        String ownerToken = registerAndGetAccessToken();
        String outsiderToken = registerAndGetAccessToken();
        String workspaceId = createWorkspace(ownerToken).get("id").asText();
        String documentId = createDocument(ownerToken, workspaceId)
                .get("id")
                .asText();

        mockMvc.perform(post("/api/v1/documents/{id}/blocks", documentId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(outsiderToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(blockBody("PARAGRAPH", "Private content")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code")
                        .value("WORKSPACE_ACCESS_DENIED"));
    }

    @Test
    void shouldRejectUnsupportedBlockType() throws Exception {
        String token = registerAndGetAccessToken();
        String workspaceId = createWorkspace(token).get("id").asText();
        String documentId = createDocument(token, workspaceId)
                .get("id")
                .asText();

        mockMvc.perform(post("/api/v1/documents/{id}/blocks", documentId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(blockBody("DIAGRAM", "graph TD")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void shouldUpdateParagraphContentWithoutChangingIdentityOrOrder()
            throws Exception {
        String token = registerAndGetAccessToken();
        String workspaceId = createWorkspace(token).get("id").asText();
        String documentId = createDocument(token, workspaceId)
                .get("id")
                .asText();
        JsonNode block = createBlock(token, documentId, "Original content");

        mockMvc.perform(patch(
                        "/api/v1/documents/{documentId}/blocks/{blockId}",
                        documentId,
                        block.get("id").asText()
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBlockBody("  Updated content  ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(block.get("id").asText()))
                .andExpect(jsonPath("$.documentId").value(documentId))
                .andExpect(jsonPath("$.type").value("PARAGRAPH"))
                .andExpect(jsonPath("$.content.text")
                        .value("Updated content"))
                .andExpect(jsonPath("$.sortOrder").value(0))
                .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(get("/api/v1/documents/{id}/blocks", documentId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].content.text")
                        .value("Updated content"))
                .andExpect(jsonPath("$[0].version").value(1));
    }

    @Test
    void shouldRejectStaleBlockContentUpdate() throws Exception {
        String token = registerAndGetAccessToken();
        String workspaceId = createWorkspace(token).get("id").asText();
        String documentId = createDocument(token, workspaceId)
                .get("id")
                .asText();
        JsonNode block = createBlock(token, documentId, "Original content");
        String blockId = block.get("id").asText();

        mockMvc.perform(patch(
                        "/api/v1/documents/{documentId}/blocks/{blockId}",
                        documentId,
                        blockId
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBlockBody("First update", 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(patch(
                        "/api/v1/documents/{documentId}/blocks/{blockId}",
                        documentId,
                        blockId
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBlockBody("Stale update", 0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("DOCUMENT_BLOCK_VERSION_CONFLICT"));
    }

    @Test
    void shouldRejectBlockUpdateFromWorkspaceOutsider() throws Exception {
        String ownerToken = registerAndGetAccessToken();
        String outsiderToken = registerAndGetAccessToken();
        String workspaceId = createWorkspace(ownerToken).get("id").asText();
        String documentId = createDocument(ownerToken, workspaceId)
                .get("id")
                .asText();
        String blockId = createBlock(ownerToken, documentId, "Private")
                .get("id")
                .asText();

        mockMvc.perform(patch(
                        "/api/v1/documents/{documentId}/blocks/{blockId}",
                        documentId,
                        blockId
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(outsiderToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBlockBody("Forbidden update")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code")
                        .value("WORKSPACE_ACCESS_DENIED"));
    }

    @Test
    void shouldRejectBlockThatDoesNotBelongToPathDocument()
            throws Exception {
        String token = registerAndGetAccessToken();
        String workspaceId = createWorkspace(token).get("id").asText();
        String firstDocumentId = createDocument(token, workspaceId)
                .get("id")
                .asText();
        String secondDocumentId = createDocument(token, workspaceId)
                .get("id")
                .asText();
        String blockId = createBlock(token, firstDocumentId, "First document")
                .get("id")
                .asText();

        mockMvc.perform(patch(
                        "/api/v1/documents/{documentId}/blocks/{blockId}",
                        secondDocumentId,
                        blockId
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBlockBody("Wrong document")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("DOCUMENT_BLOCK_NOT_FOUND"));
    }

    @Test
    void shouldDeleteBlockAndNormalizeRemainingOrder() throws Exception {
        String token = registerAndGetAccessToken();
        String workspaceId = createWorkspace(token).get("id").asText();
        String documentId = createDocument(token, workspaceId)
                .get("id")
                .asText();
        JsonNode first = createBlock(token, documentId, "First");
        JsonNode second = createBlock(token, documentId, "Second");
        JsonNode third = createBlock(token, documentId, "Third");

        mockMvc.perform(delete(
                        "/api/v1/documents/{documentId}/blocks/{blockId}",
                        documentId,
                        second.get("id").asText()
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/documents/{id}/blocks", documentId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(first.get("id").asText()))
                .andExpect(jsonPath("$[0].sortOrder").value(0))
                .andExpect(jsonPath("$[1].id").value(third.get("id").asText()))
                .andExpect(jsonPath("$[1].sortOrder").value(1));
    }

    @Test
    void shouldMoveBlockAndReturnCompleteNewOrder() throws Exception {
        String token = registerAndGetAccessToken();
        String workspaceId = createWorkspace(token).get("id").asText();
        String documentId = createDocument(token, workspaceId)
                .get("id")
                .asText();
        JsonNode first = createBlock(token, documentId, "First");
        JsonNode second = createBlock(token, documentId, "Second");
        JsonNode third = createBlock(token, documentId, "Third");

        mockMvc.perform(patch(
                        "/api/v1/documents/{documentId}/blocks/{blockId}/position",
                        documentId,
                        third.get("id").asText()
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(moveBlockBody(0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(third.get("id").asText()))
                .andExpect(jsonPath("$[0].sortOrder").value(0))
                .andExpect(jsonPath("$[1].id").value(first.get("id").asText()))
                .andExpect(jsonPath("$[1].sortOrder").value(1))
                .andExpect(jsonPath("$[2].id").value(second.get("id").asText()))
                .andExpect(jsonPath("$[2].sortOrder").value(2));
    }

    @Test
    void shouldRejectTargetIndexOutsideDocumentRange() throws Exception {
        String token = registerAndGetAccessToken();
        String workspaceId = createWorkspace(token).get("id").asText();
        String documentId = createDocument(token, workspaceId)
                .get("id")
                .asText();
        String blockId = createBlock(token, documentId, "Only block")
                .get("id")
                .asText();

        mockMvc.perform(patch(
                        "/api/v1/documents/{documentId}/blocks/{blockId}/position",
                        documentId,
                        blockId
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(moveBlockBody(1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("DOCUMENT_BLOCK_POSITION_INVALID"));
    }

    @Test
    void shouldRejectBlockDeletionFromWorkspaceOutsider() throws Exception {
        String ownerToken = registerAndGetAccessToken();
        String outsiderToken = registerAndGetAccessToken();
        String workspaceId = createWorkspace(ownerToken).get("id").asText();
        String documentId = createDocument(ownerToken, workspaceId)
                .get("id")
                .asText();
        String blockId = createBlock(ownerToken, documentId, "Private")
                .get("id")
                .asText();

        mockMvc.perform(delete(
                        "/api/v1/documents/{documentId}/blocks/{blockId}",
                        documentId,
                        blockId
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(outsiderToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code")
                        .value("WORKSPACE_ACCESS_DENIED"));
    }

    private JsonNode createWorkspace(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/workspaces")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Block workspace\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return responseJson(result);
    }

    private JsonNode createDocument(String token, String workspaceId)
            throws Exception {
        MvcResult result = mockMvc.perform(
                        post("/api/v1/workspaces/{id}/documents", workspaceId)
                                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"title\":\"Block design\"}")
                )
                .andExpect(status().isCreated())
                .andReturn();
        return responseJson(result);
    }

    private JsonNode createBlock(
            String token,
            String documentId,
            String text
    ) throws Exception {
        MvcResult result = mockMvc.perform(
                        post("/api/v1/documents/{id}/blocks", documentId)
                                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(blockBody("PARAGRAPH", text))
                )
                .andExpect(status().isCreated())
                .andReturn();
        return responseJson(result);
    }

    private org.springframework.test.web.servlet.ResultActions createStructuredBlock(
            String token,
            String documentId,
            String body
    ) throws Exception {
        return mockMvc.perform(post("/api/v1/documents/{id}/blocks", documentId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    private String blockBody(String type, String text) throws Exception {
        return objectMapper.writeValueAsString(
                new BlockBody(type, new BlockContentBody(text))
        );
    }

    private String updateBlockBody(String text) throws Exception {
        return updateBlockBody(text, 0);
    }

    private String updateBlockBody(String text, long expectedVersion)
            throws Exception {
        return objectMapper.writeValueAsString(
                new UpdateBlockBody(
                        new BlockContentBody(text),
                        expectedVersion
                )
        );
    }

    private String moveBlockBody(int targetIndex) throws Exception {
        return objectMapper.writeValueAsString(
                new MoveBlockBody(targetIndex)
        );
    }

    private String registerAndGetAccessToken() throws Exception {
        String username = "block_" + UUID.randomUUID()
                .toString()
                .substring(0, 8);
        String requestBody = objectMapper.writeValueAsString(
                new RegisterBody(username, "Block Tester", "password123")
        );
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andReturn();
        return responseJson(result).get("accessToken").asText();
    }

    private JsonNode responseJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record BlockBody(String type, BlockContentBody content) {
    }

    private record BlockContentBody(String text) {
    }

    private record UpdateBlockBody(
            BlockContentBody content,
            long expectedVersion
    ) {
    }

    private record MoveBlockBody(int targetIndex) {
    }

    private record RegisterBody(
            String username,
            String displayName,
            String password
    ) {
    }
}
