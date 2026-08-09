package com.devcollab.knowledgecore.documentchange.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
 * 回归测试（施工计划 1.1b/D2）：UPSERT 携带 bindingId 不得在自动应用路径
 * 删除他人绑定。
 *
 * 缺陷背景：applyBindingProposal 对 bindingId 非 null 的 UPSERT 在
 * findExactBinding 未命中时执行 deleteBinding(proposal.bindingId())——
 * binding-only 全 UPSERT 请求走自动应用（无审批），而
 * lockAndValidateBindingTargets 只校验 REMOVE 的 bindingId（UPSERT 的
 * bindingId 完全不受校验）。MEMBER 提交 [UPSERT(bindingId=他人绑定 id,
 * 字段不同)] → findExactBinding 不匹配 → 任意绑定被无审批删除。
 *
 * 修复后契约（本测试断言）：
 *   1. POST 不得自动应用（4xx 拒绝或创建后 PENDING）
 *   2. victim 绑定必须存活
 * 修复前：POST 201 + APPLIED + victim 被删 → 断言（2）失败（红）。
 *
 * 注意：本文件夹具复制自 BindingOnlyRemoveRequiresApprovalTests；
 * 邀请接口 POST /workspaces/{id}/members/invitations，body {username, role}。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BindingUpsertCannotDeleteArbitraryBindingTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void memberUpsertCarryingForeignBindingId_neverDeletesIt() throws Exception {
        Fixture admin = fixture("upsert_admin_");
        Fixture member = fixture("upsert_member_");
        inviteMember(admin, member);

        UUID victimId = seedSymbolBinding(admin, "PYTHON:src/Example.java:Foo:CLASS");

        String payload = """
                {
                  "clientRequestId":"upsert-victim-%s",
                  "summary":"UPSERT 携带他人 bindingId",
                  "rationale":"回归测试：自动路径不得删除 victim。",
                  "operations":[],
                  "bindingProposals":[{
                    "clientBindingProposalId":"upsert-victim",
                    "sequenceNumber":1,
                    "action":"UPSERT_BINDING",
                    "repositoryId":"%s",
                    "revision":"A",
                    "filePath":"src/Example.java",
                    "anchorKind":"SYMBOL",
                    "symbolKey":"PYTHON:src/Example.java:Foo:CLASS",
                    "startLine":30,
                    "endLine":40,
                    "documentId":"%s",
                    "blockId":null,
                    "bindingId":"%s",
                    "reason":"回归测试"
                  }]
                }
                """.formatted(
                UUID.randomUUID(),
                admin.repositoryId(),
                admin.documentId(),
                victimId
        );

        MvcResult created = mockMvc.perform(post(
                        "/api/v1/workspaces/{workspaceId}/document-change-requests",
                        admin.workspaceId()
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(member.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andReturn();
        // 拒绝（4xx）或创建后停审批（201 PENDING）均可；500 视为实现缺陷
        assertThat(created.getResponse().getStatus())
                .isLessThan(HttpStatus.INTERNAL_SERVER_ERROR.value());

        // 若创建成功（201），CR 必须停在 PENDING——不得自动 APPLIED
        // （detail 端点 requireAdmin，MEMBER 无查看权限，用 admin token 查询）
        if (created.getResponse().getStatus() == HttpStatus.CREATED.value()) {
            String requestId = objectMapper
                    .readTree(created.getResponse().getContentAsString())
                    .get("changeRequestId").asText();
            mockMvc.perform(get(
                            "/api/v1/workspaces/{workspaceId}/document-change-requests/{requestId}",
                            admin.workspaceId(),
                            requestId
                    )
                            .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.request.status").value("PENDING"));
        }

        // 核心断言：victim 绑定必须存活（修复前被 deleteBinding 静默删除）
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM code_document_bindings WHERE id = ?",
                Integer.class,
                victimId
        );
        assertThat(count).isEqualTo(1);
    }

    // ── Fixture（复制自 BindingOnlyRemoveRequiresApprovalTests） ────────────

    private Fixture fixture(String usernamePrefix) throws Exception {
        String username = usernamePrefix + UUID.randomUUID().toString().substring(0, 8);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterBody(username, "Tester", "password123")
                        )))
                .andExpect(status().isCreated())
                .andReturn();
        String token = responseJson(result).get("accessToken").asText();

        String workspaceId = responseJson(mockMvc.perform(post("/api/v1/workspaces")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Upsert Binding Workspace\"}"))
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
                                  "name":"Upsert Binding Repository",
                                  "provider":"GENERIC",
                                  "remoteUrl":"https://example.com/upsert-binding.git",
                                  "defaultBranch":"main"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn()).get("id").asText();
        return new Fixture(token, username, workspaceId, documentId, repositoryId);
    }

    private void inviteMember(Fixture admin, Fixture member) throws Exception {
        mockMvc.perform(post(
                        "/api/v1/workspaces/{workspaceId}/members/invitations",
                        admin.workspaceId()
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","role":"MEMBER"}
                                """.formatted(member.username())))
                .andExpect(status().isCreated());
    }

    private UUID seedSymbolBinding(Fixture admin, String symbolKey) {
        UUID bindingId = UUID.randomUUID();
        UUID createdBy = jdbcTemplate.queryForObject(
                "SELECT id FROM user_accounts WHERE username = ?",
                UUID.class,
                admin.username()
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
                UUID.fromString(admin.workspaceId()),
                UUID.fromString(admin.repositoryId()),
                UUID.fromString(admin.documentId()),
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

    private record RegisterBody(
            String username,
            String displayName,
            String password
    ) {
    }
}
