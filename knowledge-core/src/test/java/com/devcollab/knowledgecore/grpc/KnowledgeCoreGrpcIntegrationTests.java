package com.devcollab.knowledgecore.grpc;

import com.devcollab.protocol.core.v1.ApplyDocumentOperationRequest;
import com.devcollab.protocol.core.v1.DocumentOperationStatus;
import com.devcollab.protocol.core.v1.DocumentOperationType;
import com.devcollab.protocol.core.v1.KnowledgeCoreCollaborationServiceGrpc;
import com.devcollab.protocol.core.v1.ListDocumentOperationsRequest;
import com.devcollab.protocol.core.v1.VerifyDocumentAccessRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KnowledgeCoreGrpcIntegrationTests {

    private static final Metadata.Key<String> AUTHORIZATION = Metadata.Key.of(
            "authorization",
            Metadata.ASCII_STRING_MARSHALLER
    );

    @Autowired
    private KnowledgeCoreCollaborationGrpcService grpcService;

    @Autowired
    private GrpcJwtAuthenticationInterceptor authenticationInterceptor;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private Server server;
    private ManagedChannel channel;

    @BeforeAll
    void startRealGrpcServer() throws Exception {
        server = ServerBuilder.forPort(0)
                .addService(ServerInterceptors.intercept(
                        grpcService,
                        authenticationInterceptor
                ))
                .build()
                .start();
        channel = ManagedChannelBuilder.forAddress(
                        "127.0.0.1",
                        server.getPort()
                )
                .usePlaintext()
                .build();
    }

    @AfterAll
    void stopRealGrpcServer() throws Exception {
        if (channel != null) {
            channel.shutdownNow().awaitTermination(3, TimeUnit.SECONDS);
        }
        if (server != null) {
            server.shutdownNow().awaitTermination(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void authenticatedMemberCanVerifyApplyAndCatchUpOverRealGrpcTransport()
            throws Exception {
        Fixture fixture = fixture();
        var stub = authenticatedStub(fixture.token());

        var access = stub.verifyDocumentAccess(
                VerifyDocumentAccessRequest.newBuilder()
                        .setDocumentId(fixture.documentId())
                        .build()
        );
        assertThat(access.getDocumentId()).isEqualTo(fixture.documentId());
        assertThat(access.getWorkspaceId()).isEqualTo(fixture.workspaceId());

        UUID operationId = UUID.randomUUID();
        var request = ApplyDocumentOperationRequest.newBuilder()
                .setDocumentId(fixture.documentId())
                .setClientOperationId(operationId.toString())
                .setOperationType(DocumentOperationType.UPDATE_TEXT)
                .setBlockId(fixture.blockId())
                .setExpectedVersion(0)
                .setContentSchemaVersion(1)
                .setContentJson("""
                        {"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"Saved through gRPC JSON"}]}]}
                        """)
                .build();

        var applied = stub.applyDocumentOperation(request);
        assertThat(applied.getStatus())
                .isEqualTo(DocumentOperationStatus.APPLIED);
        assertThat(applied.getDocumentSequence()).isEqualTo(1);
        assertThat(applied.getBlock().getText())
                .isEqualTo("Saved through gRPC JSON");
        assertThat(applied.getBlock().getContentSchemaVersion()).isEqualTo(1);
        assertThat(applied.getBlock().getContentJson()).contains("Saved through gRPC JSON");
        assertThat(applied.getBlock().getVersion()).isEqualTo(1);

        var duplicate = stub.applyDocumentOperation(request);
        assertThat(duplicate.getStatus())
                .isEqualTo(DocumentOperationStatus.DUPLICATE);
        assertThat(duplicate.getDocumentSequence()).isEqualTo(1);
        assertThat(duplicate.getBlock().getVersion()).isEqualTo(1);

        var page = stub.listDocumentOperations(
                ListDocumentOperationsRequest.newBuilder()
                        .setDocumentId(fixture.documentId())
                        .setAfterSequence(0)
                        .setLimit(1)
                        .build()
        );
        assertThat(page.getLatestDocumentSequence()).isEqualTo(1);
        assertThat(page.getHasMore()).isFalse();
        assertThat(page.getOperationsCount()).isEqualTo(1);
        assertThat(page.getOperations(0).getClientOperationId())
                .isEqualTo(operationId.toString());
    }

    @Test
    void missingBearerTokenIsUnauthenticated() {
        var stub = KnowledgeCoreCollaborationServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(3, TimeUnit.SECONDS);

        assertThatThrownBy(() -> stub.verifyDocumentAccess(
                VerifyDocumentAccessRequest.newBuilder()
                        .setDocumentId(UUID.randomUUID().toString())
                        .build()
        )).isInstanceOfSatisfying(
                StatusRuntimeException.class,
                exception -> assertThat(exception.getStatus().getCode())
                        .isEqualTo(Status.Code.UNAUTHENTICATED)
        );
    }

    @Test
    void nonMemberIsPermissionDeniedWithStableErrorCode() throws Exception {
        Fixture fixture = fixture();
        String outsiderToken = registerAndGetAccessToken();

        assertThatThrownBy(() -> authenticatedStub(outsiderToken)
                .verifyDocumentAccess(
                        VerifyDocumentAccessRequest.newBuilder()
                                .setDocumentId(fixture.documentId())
                                .build()
                )).isInstanceOfSatisfying(
                StatusRuntimeException.class,
                exception -> {
                    assertThat(exception.getStatus().getCode())
                            .isEqualTo(Status.Code.PERMISSION_DENIED);
                    assertThat(exception.getTrailers()).isNotNull();
                    assertThat(exception.getTrailers().get(
                            GrpcExceptionMapper.ERROR_CODE
                    )).isEqualTo("WORKSPACE_ACCESS_DENIED");
                }
        );
    }

    @Test
    void invalidUuidIsInvalidArgumentWithStableErrorCode() throws Exception {
        String token = registerAndGetAccessToken();

        assertThatThrownBy(() -> authenticatedStub(token).verifyDocumentAccess(
                VerifyDocumentAccessRequest.newBuilder()
                        .setDocumentId("not-a-uuid")
                        .build()
        )).isInstanceOfSatisfying(
                StatusRuntimeException.class,
                exception -> {
                    assertThat(exception.getStatus().getCode())
                            .isEqualTo(Status.Code.INVALID_ARGUMENT);
                    assertThat(exception.getTrailers()).isNotNull();
                    assertThat(exception.getTrailers().get(
                            GrpcExceptionMapper.ERROR_CODE
                    )).isEqualTo("INVALID_ARGUMENT");
                }
        );
    }

    private KnowledgeCoreCollaborationServiceGrpc
            .KnowledgeCoreCollaborationServiceBlockingStub authenticatedStub(
            String token
    ) {
        Metadata metadata = new Metadata();
        metadata.put(AUTHORIZATION, "Bearer " + token);
        return KnowledgeCoreCollaborationServiceGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(
                        metadata
                ))
                .withDeadlineAfter(3, TimeUnit.SECONDS);
    }

    private Fixture fixture() throws Exception {
        String token = registerAndGetAccessToken();
        String workspaceId = responseJson(mockMvc.perform(
                        post("/api/v1/workspaces")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(token)
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"gRPC workspace\"}")
                )
                .andExpect(status().isCreated())
                .andReturn()).get("id").asText();
        String documentId = responseJson(mockMvc.perform(post(
                        "/api/v1/workspaces/{id}/documents",
                        workspaceId
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"gRPC document\"}"))
                .andExpect(status().isCreated())
                .andReturn()).get("id").asText();
        String blockId = responseJson(mockMvc.perform(post(
                        "/api/v1/documents/{id}/blocks",
                        documentId
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"PARAGRAPH","content":{"text":"Initial"}}
                                """))
                .andExpect(status().isCreated())
                .andReturn()).get("id").asText();
        return new Fixture(token, workspaceId, documentId, blockId);
    }

    private String registerAndGetAccessToken() throws Exception {
        String username = "grpc_" + UUID.randomUUID()
                .toString()
                .substring(0, 8);
        String body = objectMapper.writeValueAsString(new RegisterBody(
                username,
                "gRPC Tester",
                "password123"
        ));
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

    private record Fixture(
            String token,
            String workspaceId,
            String documentId,
            String blockId
    ) {
    }

    private record RegisterBody(
            String username,
            String displayName,
            String password
    ) {
    }
}
