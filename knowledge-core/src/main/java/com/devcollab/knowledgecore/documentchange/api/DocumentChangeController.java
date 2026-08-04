package com.devcollab.knowledgecore.documentchange.api;

import com.devcollab.knowledgecore.documentchange.application.DocumentChangeApplicationService;
import com.devcollab.knowledgecore.documentchange.application.DocumentChangeViews.DetailView;
import com.devcollab.knowledgecore.documentchange.application.DocumentChangeViews.PageView;
import com.devcollab.knowledgecore.documentchange.domain.DocumentChangeModel.Status;
import com.devcollab.knowledgecore.documentchange.domain.DocumentChangeModel.OperationType;
import com.devcollab.knowledgecore.documentchange.domain.DocumentChangeModel.BindingAction;
import com.devcollab.knowledgecore.document.core.domain.DocumentType;
import com.devcollab.knowledgecore.document.core.domain.DocumentBlockType;
import com.devcollab.knowledgecore.document.block.application.DocumentBlockContentFormat;
import com.devcollab.knowledgecore.git.domain.CodeAnchorKind;
import com.devcollab.knowledgecore.git.domain.BindingRole;
import com.devcollab.knowledgecore.security.CurrentUser;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.List;

import static com.devcollab.knowledgecore.documentchange.application.DocumentChangeApplicationService.*;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/document-change-requests")
public class DocumentChangeController {

    private final DocumentChangeApplicationService service;

    public DocumentChangeController(DocumentChangeApplicationService service) {
        this.service = service;
    }

    @GetMapping("/pending-count")
    public PendingCountResponse pendingCount(
            @PathVariable UUID workspaceId,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return new PendingCountResponse(
                service.pendingCount(workspaceId, currentUser.userId())
        );
    }

    @PostMapping
    public ResponseEntity<CreateDocumentChangeResponse> create(
            @PathVariable UUID workspaceId,
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody CreateDocumentChangeRequest request
    ) {
        CreateResult result = service.create(
                workspaceId,
                currentUser.userId(),
                request.toCommand()
        );
        return ResponseEntity
                .status(result.idempotentReplay()
                        ? HttpStatus.OK : HttpStatus.CREATED)
                .body(new CreateDocumentChangeResponse(
                        result.changeRequestId(),
                        result.status(),
                        result.createdAt(),
                        result.idempotentReplay()
                ));
    }

    @GetMapping
    public PageView list(
            @PathVariable UUID workspaceId,
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestParam(defaultValue = "PENDING") Status status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort
    ) {
        return service.list(
                workspaceId,
                currentUser.userId(),
                status,
                page,
                size,
                sort
        );
    }

    @GetMapping("/{requestId}")
    public DetailView detail(
            @PathVariable UUID workspaceId,
            @PathVariable UUID requestId,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return service.detail(workspaceId, requestId, currentUser.userId());
    }

    @PostMapping("/{requestId}/apply")
    public ResponseEntity<DetailView> apply(
            @PathVariable UUID workspaceId,
            @PathVariable UUID requestId,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return ResponseEntity
                .ok(service.apply(
                        workspaceId,
                        requestId,
                        currentUser.userId()
                ).detail());
    }

    @PostMapping("/{requestId}/reject")
    public DetailView reject(
            @PathVariable UUID workspaceId,
            @PathVariable UUID requestId,
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody RejectDocumentChangeRequest request
    ) {
        return service.reject(
                workspaceId,
                requestId,
                currentUser.userId(),
                request.reason()
        );
    }

    public record PendingCountResponse(long count) {
    }

    public record CreateDocumentChangeRequest(
            @NotBlank @Size(max = 100) String clientRequestId,
            @NotBlank @Size(max = 300) String summary,
            @NotBlank @Size(max = 10_000) String rationale,
            @NotNull @Size(max = 50) List<@Valid OperationRequest> operations,
            @Size(max = 50) List<@Valid BindingProposalRequest> bindingProposals,
            @Size(max = 50) List<@Valid EvidenceRequest> evidence
    ) {
        CreateCommand toCommand() {
            return new CreateCommand(
                    clientRequestId,
                    summary,
                    rationale,
                    operations.stream().map(OperationRequest::toCommand).toList(),
                    bindingProposals == null ? List.of()
                            : bindingProposals.stream()
                            .map(BindingProposalRequest::toCommand)
                            .toList(),
                    evidence == null ? List.of()
                            : evidence.stream()
                            .map(EvidenceRequest::toCommand)
                            .toList()
            );
        }
    }

