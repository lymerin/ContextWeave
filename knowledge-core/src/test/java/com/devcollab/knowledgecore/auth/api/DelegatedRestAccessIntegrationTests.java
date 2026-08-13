package com.devcollab.knowledgecore.auth.api;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.devcollab.knowledgecore.auth.infrastructure.JwtProperties;
import com.devcollab.knowledgecore.auth.infrastructure.JwtTokenService;
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

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DelegatedRestAccessIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private JwtProperties jwtProperties;

    @Test
    void delegatedTokenCanCallMatchingWhitelistedEndpoint() throws Exception {
        Fixture fixture = fixture();

        mockMvc.perform(get("/api/v1/workspaces/{workspaceId}", fixture.workspaceId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(
                                delegationToken(
                                        fixture.userId(),
                                        fixture.workspaceId(),
                                        List.of("devcollab.workspace.get_context")
                                ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(fixture.workspaceId().toString()));
    }

    @Test
    void delegatedTokenCannotCallWhitelistedEndpointWithWrongTool() throws Exception {
        Fixture fixture = fixture();

        mockMvc.perform(get("/api/v1/workspaces/{workspaceId}", fixture.workspaceId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(
                                delegationToken(
                                        fixture.userId(),
                                        fixture.workspaceId(),
                                        List.of("devcollab.document.get_structure")
                                ))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AGENT_DELEGATION_DENIED"));
    }

    @Test
    void delegatedTokenCannotCrossWorkspace() throws Exception {
        Fixture fixture = fixture();
        UUID otherWorkspaceId = UUID.fromString(createWorkspace(fixture.accessToken()));

        mockMvc.perform(get("/api/v1/workspaces/{workspaceId}", otherWorkspaceId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(
                                delegationToken(
                                        fixture.userId(),
                                        fixture.workspaceId(),
                                        List.of("devcollab.workspace.get_context")
                                ))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AGENT_DELEGATION_DENIED"));
    }

    @Test
    void delegatedTokenCannotUseDifferentQueryRepository() throws Exception {
        Fixture fixture = fixture();
        UUID allowedRepositoryId = UUID.randomUUID();
        UUID requestedRepositoryId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/workspaces/{workspaceId}/document-candidates",
                        fixture.workspaceId())
                        .queryParam("repositoryId", requestedRepositoryId.toString())
                        .header(HttpHeaders.AUTHORIZATION, bearer(
                                delegationToken(
                                        fixture.userId(),
                                        fixture.workspaceId(),
                                        allowedRepositoryId,
                                        List.of("devcollab.document.find_candidates")
                                ))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AGENT_DELEGATION_DENIED"));
    }

    @Test
    void delegatedTokenCannotOmitRepositoryOnRepositoryScopedEndpoint()
            throws Exception {
        Fixture fixture = fixture();

        mockMvc.perform(get("/api/v1/workspaces/{workspaceId}/document-candidates",
                        fixture.workspaceId())
                        .queryParam("query", "api")
                        .header(HttpHeaders.AUTHORIZATION, bearer(
                                delegationToken(
                                        fixture.userId(),
                                        fixture.workspaceId(),
                                        UUID.randomUUID(),
                                        List.of("devcollab.document.find_candidates")
                                ))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AGENT_DELEGATION_DENIED"));
    }

    @Test
    void delegatedTokenCannotSubmitDocumentChangeForDifferentRepository()
            throws Exception {
        Fixture fixture = fixture();
        UUID allowedRepositoryId = UUID.randomUUID();
        UUID requestedRepositoryId = UUID.randomUUID();

        mockMvc.perform(post(
                        "/api/v1/workspaces/{workspaceId}/document-change-requests",
                        fixture.workspaceId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(
                                delegationToken(
                                        fixture.userId(),
                                        fixture.workspaceId(),
                                        allowedRepositoryId,
                                        List.of("devcollab.review.submit_document_change")
                                )))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "clientRequestId":"request-%s",
                                  "summary":"Update binding",
                                  "rationale":"Evidence points to a binding update",
                                  "operations":[],
                                  "bindingProposals":[{
                                    "clientBindingProposalId":"binding-1",
                                    "sequenceNumber":1,
                                    "action":"UPSERT_BINDING",
                                    "repositoryId":"%s",
                                    "filePath":"src/App.java",
                                    "reason":"Relevant source"
                                  }]
                                }
                                """.formatted(
                                UUID.randomUUID(),
                                requestedRepositoryId
                        )))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AGENT_DELEGATION_DENIED"));
    }

    @Test
    void delegatedSubmitterCanReadOwnDocumentChangeStatus() throws Exception {
        Fixture fixture = fixture();
        MvcResult created = mockMvc.perform(post(
                        "/api/v1/workspaces/{workspaceId}/document-change-requests",
                        fixture.workspaceId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(fixture.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "clientRequestId":"request-%s",
                                  "summary":"Create requirement",
                                  "rationale":"Agent submitted review",
                                  "operations":[{
                                    "clientOperationId":"op-1",
                                    "sequenceNumber":1,
                                    "operationType":"CREATE_DOCUMENT",
                                    "proposedDocumentTitle":"Generated requirement"
                                  }],
                                  "bindingProposals":[]
                                }
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andReturn();
        String requestId = responseJson(created).get("changeRequestId").asText();

        mockMvc.perform(get(
                        "/api/v1/workspaces/{workspaceId}/document-change-requests/{requestId}/status",
                        fixture.workspaceId(),
                        requestId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(
                                delegationToken(
                                        fixture.userId(),
                                        fixture.workspaceId(),
                                        List.of("devcollab.review.submit_document_change")
                                ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(requestId))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void delegatedTokenCannotCallUnannotatedRestEndpoint() throws Exception {
        Fixture fixture = fixture();

        mockMvc.perform(get("/api/v1/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer(
                                delegationToken(
                                        fixture.userId(),
                                        fixture.workspaceId(),
                                        List.of("devcollab.workspace.get_context")
                                ))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_ACCESS_INVALID"));
    }

    @Test
    void delegatedTokenWithoutAllowedToolsIsRejected() throws Exception {
        Fixture fixture = fixture();

        mockMvc.perform(get("/api/v1/workspaces/{workspaceId}", fixture.workspaceId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(
                                delegationTokenWithoutAllowedTools(
                                        fixture.userId(),
                                        fixture.workspaceId()
                                ))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_ACCESS_INVALID"));
    }

    private Fixture fixture() throws Exception {
        MvcResult registered = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username":"delegated_%s",
                                  "displayName":"Delegated Test",
                                  "password":"password123"
                                }
                                """.formatted(UUID.randomUUID().toString()
                                .substring(0, 8))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = responseJson(registered);
        String accessToken = body.get("accessToken").asText();
        UUID workspaceId = UUID.fromString(createWorkspace(accessToken));
        return new Fixture(
                UUID.fromString(body.get("userId").asText()),
                accessToken,
                workspaceId
        );
    }

    private String createWorkspace(String accessToken) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/workspaces")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Delegated REST\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return responseJson(result).get("id").asText();
    }

    private String delegationToken(
            UUID userId,
            UUID workspaceId,
            List<String> tools
    ) {
        return delegationToken(userId, workspaceId, UUID.randomUUID(), tools);
    }

    private String delegationToken(
            UUID userId,
            UUID workspaceId,
            UUID repositoryId,
            List<String> tools
    ) {
        return jwtTokenService.issueAgentDelegationToken(
                userId,
                UUID.randomUUID(),
                workspaceId,
                repositoryId,
                UUID.randomUUID(),
                "abc123",
                tools,
                Duration.ofMinutes(5)
        );
    }

    private String delegationTokenWithoutAllowedTools(
            UUID userId,
            UUID workspaceId
    ) {
        Instant now = Instant.now();
        return JWT.create()
                .withIssuer(jwtProperties.issuer())
                .withAudience(jwtProperties.audience())
                .withSubject(userId.toString())
                .withJWTId(UUID.randomUUID().toString())
                .withClaim("sid", UUID.randomUUID().toString())
                .withClaim("username", "agent-worker")
                .withClaim("token_type", JwtTokenService.TOKEN_TYPE_AGENT_DELEGATION)
                .withClaim("workspace_id", workspaceId.toString())
                .withIssuedAt(Date.from(now))
                .withExpiresAt(Date.from(now.plus(Duration.ofMinutes(5))))
                .sign(Algorithm.HMAC256(jwtProperties.secret()));
    }

    private JsonNode responseJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record Fixture(
            UUID userId,
            String accessToken,
            UUID workspaceId
    ) {
    }
}
