package com.devcollab.knowledgecore.documentchange.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PreciseBindingProposalIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldPersistAndApplyCreatedBlockPreciseBindingsAtomically() throws Exception {
        Fixture fixture = fixture();
        String payload = """
                {
                  "clientRequestId":"precise-%s",
                  "summary":"块级 Binding",
                  "rationale":"验证新建文档和 Block 的正式锚点映射。",
                  "operations":[
                    {
                      "clientOperationId":"create-doc",
                      "sequenceNumber":1,
                      "operationType":"CREATE_DOCUMENT",
                      "proposedDocumentTitle":"上下文构建与预算模块",
                      "proposedDocumentType":"BACKEND"
                    },
                    {
                      "clientOperationId":"add-budget",
                      "sequenceNumber":2,
                      "operationType":"ADD_BLOCK",
                      "createdDocumentClientOperationId":"create-doc",
                      "proposedBlockType":"PARAGRAPH",
                      "proposedPlainText":"预算模块负责限制上下文大小。"
                    },
                    {
                      "clientOperationId":"add-builder",
                      "sequenceNumber":3,
                      "operationType":"ADD_BLOCK",
                      "createdDocumentClientOperationId":"create-doc",
                      "proposedBlockType":"PARAGRAPH",
                      "proposedPlainText":"构建器负责组装上下文。"
                    }
                  ],
                  "bindingProposals":[
                    {
                      "clientBindingProposalId":"bind-budget",
                      "sequenceNumber":4,
                      "action":"UPSERT_BINDING",
                      "repositoryId":"%s",
                      "revision":"abc123",
                      "filePath":"agent-service/app/context/budget.py",
                      "anchorKind":"RANGE",
                      "startLine":10,
                      "endLine":30,
                      "createdDocumentClientOperationId":"create-doc",
                      "createdBlockClientOperationId":"add-budget",
                      "candidateId":"code_candidate_budget",
                      "documentAnchorCandidateId":"doc_candidate_budget",
                      "reason":"预算代码对应预算职责 Block。",
                      "confidence":0.95
                    },
                    {
                      "clientBindingProposalId":"bind-builder",
                      "sequenceNumber":5,
                      "action":"UPSERT_BINDING",
                      "repositoryId":"%s",
                      "revision":"abc123",
                      "filePath":"agent-service/app/context/builder.py",
                      "anchorKind":"SYMBOL",
                      "symbolKey":"PYTHON:agent-service/app/context/builder.py:build:FUNCTIONDEF",
                      "startLine":4,
                      "endLine":18,
                      "createdDocumentClientOperationId":"create-doc",
                      "createdBlockClientOperationId":"add-builder",
                      "candidateId":"code_candidate_builder",
                      "documentAnchorCandidateId":"doc_candidate_builder",
                      "reason":"构建函数对应上下文组装 Block。",
                      "confidence":0.91
                    }
                  ]
                }
                """.formatted(
                UUID.randomUUID(),
                fixture.repositoryId(),
                fixture.repositoryId()
        );
        JsonNode created = responseJson(mockMvc.perform(post(
                        "/api/v1/workspaces/{workspaceId}/document-change-requests",
                        fixture.workspaceId()
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(fixture.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn());
        String requestId = created.get("changeRequestId").asText();

        mockMvc.perform(get(
                        "/api/v1/workspaces/{workspaceId}/document-change-requests/{requestId}",
                        fixture.workspaceId(),
                        requestId
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(fixture.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bindingProposals[0].anchorKind").value("RANGE"))
                .andExpect(jsonPath("$.bindingProposals[0].createdBlockClientOperationId")
                        .value("add-budget"))
                .andExpect(jsonPath("$.bindingProposals[1].anchorKind").value("SYMBOL"))
                .andExpect(jsonPath("$.bindingProposals[1].symbolKey")
                        .value("PYTHON:agent-service/app/context/builder.py:build:FUNCTIONDEF"))
                .andExpect(jsonPath("$.bindingProposals[1].confidence").value(0.91));

        JsonNode applied = responseJson(mockMvc.perform(post(
                        "/api/v1/workspaces/{workspaceId}/document-change-requests/{requestId}/apply",
                        fixture.workspaceId(),
                        requestId
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(fixture.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request.status").value("APPLIED"))
                .andExpect(jsonPath("$.applyResult.createdDocuments").isMap())
                .andExpect(jsonPath("$.applyResult.createdBlocks").isMap())
                .andExpect(jsonPath("$.applyResult.bindings.length()").value(2))
                .andReturn());

        assertThat(applied.path("applyResult").path("createdBlocks").size()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM code_document_bindings
                 WHERE repository_id = ?
                   AND revision = 'abc123'
                   AND block_id IS NOT NULL
                """, Integer.class, UUID.fromString(fixture.repositoryId())))
                .isEqualTo(2);
        List<Map<String, Object>> bindings = jdbcTemplate.queryForList("""
                SELECT path_pattern, anchor_kind, symbol_key, start_line, end_line
                  FROM code_document_bindings
                 WHERE repository_id = ?
                   AND revision = 'abc123'
                 ORDER BY path_pattern
                """, UUID.fromString(fixture.repositoryId()));
        assertThat(bindings).hasSize(2);
        assertThat(bindings.get(0))
                .containsEntry("PATH_PATTERN", "agent-service/app/context/budget.py")
                .containsEntry("ANCHOR_KIND", "RANGE")
                .containsEntry("START_LINE", 10)
                .containsEntry("END_LINE", 30);
        assertThat(bindings.get(1))
                .containsEntry("PATH_PATTERN", "agent-service/app/context/builder.py")
                .containsEntry("ANCHOR_KIND", "SYMBOL")
                .containsEntry(
                        "SYMBOL_KEY",
                        "PYTHON:agent-service/app/context/builder.py:build:FUNCTIONDEF"
                )
                .containsEntry("START_LINE", 4)
                .containsEntry("END_LINE", 18);

        mockMvc.perform(post(
                        "/api/v1/workspaces/{workspaceId}/document-change-requests/{requestId}/apply",
                        fixture.workspaceId(),
                        requestId
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(fixture.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request.status").value("APPLIED"))
                .andExpect(jsonPath("$.replayed").value(true));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM code_document_bindings
                 WHERE repository_id = ? AND revision = 'abc123'
                """, Integer.class, UUID.fromString(fixture.repositoryId())))
                .isEqualTo(2);
    }

    @Test
    void shouldRejectCreatedBlockReferenceThatIsNotAddBlock() throws Exception {
        Fixture fixture = fixture();
        String payload = """
                {
                  "clientRequestId":"invalid-%s",
                  "summary":"无效 Block 引用",
                  "rationale":"验证引用类型。",
                  "operations":[{
                    "clientOperationId":"create-doc",
                    "sequenceNumber":1,
                    "operationType":"CREATE_DOCUMENT",
                    "proposedDocumentTitle":"测试文档"
                  }],
                  "bindingProposals":[{
                    "clientBindingProposalId":"bind",
                    "sequenceNumber":2,
                    "action":"UPSERT_BINDING",
                    "repositoryId":"%s",
                    "filePath":"src/Test.java",
                    "documentId":"%s",
                    "createdBlockClientOperationId":"create-doc",
                    "reason":"无效引用"
                  }]
                }
                """.formatted(
                UUID.randomUUID(),
                fixture.repositoryId(),
                fixture.documentId()
        );
        mockMvc.perform(post(
                        "/api/v1/workspaces/{workspaceId}/document-change-requests",
                        fixture.workspaceId()
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(fixture.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("DOCUMENT_CHANGE_OPERATION_INVALID"));
    }

    @Test
    void shouldApplyExistingDocumentAndExistingBlockProposal() throws Exception {
        Fixture fixture = fixture();
        String blockId = responseJson(mockMvc.perform(post(
                        "/api/v1/documents/{documentId}/blocks",
                        fixture.documentId()
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(fixture.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type":"PARAGRAPH",
                                  "content":{"text":"现有文档块描述上下文预算职责。"}
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn()).get("id").asText();
        String payload = """
                {
                  "clientRequestId":"existing-%s",
                  "summary":"绑定现有文档块",
                  "rationale":"验证正式 Block 引用和文件级兼容。",
                  "operations":[],
                  "bindingProposals":[
                    {
                      "clientBindingProposalId":"existing-symbol",
                      "sequenceNumber":1,
                      "action":"UPSERT_BINDING",
                      "repositoryId":"%s",
                      "revision":"abc123",
                      "filePath":"agent-service/app/context/budget.py",
                      "anchorKind":"SYMBOL",
                      "symbolKey":"PYTHON:agent-service/app/context/budget.py:ContextBudget:CLASSDEF",
                      "startLine":5,
                      "endLine":25,
                      "documentId":"%s",
                      "blockId":"%s",
                      "reason":"现有 Block 与预算符号职责一致。",
                      "confidence":0.94
                    },
                    {
                      "clientBindingProposalId":"legacy-file",
                      "sequenceNumber":2,
                      "action":"UPSERT_BINDING",
                      "repositoryId":"%s",
                      "filePath":"README.md",
                      "documentId":"%s",
                      "reason":"保留文件级文档绑定兼容语义。"
                    }
                  ]
                }
                """.formatted(
                UUID.randomUUID(),
                fixture.repositoryId(),
                fixture.documentId(),
                blockId,
                fixture.repositoryId(),
                fixture.documentId()
        );
        JsonNode created = responseJson(mockMvc.perform(post(
                        "/api/v1/workspaces/{workspaceId}/document-change-requests",
                        fixture.workspaceId()
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(fixture.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn());

        mockMvc.perform(post(
                        "/api/v1/workspaces/{workspaceId}/document-change-requests/{requestId}/apply",
                        fixture.workspaceId(),
                        created.get("changeRequestId").asText()
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(fixture.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request.status").value("APPLIED"))
                .andExpect(jsonPath("$.applyResult.bindings.length()").value(2));

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM code_document_bindings
                 WHERE repository_id = ?
                   AND document_id = ?
                   AND block_id = ?
                   AND anchor_kind = 'SYMBOL'
                """, Integer.class,
                UUID.fromString(fixture.repositoryId()),
                UUID.fromString(fixture.documentId()),
                UUID.fromString(blockId)))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM code_document_bindings
                 WHERE repository_id = ?
                   AND document_id = ?
                   AND block_id IS NULL
                   AND anchor_kind = 'FILE'
                """, Integer.class,
                UUID.fromString(fixture.repositoryId()),
                UUID.fromString(fixture.documentId())))
                .isEqualTo(1);
    }

    @Test
    void shouldPersistPrimaryAndSupportingOrderForOneBlock() throws Exception {
        Fixture fixture = fixture();
        String blockId = responseJson(mockMvc.perform(post(
                        "/api/v1/documents/{documentId}/blocks",
                        fixture.documentId()
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(fixture.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"PARAGRAPH","content":{"text":"接口职责与辅助转换。"}}
                                """))
                .andExpect(status().isCreated())
                .andReturn()).get("id").asText();
        String payload = """
                {
                  "clientRequestId":"role-order-%s",
                  "summary":"验证主要与辅助代码顺序",
                  "rationale":"同一 Block 只允许一个主要代码，并按序保存辅助代码。",
                  "operations":[],
                  "bindingProposals":[
                    {
                      "clientBindingProposalId":"primary",
                      "sequenceNumber":1,
                      "action":"UPSERT_BINDING",
                      "repositoryId":"%s",
                      "revision":"abc123",
                      "filePath":"app/main.py",
                      "anchorKind":"RANGE",
                      "startLine":10,
                      "endLine":20,
                      "documentId":"%s",
                      "blockId":"%s",
                      "bindingRole":"PRIMARY",
                      "bindingOrdinal":1,
                      "reason":"接口入口是主要代码。"
                    },
                    {
                      "clientBindingProposalId":"supporting",
                      "sequenceNumber":2,
                      "action":"UPSERT_BINDING",
                      "repositoryId":"%s",
                      "revision":"abc123",
                      "filePath":"app/schemas.py",
                      "anchorKind":"RANGE",
                      "startLine":30,
                      "endLine":40,
                      "documentId":"%s",
                      "blockId":"%s",
                      "bindingRole":"SUPPORTING",
                      "bindingOrdinal":2,
                      "reason":"请求转换是辅助代码。"
                    }
                  ]
                }
                """.formatted(
                UUID.randomUUID(),
                fixture.repositoryId(), fixture.documentId(), blockId,
                fixture.repositoryId(), fixture.documentId(), blockId
        );
        JsonNode created = responseJson(mockMvc.perform(post(
                        "/api/v1/workspaces/{workspaceId}/document-change-requests",
                        fixture.workspaceId()
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(fixture.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bindingProposals[0].bindingRole").value("PRIMARY"))
                .andExpect(jsonPath("$.bindingProposals[1].bindingRole").value("SUPPORTING"))
                .andReturn());

        mockMvc.perform(post(
                        "/api/v1/workspaces/{workspaceId}/document-change-requests/{requestId}/apply",
                        fixture.workspaceId(), created.get("changeRequestId").asText()
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(fixture.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request.status").value("APPLIED"));

        List<Map<String, Object>> roleBindings = jdbcTemplate.queryForList("""
                SELECT binding_role, binding_ordinal, path_pattern
                  FROM code_document_bindings
                 WHERE repository_id = ? AND block_id = ?
                 ORDER BY binding_ordinal
                """, UUID.fromString(fixture.repositoryId()), UUID.fromString(blockId));
        assertThat(roleBindings).hasSize(2);
        assertThat(roleBindings.get(0))
                .containsEntry("BINDING_ROLE", "PRIMARY")
                .containsEntry("BINDING_ORDINAL", 1);
        assertThat(roleBindings.get(1))
                .containsEntry("BINDING_ROLE", "SUPPORTING")
                .containsEntry("BINDING_ORDINAL", 2);
    }

    @Test
    void shouldRollbackCreatedDocumentAndBlockWhenBindingApplyFails() throws Exception {
        Fixture fixture = fixture();
        String title = "Rollback " + UUID.randomUUID();
        String payload = """
                {
                  "clientRequestId":"rollback-%s",
                  "summary":"验证事务回滚",
                  "rationale":"正式 Binding 失败时不得留下文档或 Block。",
                  "operations":[
                    {
                      "clientOperationId":"create-doc",
                      "sequenceNumber":1,
                      "operationType":"CREATE_DOCUMENT",
                      "proposedDocumentTitle":"%s"
                    },
                    {
                      "clientOperationId":"add-block",
                      "sequenceNumber":2,
                      "operationType":"ADD_BLOCK",
                      "createdDocumentClientOperationId":"create-doc",
                      "proposedBlockType":"PARAGRAPH",
                      "proposedPlainText":"该内容必须与失败的 Apply 一起回滚。"
                    }
                  ],
                  "bindingProposals":[{
                    "clientBindingProposalId":"bind",
                    "sequenceNumber":3,
                    "action":"UPSERT_BINDING",
                    "repositoryId":"%s",
                    "filePath":"src/missing.py",
                    "createdDocumentClientOperationId":"create-doc",
                    "createdBlockClientOperationId":"add-block",
                    "reason":"触发仓库不存在错误。"
                  }]
                }
                """.formatted(UUID.randomUUID(), title, fixture.repositoryId());
        JsonNode created = responseJson(mockMvc.perform(post(
                        "/api/v1/workspaces/{workspaceId}/document-change-requests",
                        fixture.workspaceId()
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(fixture.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn());
        String requestId = created.get("changeRequestId").asText();
        jdbcTemplate.update(
                "DELETE FROM git_repositories WHERE id = ?",
                UUID.fromString(fixture.repositoryId())
        );

        mockMvc.perform(post(
                        "/api/v1/workspaces/{workspaceId}/document-change-requests/{requestId}/apply",
                        fixture.workspaceId(),
                        requestId
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(fixture.token())))
                .andExpect(status().isNotFound());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM documents WHERE title = ?",
                Integer.class,
                title
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM document_change_requests WHERE id = ?",
                String.class,
                UUID.fromString(requestId)
        )).isEqualTo("PENDING");
    }

    private Fixture fixture() throws Exception {
        String token = registerAndGetAccessToken();
        String workspaceId = responseJson(mockMvc.perform(post("/api/v1/workspaces")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Precise Binding Workspace\"}"))
                .andExpect(status().isCreated())
                .andReturn()).get("id").asText();
        String documentId = responseJson(mockMvc.perform(post(
                        "/api/v1/workspaces/{id}/documents",
                        workspaceId
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Existing\",\"documentType\":\"REQUIREMENT\"}"))
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
                                  "name":"Precise Repository",
                                  "provider":"GENERIC",
                                  "remoteUrl":"https://example.com/precise.git",
                                  "defaultBranch":"main"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn()).get("id").asText();
        return new Fixture(token, workspaceId, documentId, repositoryId);
    }

    private String registerAndGetAccessToken() throws Exception {
        String username = "precise_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterBody(username, "Tester", "password123")
                        )))
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

    private record Fixture(
            String token,
            String workspaceId,
            String documentId,
            String repositoryId
    ) {
    }

    private record RegisterBody(
            String username,
            String displayName,
            String password
    ) {
    }
}
