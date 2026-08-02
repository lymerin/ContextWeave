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

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class WorkspaceDocumentIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldCompleteWorkspaceAndDocumentTreeChain() throws Exception {
        String token = registerAndGetAccessToken();
        JsonNode workspace = createWorkspace(token, "实验室项目");
        String workspaceId = workspace.get("id").asText();

        mockMvc.perform(get("/api/v1/workspaces")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        mockMvc.perform(get("/api/v1/workspaces/{id}", workspaceId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentUserRole").value("ADMIN"));

        JsonNode root = createDocument(
                token, workspaceId, null, "总体架构"
        );
        JsonNode child = createDocument(
                token, workspaceId, root.get("id").asText(), "登录设计"
        );

        mockMvc.perform(
                        get("/api/v1/workspaces/{id}/documents/tree", workspaceId)
                                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(root.get("id").asText()))
                .andExpect(jsonPath("$[0].title").value("总体架构"))
                .andExpect(jsonPath("$[0].children[0].id")
                        .value(child.get("id").asText()))
                .andExpect(jsonPath("$[0].children[0].title")
                        .value("登录设计"));

        mockMvc.perform(get(
                        "/api/v1/documents/{id}", child.get("id").asText())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspaceId").value(workspaceId))
                .andExpect(jsonPath("$.parentDocumentId")
                        .value(root.get("id").asText()))
                .andExpect(jsonPath("$.title").value("登录设计"))
                .andExpect(jsonPath("$.documentType").value("REQUIREMENT"))
                .andExpect(jsonPath("$.reviewStatus").value("DRAFT"));

        JsonNode apiDocument = createDocumentWithType(
                token, workspaceId, null, "Order API", "API"
        );
        mockMvc.perform(get(
                        "/api/v1/documents/{id}", apiDocument.get("id").asText())
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentType").value("API"));
    }

    @Test
    void shouldRejectCrossWorkspaceAccess() throws Exception {
        String ownerToken = registerAndGetAccessToken();
        String outsiderToken = registerAndGetAccessToken();
        String workspaceId = createWorkspace(
                ownerToken, "私有空间"
        ).get("id").asText();
        JsonNode document = createDocument(
                ownerToken, workspaceId, null, "内部设计"
        );

        mockMvc.perform(get("/api/v1/workspaces/{id}", workspaceId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(outsiderToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code")
                        .value("WORKSPACE_ACCESS_DENIED"));

        mockMvc.perform(get(
                        "/api/v1/documents/{id}", document.get("id").asText())
                        .header(HttpHeaders.AUTHORIZATION, bearer(outsiderToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code")
                        .value("WORKSPACE_ACCESS_DENIED"));
    }

    @Test
    void shouldSubmitDraftDocumentForReview() throws Exception {
        String token = registerAndGetAccessToken();
        String workspaceId = createWorkspace(
                token, "评审空间"
        ).get("id").asText();
        String documentId = createDocument(
                token, workspaceId, null, "评审文档"
        ).get("id").asText();

        mockMvc.perform(post(
                        "/api/v1/documents/{id}/submit-review",
                        documentId
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("IN_REVIEW"));

        mockMvc.perform(post(
                        "/api/v1/documents/{id}/submit-review",
                        documentId
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("DOCUMENT_REVIEW_STATUS_INVALID"));
    }

    @Test
    void shouldApproveReviewAndCreatePublishedVersion() throws Exception {
        String token = registerAndGetAccessToken();
        String workspaceId = createWorkspace(
                token, "publish workspace"
        ).get("id").asText();
        String documentId = createDocument(
                token, workspaceId, null, "publish document"
        ).get("id").asText();
        createParagraphBlock(token, documentId, "published paragraph");

        mockMvc.perform(post(
                        "/api/v1/documents/{id}/submit-review",
                        documentId
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("IN_REVIEW"));

        mockMvc.perform(post(
                        "/api/v1/documents/{id}/approve-review",
                        documentId
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"同意发布\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("PUBLISHED"));

        mockMvc.perform(get(
                        "/api/v1/documents/{id}/versions",
                        documentId
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].versionNo").value(1))
                .andExpect(jsonPath("$[0].title").value("publish document"))
                .andExpect(jsonPath("$[0].snapshotPayload")
                        .value(containsString("published paragraph")));

        String versionId = responseJson(mockMvc.perform(get(
                        "/api/v1/documents/{id}/versions",
                        documentId
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andReturn())
                .get(0)
                .get("id")
                .asText();

        mockMvc.perform(get(
                        "/api/v1/documents/{documentId}/versions/{versionId}",
                        documentId,
                        versionId
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(versionId))
                .andExpect(jsonPath("$.snapshotPayload")
                        .value(containsString("published paragraph")));

        mockMvc.perform(get(
                        "/api/v1/documents/{id}/timeline",
                        documentId
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action")
                        .value("DOCUMENT_REVIEW_APPROVED"))
                .andExpect(jsonPath("$[*].action")
                        .value(hasItem("DOCUMENT_CREATED")))
                .andExpect(jsonPath("$[*].action")
                        .value(hasItem("DOCUMENT_BLOCK_CREATED")));
    }

    @Test
    void shouldSupersedeOldVersion() throws Exception {
        String token = registerAndGetAccessToken();
        String workspaceId = createWorkspace(
                token, "version issue workspace"
        ).get("id").asText();
        String documentId = createDocument(
                token, workspaceId, null, "Versioned API"
        ).get("id").asText();
        JsonNode block = createParagraphBlock(token, documentId, "version one");

        submitAndApprove(token, documentId);

        mockMvc.perform(patch(
                        "/api/v1/documents/{documentId}/blocks/{blockId}",
                        documentId,
                        block.get("id").asText()
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "content": {"text": "version two"},
                                  "expectedVersion": 0
                                }
                                """))
                .andExpect(status().isOk());

        submitAndApprove(token, documentId);

        MvcResult versionsResult = mockMvc.perform(get(
                        "/api/v1/documents/{id}/versions",
                        documentId
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].versionNo").value(2))
                .andExpect(jsonPath("$[0].status").value("CURRENT"))
                .andExpect(jsonPath("$[1].versionNo").value(1))
                .andExpect(jsonPath("$[1].status").value("SUPERSEDED"))
                .andReturn();

    }

    @Test
    void shouldDeprecateDocumentAndRejectFurtherEditingOrReview()
            throws Exception {
        String token = registerAndGetAccessToken();
        String workspaceId = createWorkspace(
                token, "deprecated workspace"
        ).get("id").asText();
        String documentId = createDocument(
                token, workspaceId, null, "Deprecated doc"
        ).get("id").asText();

        mockMvc.perform(post(
                        "/api/v1/documents/{id}/deprecate",
                        documentId
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("DEPRECATED"));

        mockMvc.perform(patch(
                        "/api/v1/documents/{id}",
                        documentId
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"should fail\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("DOCUMENT_REVIEW_STATUS_INVALID"));

        mockMvc.perform(post(
                        "/api/v1/documents/{id}/submit-review",
                        documentId
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("DOCUMENT_REVIEW_STATUS_INVALID"));
    }

    @Test
    void shouldRejectReviewAndAllowResubmit() throws Exception {
        String token = registerAndGetAccessToken();
        String workspaceId = createWorkspace(
                token, "reject workspace"
        ).get("id").asText();
        String documentId = createDocument(
                token, workspaceId, null, "reject document"
        ).get("id").asText();

        mockMvc.perform(post(
                        "/api/v1/documents/{id}/submit-review",
                        documentId
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("IN_REVIEW"));

        mockMvc.perform(post(
                        "/api/v1/documents/{id}/reject-review",
                        documentId
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"需要补充说明\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("REJECTED"));

        mockMvc.perform(post(
                        "/api/v1/documents/{id}/submit-review",
                        documentId
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("IN_REVIEW"));
    }

    @Test
    void shouldRejectReviewApproveFromWorkspaceOutsider() throws Exception {
        String ownerToken = registerAndGetAccessToken();
        String outsiderToken = registerAndGetAccessToken();
        String workspaceId = createWorkspace(
                ownerToken, "review approve private workspace"
        ).get("id").asText();
        String documentId = createDocument(
                ownerToken, workspaceId, null, "internal review document"
        ).get("id").asText();

        mockMvc.perform(post(
                        "/api/v1/documents/{id}/submit-review",
                        documentId
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("IN_REVIEW"));

        mockMvc.perform(post(
                        "/api/v1/documents/{id}/approve-review",
                        documentId
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(outsiderToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code")
                        .value("WORKSPACE_ACCESS_DENIED"));
    }

    @Test
    void shouldRejectReviewSubmitFromWorkspaceOutsider() throws Exception {
        String ownerToken = registerAndGetAccessToken();
        String outsiderToken = registerAndGetAccessToken();
        String workspaceId = createWorkspace(
                ownerToken, "评审私有空间"
        ).get("id").asText();
        String documentId = createDocument(
                ownerToken, workspaceId, null, "内部评审文档"
        ).get("id").asText();

        mockMvc.perform(post(
                        "/api/v1/documents/{id}/submit-review",
                        documentId
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(outsiderToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code")
                        .value("WORKSPACE_ACCESS_DENIED"));
    }

    @Test
    void shouldRejectParentFromAnotherWorkspace() throws Exception {
        String token = registerAndGetAccessToken();
        String firstWorkspaceId = createWorkspace(
                token, "空间一"
        ).get("id").asText();
        String secondWorkspaceId = createWorkspace(
                token, "空间二"
        ).get("id").asText();
        String foreignParentId = createDocument(
                token, firstWorkspaceId, null, "空间一文档"
        ).get("id").asText();

        mockMvc.perform(
                        post("/api/v1/workspaces/{id}/documents", secondWorkspaceId)
                                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(documentBody(foreignParentId, "错误子文档"))
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("DOCUMENT_PARENT_INVALID"));
    }

    @Test
    void shouldUpdateMoveAndDeleteDocuments() throws Exception {
        String token = registerAndGetAccessToken();
        String workspaceId = createWorkspace(
                token, "文档增强空间"
        ).get("id").asText();
        JsonNode root = createDocument(
                token, workspaceId, null, "根文档"
        );
        JsonNode child = createDocument(
                token, workspaceId, root.get("id").asText(), "子文档"
        );
        JsonNode anotherRoot = createDocument(
                token, workspaceId, null, "另一个根文档"
        );

        mockMvc.perform(patch(
                        "/api/v1/documents/{id}",
                        child.get("id").asText()
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"子文档改名\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("子文档改名"));

        mockMvc.perform(patch(
                        "/api/v1/documents/{id}/parent",
                        child.get("id").asText()
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"parentDocumentId":"%s"}
                                """.formatted(anotherRoot.get("id").asText())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parentDocumentId")
                        .value(anotherRoot.get("id").asText()));

        mockMvc.perform(patch(
                        "/api/v1/documents/{id}/parent",
                        anotherRoot.get("id").asText()
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"parentDocumentId":"%s"}
                                """.formatted(child.get("id").asText())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DOCUMENT_PARENT_CYCLE"));

        mockMvc.perform(delete(
                        "/api/v1/documents/{id}",
                        anotherRoot.get("id").asText()
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(
                        "/api/v1/documents/{id}",
                        child.get("id").asText()
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));
    }

    @Test
    void shouldRequireAuthenticationForWorkspaceCreation() throws Exception {
        mockMvc.perform(post("/api/v1/workspaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"未登录空间\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_ACCESS_INVALID"));
    }

    private JsonNode createWorkspace(String token, String name)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/workspaces")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\"}".formatted(name)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currentUserRole").value("ADMIN"))
                .andReturn();
        return responseJson(result);
    }

    private JsonNode createDocument(
            String token,
            String workspaceId,
            String parentDocumentId,
            String title
    ) throws Exception {
        MvcResult result = mockMvc.perform(
                        post("/api/v1/workspaces/{id}/documents", workspaceId)
                                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(documentBody(parentDocumentId, title))
                )
                .andExpect(status().isCreated())
                .andReturn();
        return responseJson(result);
    }

    private JsonNode createDocumentWithType(
            String token,
            String workspaceId,
            String parentDocumentId,
            String title,
            String documentType
    ) throws Exception {
        MvcResult result = mockMvc.perform(
                        post("/api/v1/workspaces/{id}/documents", workspaceId)
                                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(documentBody(
                                        parentDocumentId,
                                        title,
                                        documentType
                                ))
                )
                .andExpect(status().isCreated())
                .andReturn();
        return responseJson(result);
    }

    private JsonNode createParagraphBlock(
            String token,
            String documentId,
            String text
    ) throws Exception {
        MvcResult result = mockMvc.perform(
                        post("/api/v1/documents/{id}/blocks", documentId)
                                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "type": "PARAGRAPH",
                                          "content": {
                                            "text": "%s"
                                          }
                                        }
                                        """.formatted(text))
                )
                .andExpect(status().isCreated())
                .andReturn();
        return responseJson(result);
    }

    private void submitAndApprove(String token, String documentId)
            throws Exception {
        mockMvc.perform(post(
                        "/api/v1/documents/{id}/submit-review",
                        documentId
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("IN_REVIEW"));

        mockMvc.perform(post(
                        "/api/v1/documents/{id}/approve-review",
                        documentId
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("PUBLISHED"));
    }

    private String documentBody(String parentDocumentId, String title) {
        return documentBody(parentDocumentId, title, null);
    }

    private String documentBody(
            String parentDocumentId,
            String title,
            String documentType
    ) {
        String parent = parentDocumentId == null
                ? "null"
                : "\"" + parentDocumentId + "\"";
        String type = documentType == null
                ? "null"
                : "\"" + documentType + "\"";
        return """
                {"parentDocumentId": %s, "title": "%s", "documentType": %s}
                """.formatted(parent, title, type);
    }

    private String registerAndGetAccessToken() throws Exception {
        String username = "user_" + UUID.randomUUID()
                .toString().substring(0, 8);
        String requestBody = """
                {
                  "username": "%s",
                  "displayName": "测试用户",
                  "password": "password123"
                }
                """.formatted(username);

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
}
