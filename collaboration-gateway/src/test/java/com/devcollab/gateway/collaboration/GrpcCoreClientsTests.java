package com.devcollab.gateway.collaboration;

import com.devcollab.protocol.core.v1.ApplyDocumentOperationRequest;
import com.devcollab.protocol.core.v1.DocumentBlock;
import com.devcollab.protocol.core.v1.DocumentBlockType;
import com.devcollab.protocol.core.v1.DocumentOperationResponse;
import com.devcollab.protocol.core.v1.DocumentOperationStatus;
import com.devcollab.protocol.core.v1.DocumentOperationType;
import com.devcollab.protocol.core.v1.KnowledgeCoreCollaborationServiceGrpc;
import com.devcollab.protocol.core.v1.ListDocumentOperationsRequest;
import com.devcollab.protocol.core.v1.ListDocumentOperationsResponse;
import com.devcollab.protocol.core.v1.VerifyDocumentAccessRequest;
import com.devcollab.protocol.core.v1.VerifyDocumentAccessResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Timestamp;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GrpcCoreClientsTests {

    private final AtomicReference<String> authorization =
            new AtomicReference<>();
    private final AtomicReference<Status> nextApplyFailure =
            new AtomicReference<>();
    private final AtomicReference<String> nextErrorCode =
            new AtomicReference<>();
    private final AtomicReference<Duration> verifyDelay =
            new AtomicReference<>(Duration.ZERO);

    private Server server;
    private CoreGrpcChannel channel;
    private GrpcCoreDocumentAccessVerifier accessVerifier;
    private GrpcCoreDocumentOperationClient operationClient;
    private SimpleMeterRegistry meterRegistry;
    private CoreGrpcClientMetrics metrics;

    @BeforeAll
    void startServerAndClient() throws Exception {
        ServerInterceptor metadataCapture = new ServerInterceptor() {
            private final Metadata.Key<String> key = Metadata.Key.of(
                    "authorization",
                    Metadata.ASCII_STRING_MARSHALLER
            );

            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                    ServerCall<ReqT, RespT> call,
                    Metadata headers,
                    ServerCallHandler<ReqT, RespT> next
            ) {
                authorization.set(headers.get(key));
                return next.startCall(call, headers);
            }
        };
        server = ServerBuilder.forPort(0)
                .addService(ServerInterceptors.intercept(
                        new FakeCoreService(),
                        metadataCapture
                ))
                .build()
                .start();
        CoreGrpcClientProperties properties = properties(
                Duration.ofSeconds(3)
        );
        channel = new CoreGrpcChannel(properties);
        meterRegistry = new SimpleMeterRegistry();
        metrics = new CoreGrpcClientMetrics(meterRegistry, channel);
        accessVerifier = new GrpcCoreDocumentAccessVerifier(
                channel,
                properties,
                metrics
        );
        operationClient = new GrpcCoreDocumentOperationClient(
                channel,
                properties,
                metrics
        );
    }

    @AfterAll
    void stopServerAndClient() throws Exception {
        if (channel != null) {
            channel.close();
        }
        if (server != null) {
            server.shutdownNow().awaitTermination(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void accessVerifierSendsJwtMetadataOverRealGrpcTransport() {
        UUID documentId = UUID.randomUUID();

        accessVerifier.verifyCanAccess(documentId, "access-token");

        assertThat(authorization.get()).isEqualTo("Bearer access-token");
        assertThat(channel.channel().isShutdown()).isFalse();
    }

    @Test
    void operationClientMapsAppliedResultAndCatchUpPage() {
        UUID documentId = UUID.randomUUID();
        UUID blockId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();

        var applied = operationClient.apply(
                documentId,
                blockId,
                operationId,
                "operation-token",
                "UPDATE_TEXT",
                "gRPC text",
                2L,
                null,
                null
        );

        assertThat(applied.status()).isEqualTo("APPLIED");
        assertThat(applied.documentSequence()).isEqualTo(7);
        assertThat(applied.block().id()).isEqualTo(blockId);
        assertThat(applied.block().content().text()).isEqualTo("gRPC text");
        assertThat(applied.block().version()).isEqualTo(3);
        assertThat(authorization.get()).isEqualTo("Bearer operation-token");

        var page = operationClient.listAfter(
                documentId,
                "catch-up-token",
                4,
                1
        );
        assertThat(page.requestedAfterSequence()).isEqualTo(4);
        assertThat(page.latestDocumentSequence()).isEqualTo(7);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.operations()).hasSize(1);
        assertThat(page.operations().getFirst().documentSequence())
                .isEqualTo(7);
        assertThat(authorization.get()).isEqualTo("Bearer catch-up-token");
    }

    @Test
    void operationClientTransportsStructuredBlockContent() throws Exception {
        UUID documentId = UUID.randomUUID();
        UUID blockId = UUID.randomUUID();
        var document = new ObjectMapper().readTree("""
                {"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"Structured gRPC"}]}]}
                """);

        var applied = operationClient.apply(
                documentId,
                blockId,
                UUID.randomUUID(),
                "operation-token",
                "UPDATE_TEXT",
                new CollaborationMessages.DocumentOperationContent(
                        null,
                        1,
                        document
                ),
                2L,
                null,
                null
        );

        assertThat(applied.block().content().schemaVersion()).isEqualTo(1);
        assertThat(applied.block().content().document()).isEqualTo(document);
    }

    @Test
    void operationClientMapsBusinessFailureButPropagatesInfrastructureFailure() {
        nextApplyFailure.set(Status.ABORTED);
        nextErrorCode.set("DOCUMENT_BLOCK_VERSION_CONFLICT");

        var conflict = applyRandom();
        assertThat(conflict.status()).isEqualTo("CONFLICT");
        assertThat(conflict.message())
                .contains("DOCUMENT_BLOCK_VERSION_CONFLICT");

        nextApplyFailure.set(Status.PERMISSION_DENIED);
        nextErrorCode.set("WORKSPACE_ACCESS_DENIED");
        var rejected = applyRandom();
        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.message()).contains("WORKSPACE_ACCESS_DENIED");

        nextApplyFailure.set(Status.UNAVAILABLE);
        nextErrorCode.set(null);
        assertThatThrownBy(this::applyRandom)
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        exception -> assertThat(
                                exception.getStatus().getCode()
                        ).isEqualTo(Status.Code.UNAVAILABLE)
                );
    }

    @Test
    void operationClientRejectsInvalidLocalEnumValues() {
        var rejected = operationClient.apply(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "operation-token",
                "NOT_A_REAL_OPERATION",
                "text",
                0L,
                null,
                null
        );

        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.message()).contains("NOT_A_REAL_OPERATION");
    }

    @Test
    void deadlineExceededIsNotConvertedToBusinessRejection() {
        CoreGrpcClientProperties shortDeadline = properties(
                Duration.ofMillis(30)
        );
        GrpcCoreDocumentAccessVerifier shortDeadlineVerifier =
                new GrpcCoreDocumentAccessVerifier(
                        channel,
                        shortDeadline,
                        metrics
                );
        verifyDelay.set(Duration.ofMillis(150));

        assertThatThrownBy(() -> shortDeadlineVerifier.verifyCanAccess(
                UUID.randomUUID(),
                "slow-token"
        )).isInstanceOfSatisfying(
                StatusRuntimeException.class,
                exception -> assertThat(exception.getStatus().getCode())
                        .isEqualTo(Status.Code.DEADLINE_EXCEEDED)
        );
        verifyDelay.set(Duration.ZERO);
    }

    @Test
    void recordsGrpcStatusDurationAndChannelStateMetrics() {
        accessVerifier.verifyCanAccess(UUID.randomUUID(), "metrics-token");
        nextApplyFailure.set(Status.PERMISSION_DENIED);
        nextErrorCode.set("WORKSPACE_ACCESS_DENIED");
        applyRandom();

        assertThat(meterRegistry.counter(
                CoreGrpcClientMetrics.REQUESTS_METRIC,
                "method", "VerifyDocumentAccess",
                "status", "OK"
        ).count()).isGreaterThanOrEqualTo(1.0);
        assertThat(meterRegistry.timer(
                CoreGrpcClientMetrics.DURATION_METRIC,
                "method", "ApplyDocumentOperation",
                "status", "PERMISSION_DENIED"
        ).count()).isGreaterThanOrEqualTo(1L);
        double activeStates = meterRegistry.find(
                        CoreGrpcClientMetrics.CHANNEL_STATE_METRIC
                )
                .gauges()
                .stream()
                .mapToDouble(gauge -> gauge.value())
                .sum();
        assertThat(activeStates).isEqualTo(1.0);
    }

    private CoreDocumentOperationClient.CoreDocumentOperationResult
            applyRandom() {
        return operationClient.apply(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "access-token",
                "UPDATE_TEXT",
                "text",
                0L,
                null,
                null
        );
    }

    private CoreGrpcClientProperties properties(Duration deadline) {
        return new CoreGrpcClientProperties(
                "127.0.0.1",
                server.getPort(),
                true,
                deadline,
                1_048_576,
                Duration.ofSeconds(3)
        );
    }

    private final class FakeCoreService extends
            KnowledgeCoreCollaborationServiceGrpc
                    .KnowledgeCoreCollaborationServiceImplBase {

        @Override
        public void verifyDocumentAccess(
                VerifyDocumentAccessRequest request,
                StreamObserver<VerifyDocumentAccessResponse> observer
        ) {
            Duration delay = verifyDelay.getAndSet(Duration.ZERO);
            if (!delay.isZero()) {
                try {
                    Thread.sleep(delay.toMillis());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            observer.onNext(VerifyDocumentAccessResponse.newBuilder()
                    .setDocumentId(request.getDocumentId())
                    .setWorkspaceId(UUID.randomUUID().toString())
                    .build());
            observer.onCompleted();
        }

        @Override
        public void applyDocumentOperation(
                ApplyDocumentOperationRequest request,
                StreamObserver<DocumentOperationResponse> observer
        ) {
            Status failure = nextApplyFailure.getAndSet(null);
            if (failure != null) {
                Metadata trailers = new Metadata();
                String errorCode = nextErrorCode.getAndSet(null);
                if (errorCode != null) {
                    trailers.put(
                            Metadata.Key.of(
                                    "devcollab-error-code",
                                    Metadata.ASCII_STRING_MARSHALLER
                            ),
                            errorCode
                    );
                }
                observer.onError(failure.asRuntimeException(trailers));
                return;
            }

            DocumentBlock block = block(
                    request.getDocumentId(),
                    request.getBlockId(),
                    request.hasText() ? request.getText() : "Structured gRPC"
            );
            if (request.hasContentSchemaVersion()) {
                block = block.toBuilder()
                        .setContentSchemaVersion(request.getContentSchemaVersion())
                        .setContentJson(request.getContentJson())
                        .build();
            }
            observer.onNext(DocumentOperationResponse.newBuilder()
                    .setClientOperationId(request.getClientOperationId())
                    .setBlockId(request.getBlockId())
                    .setOperationType(request.getOperationType())
                    .setStatus(DocumentOperationStatus.APPLIED)
                    .setDocumentSequence(7)
                    .setOperatorUserId(UUID.randomUUID().toString())
                    .setBlock(block)
                    .build());
            observer.onCompleted();
        }

        @Override
        public void listDocumentOperations(
                ListDocumentOperationsRequest request,
                StreamObserver<ListDocumentOperationsResponse> observer
        ) {
            UUID operationId = UUID.randomUUID();
            UUID blockId = UUID.randomUUID();
            DocumentOperationResponse operation =
                    DocumentOperationResponse.newBuilder()
                            .setClientOperationId(operationId.toString())
                            .setBlockId(blockId.toString())
                            .setOperationType(DocumentOperationType.UPDATE_TEXT)
                            .setStatus(DocumentOperationStatus.APPLIED)
                            .setDocumentSequence(7)
                            .setOperatorUserId(UUID.randomUUID().toString())
                            .setBlock(block(
                                    request.getDocumentId(),
                                    blockId.toString(),
                                    "recovered"
                            ))
                            .build();
            observer.onNext(ListDocumentOperationsResponse.newBuilder()
                    .setRequestedAfterSequence(request.getAfterSequence())
                    .setLatestDocumentSequence(7)
                    .setHasMore(false)
                    .addOperations(operation)
                    .build());
            observer.onCompleted();
        }

        private DocumentBlock block(
                String documentId,
                String blockId,
                String text
        ) {
            Instant now = Instant.parse("2026-07-18T06:00:00Z");
            Timestamp timestamp = Timestamp.newBuilder()
                    .setSeconds(now.getEpochSecond())
                    .build();
            return DocumentBlock.newBuilder()
                    .setId(blockId)
                    .setDocumentId(documentId)
                    .setType(DocumentBlockType.PARAGRAPH)
                    .setText(text)
                    .setSortOrder(0)
                    .setVersion(3)
                    .setCreatedBy(UUID.randomUUID().toString())
                    .setCreatedAt(timestamp)
                    .setUpdatedAt(timestamp)
                    .build();
        }
    }
}
