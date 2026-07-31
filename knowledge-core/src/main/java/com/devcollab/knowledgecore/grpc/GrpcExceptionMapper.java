package com.devcollab.knowledgecore.grpc;

import com.devcollab.knowledgecore.common.redis.RateLimitExceededException;
import com.devcollab.knowledgecore.document.collaboration.application.exception.CollaborationOperationIdReusedException;
import com.devcollab.knowledgecore.document.core.application.exception.DocumentBlockNotFoundException;
import com.devcollab.knowledgecore.document.core.application.exception.DocumentBlockVersionConflictException;
import com.devcollab.knowledgecore.document.core.application.exception.DocumentNotFoundException;
import com.devcollab.knowledgecore.document.core.application.exception.InvalidDocumentBlockPositionException;
import com.devcollab.knowledgecore.document.review.application.exception.InvalidDocumentReviewStatusException;
import com.devcollab.knowledgecore.workspace.application.exception.WorkspaceAccessDeniedException;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.springframework.stereotype.Component;

@Component
public class GrpcExceptionMapper {

    public static final Metadata.Key<String> ERROR_CODE = Metadata.Key.of(
            "devcollab-error-code",
            Metadata.ASCII_STRING_MARSHALLER
    );

    public StatusRuntimeException map(RuntimeException exception) {
        if (exception instanceof StatusRuntimeException statusException) {
            return statusException;
        }

        Mapping mapping = switch (exception) {
            case WorkspaceAccessDeniedException ignored -> new Mapping(
                    Status.PERMISSION_DENIED,
                    "WORKSPACE_ACCESS_DENIED"
            );
            case DocumentNotFoundException ignored -> new Mapping(
                    Status.NOT_FOUND,
                    "DOCUMENT_NOT_FOUND"
            );
            case DocumentBlockNotFoundException ignored -> new Mapping(
                    Status.NOT_FOUND,
                    "DOCUMENT_BLOCK_NOT_FOUND"
            );
            case DocumentBlockVersionConflictException ignored -> new Mapping(
                    Status.ABORTED,
                    "DOCUMENT_BLOCK_VERSION_CONFLICT"
            );
            case CollaborationOperationIdReusedException ignored -> new Mapping(
                    Status.ALREADY_EXISTS,
                    "DOCUMENT_OPERATION_ID_REUSED"
            );
            case InvalidDocumentReviewStatusException ignored -> new Mapping(
                    Status.FAILED_PRECONDITION,
                    "DOCUMENT_REVIEW_STATUS_INVALID"
            );
            case InvalidDocumentBlockPositionException ignored -> new Mapping(
                    Status.INVALID_ARGUMENT,
                    "DOCUMENT_BLOCK_POSITION_INVALID"
            );
            case RateLimitExceededException ignored -> new Mapping(
                    Status.RESOURCE_EXHAUSTED,
                    "RATE_LIMIT_EXCEEDED"
            );
            case IllegalArgumentException ignored -> new Mapping(
                    Status.INVALID_ARGUMENT,
                    "INVALID_ARGUMENT"
            );
            default -> new Mapping(Status.INTERNAL, "INTERNAL_ERROR");
        };

        Metadata trailers = new Metadata();
        trailers.put(ERROR_CODE, mapping.errorCode());
        String description = mapping.status().getCode() == Status.Code.INTERNAL
                ? "Internal Core error"
                : exception.getMessage();
        return mapping.status()
                .withDescription(description)
                .withCause(exception)
                .asRuntimeException(trailers);
    }

    private record Mapping(Status status, String errorCode) {
    }
}
