package com.devcollab.gateway.collaboration;

import com.devcollab.gateway.collaboration.CollaborationMessages.CoreBlockContent;
import com.devcollab.gateway.collaboration.CollaborationMessages.CoreBlockResponse;
import com.devcollab.gateway.collaboration.CollaborationMessages.DocumentOperationContent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.devcollab.protocol.core.v1.ApplyDocumentOperationRequest;
import com.devcollab.protocol.core.v1.DocumentOperationResponse;
import com.devcollab.protocol.core.v1.DocumentOperationType;
import com.devcollab.protocol.core.v1.ListDocumentOperationsRequest;
import io.grpc.StatusRuntimeException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
@ConditionalOnProperty(
        prefix = "devcollab.gateway",
        name = "core-transport",
        havingValue = "grpc",
        matchIfMissing = true
)
public class GrpcCoreDocumentOperationClient
        implements CoreDocumentOperationClient {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final CoreGrpcChannel channel;
    private final CoreGrpcClientProperties properties;
    private final CoreGrpcClientMetrics metrics;

    public GrpcCoreDocumentOperationClient(
            CoreGrpcChannel channel,
            CoreGrpcClientProperties properties,
            CoreGrpcClientMetrics metrics
    ) {
        this.channel = channel;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Override
    public CoreDocumentOperationResult apply(
            UUID documentId,
            UUID blockId,
            UUID clientOperationId,
            String accessToken,
            String operationType,
            DocumentOperationContent content,
            Long expectedVersion,
            String blockType,
            Integer targetIndex
    ) {
        try {
            var request = ApplyDocumentOperationRequest.newBuilder()
                    .setDocumentId(documentId.toString())
                    .setClientOperationId(clientOperationId.toString())
                    .setOperationType(DocumentOperationType.valueOf(
                            operationType
                    ));
            if (blockId != null) {
                request.setBlockId(blockId.toString());
            }
            if (expectedVersion != null) {
                request.setExpectedVersion(expectedVersion);
            }
            if (blockType != null) {
                request.setBlockType(
                        com.devcollab.protocol.core.v1.DocumentBlockType
                                .valueOf(blockType)
                );
            }
            if (targetIndex != null) {
                request.setTargetIndex(targetIndex);
            }
            if (content != null) {
                if (content.text() != null) {
                    request.setText(content.text());
                }
                if (content.schemaVersion() != null) {
                    request.setContentSchemaVersion(content.schemaVersion());
                }
                if (content.document() != null) {
                    request.setContentJson(writeDocument(content.document()));
                }
            }

            DocumentOperationResponse response = metrics.record(
                    "ApplyDocumentOperation",
                    () -> stub(accessToken)
                            .applyDocumentOperation(request.build())
            );
            return operationResult(response);
        } catch (StatusRuntimeException exception) {
            if (GrpcCoreClientSupport.isConflict(exception)) {
                return CoreDocumentOperationResult.conflict(
                        GrpcCoreClientSupport.errorMessage(exception)
                );
            }
            if (GrpcCoreClientSupport.isRejected(exception)) {
                return CoreDocumentOperationResult.rejected(
                        GrpcCoreClientSupport.errorMessage(exception)
                );
            }
            throw exception;
        } catch (IllegalArgumentException exception) {
            return CoreDocumentOperationResult.rejected(exception.getMessage());
        }
    }

    @Override
    public CollaborationMessages.DocumentOperationCatchUp listAfter(
            UUID documentId,
            String accessToken,
            long afterSequence,
            int limit
    ) {
        var response = metrics.record(
                "ListDocumentOperations",
                () -> stub(accessToken).listDocumentOperations(
                        ListDocumentOperationsRequest.newBuilder()
                                .setDocumentId(documentId.toString())
                                .setAfterSequence(afterSequence)
                                .setLimit(limit)
                                .build()
                )
        );
        return new CollaborationMessages.DocumentOperationCatchUp(
                response.getRequestedAfterSequence(),
                response.getLatestDocumentSequence(),
                response.getHasMore(),
                response.getOperationsList().stream()
                        .map(this::recoveredOperation)
                        .toList()
        );
    }

    private com.devcollab.protocol.core.v1
            .KnowledgeCoreCollaborationServiceGrpc
            .KnowledgeCoreCollaborationServiceBlockingStub stub(
            String accessToken
    ) {
        return GrpcCoreClientSupport.authenticatedStub(
                channel,
                properties.deadline(),
                accessToken
        );
    }

    private CoreDocumentOperationResult operationResult(
            DocumentOperationResponse response
    ) {
        return new CoreDocumentOperationResult(
                response.getStatus().name(),
                response.getDocumentSequence(),
                response.hasBlock() ? block(response.getBlock()) : null,
                response.getBlocksList().stream().map(this::block).toList(),
                null
        );
    }

    private CollaborationMessages.RecoveredDocumentOperation
            recoveredOperation(DocumentOperationResponse response) {
        return new CollaborationMessages.RecoveredDocumentOperation(
                UUID.fromString(response.getClientOperationId()),
                response.hasBlockId()
                        ? UUID.fromString(response.getBlockId())
                        : null,
                response.getOperationType().name(),
                response.getDocumentSequence(),
                UUID.fromString(response.getOperatorUserId()),
                response.hasBlock() ? block(response.getBlock()) : null,
                response.getBlocksList().stream().map(this::block).toList()
        );
    }

    private CoreBlockResponse block(
            com.devcollab.protocol.core.v1.DocumentBlock block
    ) {
        return new CoreBlockResponse(
                UUID.fromString(block.getId()),
                UUID.fromString(block.getDocumentId()),
                block.getType().name(),
                new CoreBlockContent(
                        block.getText(),
                        block.getContentSchemaVersion(),
                        block.hasContentJson()
                                ? readDocument(block.getContentJson())
                                : null
                ),
                block.getSortOrder(),
                block.getVersion(),
                UUID.fromString(block.getCreatedBy()),
                instant(block.getCreatedAt()),
                instant(block.getUpdatedAt())
        );
    }

    private String writeDocument(com.fasterxml.jackson.databind.JsonNode document) {
        try {
            return OBJECT_MAPPER.writeValueAsString(document);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "Block content document cannot be serialized",
                    exception
            );
        }
    }

    private com.fasterxml.jackson.databind.JsonNode readDocument(String value) {
        try {
            return OBJECT_MAPPER.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Core returned invalid Block content JSON",
                    exception
            );
        }
    }

    private Instant instant(com.google.protobuf.Timestamp timestamp) {
        return Instant.ofEpochSecond(
                timestamp.getSeconds(),
                timestamp.getNanos()
        );
    }
}
