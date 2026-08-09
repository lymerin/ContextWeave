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

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 回归测试（施工计划 1.2）：含 REMOVE_BINDING 的 binding-only 变更请求
 * 必须走人工审批（PENDING），禁止自动应用。
 *
 * 缺陷背景：DocumentChangeApplicationService.create() 对零 operations 的
 * binding-only 请求直接 applyBindingOnly()（源码自带
 * "// FIXME: bypasses human review, should require approval"）——
 * 普通 MEMBER 可借此删除他人（含 admin 创建）的文档-代码绑定，且无审批。
 *
 * 修复后契约（本测试断言）：
 *   POST binding-only CR（REMOVE_BINDING）→ 返回 PENDING，绑定表不变。
 * 修复前：create() 返回 APPLIED 且绑定被删除 → jsonPath("$.request.status")
 * 断言 PENDING 失败（红）。
 *
 * 注意：本文件夹具复制自 PreciseBindingProposalIntegrationTests
 * （该文件 Fixture 为私有）。若种子绑定 INSERT 因约束报错，参照
 * GitKnowledgeIntegrationTests.databaseConstraintsProtectLegacyDefaultsAndPreciseIdentity
 * 调整列；REMOVE_BINDING 的 documentId/bindingId/symbolKey 必须与种子一致。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BindingOnlyRemoveRequiresApprovalTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void memberRemoveBindingProposal_requiresApproval_notAutoApplied() throws Exception {
        Fixture fixture = fixture();
        UUID bindingId = seedSymbolBinding(fixture, "PYTHON:src/Example.java:Foo:CLASS");

        String payload = """
                {
                  "clientRequestId":"remove-%s",
                  "summary":"成员删除他人绑定",
                  "rationale":"回归测试：REMOVE_BINDING 必须走审批。",
                  "operations":[],
                  "bindingProposals":[{
                    "clientBindingProposalId":"remove-bind",
                    "sequenceNumber":1,
                    "action":"REMOVE_BINDING",
                    "repositoryId":"%s",
                    "revision":"A",
                    "filePath":"src/Example.java",
                    "anchorKind":"SYMBOL",
                    "symbolKey":"PYTHON:src/Example.java:Foo:CLASS",
                    "startLine":10,
                    "endLine":20,
                    "documentId":"%s",
                    "blockId":null,
                    "bindingId":"%s",
                    "reason":"回归测试"
                  }]
                }
                """.formatted(
                UUID.randomUUID(),
                fixture.repositoryId(),
                fixture.documentId(),
                bindingId
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

        // 核心断言：REMOVE_BINDING 不得自动应用 —— 必须停在 PENDING 等审批
        mockMvc.perform(get(
                        "/api/v1/workspaces/{workspaceId}/document-change-requests/{requestId}",
                        fixture.workspaceId(),
                        requestId
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(fixture.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request.status").value("PENDING"));

        // 结果级断言：绑定必须仍然存在（未被静默删除）
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM code_document_bindings WHERE id = ?",
                Integer.class,
                bindingId
        );
        assertThat(count).isEqualTo(1);
    }

    // ── Fixture（复制自 PreciseBindingProposalIntegrationTests） ───────────

    private Fixture fixture() throws Exception {
        RegisterResult registered = registerUser();
        String token = registered.token();
        String username = registered.username();
        String workspaceId = responseJson(mockMvc.perform(post("/api/v1/workspaces")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Binding Approval Workspace\"}"))
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
                                  "name":"Binding Approval Repository",
                                  "provider":"GENERIC",
                                  "remoteUrl":"https://example.com/binding-approval.git",
                                  "defaultBranch":"main"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn()).get("id").asText();
        return new Fixture(token, username, workspaceId, documentId, repositoryId);
    }

    private RegisterResult registerUser() throws Exception {
        String username = "binding_approval_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterBody(username, "Tester", "password123")
                        )))
                .andExpect(status().isCreated())
                .andReturn();
        return new RegisterResult(
                username,
                responseJson(result).get("accessToken").asText()
        );
    }

    private UUID seedSymbolBinding(Fixture fixture, String symbolKey) {
        UUID bindingId = UUID.randomUUID();
        UUID createdBy = jdbcTemplate.queryForObject(
                "SELECT id FROM user_accounts WHERE username = ?",
                UUID.class,
                fixture.username()
        );
        jdbcTemplate.update("""
                INSERT INTO code_document_bindings
                    (id, workspace_id, repository_id, document_id, block_id,
                     target_key, path_pattern, revision, anchor_kind, symbol_key,
                     start_line, end_line, revision_key, symbol_key_identity,
                     start_line_key, end_line_key, created_by, created_at)
                VALUES (?, ?, ?, ?, NULL, 'DOCUMENT', 'src/Example.java',
                        'A', 'SYMBOL', ?, 10, 20, 'A', ?, 10, 20, ?, ?)
                """,
                bindingId,
                UUID.fromString(fixture.workspaceId()),
                UUID.fromString(fixture.repositoryId()),
                UUID.fromString(fixture.documentId()),
                symbolKey,
                symbolKey,
                createdBy,
                Instant.now()
        );
        return bindingId;
    }

    private JsonNode responseJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record Fixture(
            String token,
            String username,
            String workspaceId,
            String documentId,
            String repositoryId
    ) {
    }

    private record RegisterResult(
            String username,
            String token
    ) {
    }

    private record RegisterBody(
            String username,
            String displayName,
            String password
    ) {
    }
}