    public record OperationRequest(
            @NotBlank @Size(max = 100) String clientOperationId,
            int sequenceNumber,
            @NotNull OperationType operationType,
            UUID documentId,
            String createdDocumentClientOperationId,
            UUID blockId,
            Long baseBlockVersion,
            @Size(max = 200) String proposedDocumentTitle,
            DocumentType proposedDocumentType,
            UUID proposedParentDocumentId,
            DocumentBlockType proposedBlockType,
            @Size(max = 20_000) String proposedPlainText,
            DocumentBlockContentFormat proposedContentFormat,
            @Valid BlockContentRequest proposedContent
    ) {
        CreateOperationCommand toCommand() {
            return new CreateOperationCommand(
                    clientOperationId,
                    sequenceNumber,
                    operationType,
                    documentId,
                    createdDocumentClientOperationId,
                    blockId,
                    baseBlockVersion,
                    proposedDocumentTitle,
                    proposedDocumentType,
                    proposedParentDocumentId,
                    proposedBlockType,
                    proposedPlainText,
                    proposedContentFormat,
                    proposedContent == null ? null
                            : proposedContent.schemaVersion(),
                    proposedContent == null ? null : proposedContent.document()
            );
        }
    }

    public record BlockContentRequest(
            Integer schemaVersion,
            JsonNode document
    ) {
    }

    public record BindingProposalRequest(
            @NotBlank @Size(max = 100) String clientBindingProposalId,
            int sequenceNumber,
            @NotNull BindingAction action,
            @NotNull UUID repositoryId,
            @Size(max = 255) String revision,
            @NotBlank @Size(max = 1000) String filePath,
            CodeAnchorKind anchorKind,
            @Size(max = 1000) String symbolKey,
            Integer startLine,
            Integer endLine,
            UUID documentId,
            String createdDocumentClientOperationId,
            UUID blockId,
            String createdBlockClientOperationId,
            UUID bindingId,
            @Size(max = 100) String candidateId,
            @Size(max = 100) String documentAnchorCandidateId,
            @NotBlank @Size(max = 1000) String reason,
            Double confidence,
            BindingRole bindingRole,
            Integer bindingOrdinal,
            @Size(max = 1500) String boundSignature
    ) {
        CreateBindingProposalCommand toCommand() {
            return new CreateBindingProposalCommand(
                    clientBindingProposalId,
                    sequenceNumber,
                    action,
                    repositoryId,
                    revision,
                    filePath,
                    anchorKind,
                    symbolKey,
                    startLine,
                    endLine,
                    documentId,
                    createdDocumentClientOperationId,
                    blockId,
                    createdBlockClientOperationId,
                    bindingId,
                    candidateId,
                    documentAnchorCandidateId,
                    reason,
                    confidence,
                    bindingRole == null ? BindingRole.PRIMARY : bindingRole,
                    bindingOrdinal == null ? 1 : bindingOrdinal,
                    boundSignature
            );
        }
    }

    public record EvidenceRequest(
            String clientOperationId,
            @NotNull UUID repositoryId,
            @NotBlank @Size(max = 1000) String filePath,
            Integer startLine,
            Integer endLine,
            @NotBlank @Size(max = 1000) String description
    ) {
        CreateEvidenceCommand toCommand() {
            return new CreateEvidenceCommand(
                    clientOperationId,
                    repositoryId,
                    filePath,
                    startLine,
                    endLine,
                    description
            );
        }
    }

    public record CreateDocumentChangeResponse(
            UUID changeRequestId,
            Status status,
            java.time.Instant createdAt,
            boolean idempotentReplay
    ) {
    }

    public record RejectDocumentChangeRequest(
            @NotBlank @Size(max = 2_000) String reason
    ) {
    }

    /**
     * Return drift fixes that affected the given document, newest first.
     *
     * <p>Used by the frontend inspector panel to show a before/after
     * history of automatic drift corrections for a document.
     */
    @GetMapping("/api/v1/workspaces/{workspaceId}/documents/{documentId}/drift-history")
    public List<DetailView> driftHistory(
            @PathVariable UUID workspaceId,
            @PathVariable UUID documentId,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return service.driftHistory(workspaceId, documentId, currentUser.userId());
    }
}
