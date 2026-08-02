package com.devcollab.knowledgecore.documentchange.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DocumentChangeControllerIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @Disabled("Binding-only auto-approve bypass marked for review")
    void shouldAcceptBindingOnlyRequest() throws Exception {
        Fixture fixture = fixture();
        
        // 1. operations=[], bindingProposals 非空：成功创建 PENDING
        String bindingOnlyPayload = """
                {
                    "clientRequestId": "req-1",
                    "summary": "Binding only",
                    "rationale": "Test",
                    "operations": [],
                    "bindingProposals": [
                        {
                            "clientBindingProposalId": "bp-1",
                            "sequenceNumber": 1,
                            "action": "UPSERT_BINDING",
                            "repositoryId": "%s",
                            "filePath": "knowledge-core/src/main/java/App.java",
                            "documentId": "%s",
                            "reason": "Because"
                        }
                    ]
                }
                """.formatted(fixture.repositoryId(), fixture.documentId());

        mockMvc.perform(post("/api/v1/workspaces/{workspaceId}/document-change-requests", fixture.workspaceId())
                .header(HttpHeaders.AUTHORIZATION, bearer(fixture.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(bindingOnlyPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));
                
        // 2. operations 非空、bindingProposals 为空：成功
        String operationsOnlyPayload = """
                {
                    "clientRequestId": "req-2",
                    "summary": "Operations only",
                    "rationale": "Test",
                    "operations": [
                        {
                            "clientOperationId": "op-1",
                            "sequenceNumber": 1,
                            "operationType": "CREATE_DOCUMENT",
                            "proposedDocumentTitle": "New Doc"
                        }
                    ],
                    "bindingProposals": []
                }
                """;

        mockMvc.perform(post("/api/v1/workspaces/{workspaceId}/document-change-requests", fixture.workspaceId())
                .header(HttpHeaders.AUTHORIZATION, bearer(fixture.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(operationsOnlyPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));

        // 3. operations=[], bindingProposals=[]：返回明确业务校验错误
        String emptyPayload = """
                {
                    "clientRequestId": "req-3",
                    "summary": "Empty",
                    "rationale": "Test",
                    "operations": [],
                    "bindingProposals": []
                }
                """;

        mockMvc.perform(post("/api/v1/workspaces/{workspaceId}/document-change-requests", fixture.workspaceId())
                .header(HttpHeaders.AUTHORIZATION, bearer(fixture.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(emptyPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DOCUMENT_CHANGE_OPERATION_INVALID"));
                
        // 4. operations 缺失或 null：返回 400
        String missingOperationsPayload = """
                {
                    "clientRequestId": "req-4",
                    "summary": "Missing",
                    "rationale": "Test"
                }
                """;

        mockMvc.perform(post("/api/v1/workspaces/{workspaceId}/document-change-requests", fixture.workspaceId())
                .header(HttpHeaders.AUTHORIZATION, bearer(fixture.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(missingOperationsPayload))
                .andExpect(status().isBadRequest());
    }
    
    private Fixture fixture() throws Exception {
        String token = registerAndGetAccessToken();
        String workspaceId = responseJson(mockMvc.perform(
                        post("/api/v1/workspaces")
                                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"Collab workspace\"}")
                )
                .andExpect(status().isCreated())
                .andReturn()).get("id").asText();
        
        String documentId = responseJson(mockMvc.perform(post(
                        "/api/v1/workspaces/{id}/documents",
                        workspaceId
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Collab document\",\"documentType\":\"REQUIREMENT\"}"))
                .andExpect(status().isCreated())
                .andReturn()).get("id").asText();

        String repositoryId = responseJson(mockMvc.perform(post(
                        "/api/v1/workspaces/{id}/git/repositories",
                        workspaceId
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"DevCollab Core",
                                  "provider":"GENERIC",
                                  "remoteUrl":"https://example.com/repo.git",
                                  "defaultBranch":"main"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn()).get("id").asText();
                
        return new Fixture(token, workspaceId, documentId, repositoryId);
    }
    
    private String registerAndGetAccessToken() throws Exception {
        String username = "collab_" + UUID.randomUUID().toString().substring(0, 8);
        String body = objectMapper.writeValueAsString(
                new RegisterBody(username, "Tester", "password123")
        );
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
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

    private record Fixture(String token, String workspaceId, String documentId, String repositoryId) {
    }

    private record RegisterBody(
            String username,
            String displayName,
            String password
    ) {
    }
}
