package com.devcollab.knowledgecore.document.core.api;

import com.devcollab.knowledgecore.document.core.application.CreateDocumentCommand;
import com.devcollab.knowledgecore.document.core.application.DocumentApplicationService;
import com.devcollab.knowledgecore.document.core.application.MoveDocumentCommand;
import com.devcollab.knowledgecore.document.review.application.ReviewDocumentCommand;
import com.devcollab.knowledgecore.document.core.application.UpdateDocumentCommand;
import com.devcollab.knowledgecore.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import com.devcollab.knowledgecore.document.block.api.DocumentStructureResponse;
import com.devcollab.knowledgecore.document.version.api.DocumentVersionResponse;
import com.devcollab.knowledgecore.document.review.api.DocumentReviewRecordResponse;
import com.devcollab.knowledgecore.document.collaboration.api.DocumentOperationLogResponse;
import com.devcollab.knowledgecore.document.review.api.ReviewDocumentRequest;

@RestController
public class DocumentController {

    private final DocumentApplicationService documentService;

    public DocumentController(
            DocumentApplicationService documentService
    ) {
        this.documentService = documentService;
    }

    @PostMapping("/api/v1/workspaces/{workspaceId}/documents")
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentResponse create(
            @PathVariable UUID workspaceId,
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody CreateDocumentRequest request
    ) {
        return DocumentResponse.from(documentService.create(
                workspaceId,
                currentUser.userId(),
                new CreateDocumentCommand(
                        request.parentDocumentId(),
                        request.title(),
                        request.documentType()
                )
        ));
    }

    @GetMapping("/api/v1/workspaces/{workspaceId}/documents/tree")
    public List<DocumentTreeNodeResponse> tree(
            @PathVariable UUID workspaceId,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return DocumentTreeNodeResponse.from(
                documentService.listTreeSource(
                        workspaceId,
                        currentUser.userId()
                )
        );
    }

    @GetMapping("/api/v1/documents/{documentId}")
    public DocumentResponse get(
            @PathVariable UUID documentId,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return DocumentResponse.from(
                documentService.get(documentId, currentUser.userId())
        );
    }

    @GetMapping("/api/v1/workspaces/{workspaceId}/documents/{documentId}/structure")
    public DocumentStructureResponse getStructure(
            @PathVariable UUID workspaceId,
            @PathVariable UUID documentId,
            @AuthenticationPrincipal CurrentUser currentUser,
            @org.springframework.web.bind.annotation.RequestParam(required = false, defaultValue = "false") boolean includeBlockContent,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Integer maxBlocks,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Integer maxContentCharacters
    ) {
        return DocumentStructureResponse.from(
                documentService.getDocumentStructure(
                        workspaceId,
                        documentId,
                        currentUser.userId(),
                        includeBlockContent,
                        maxBlocks,
                        maxContentCharacters
                )
        );
    }

    @PatchMapping("/api/v1/documents/{documentId}")
    public DocumentResponse update(
            @PathVariable UUID documentId,
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody UpdateDocumentRequest request
    ) {
        return DocumentResponse.from(documentService.update(
                documentId,
                currentUser.userId(),
                new UpdateDocumentCommand(request.title())
        ));
    }

    @PatchMapping("/api/v1/documents/{documentId}/parent")
    public DocumentResponse move(
            @PathVariable UUID documentId,
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestBody MoveDocumentRequest request
    ) {
        return DocumentResponse.from(documentService.move(
                documentId,
                currentUser.userId(),
                new MoveDocumentCommand(request.parentDocumentId())
        ));
    }

    @DeleteMapping("/api/v1/documents/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable UUID documentId,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        documentService.delete(documentId, currentUser.userId());
    }

    @PostMapping("/api/v1/documents/{documentId}/submit-review")
    public DocumentResponse submitReview(
            @PathVariable UUID documentId,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return DocumentResponse.from(
                documentService.submitReview(documentId, currentUser.userId())
        );
    }

    @PostMapping("/api/v1/documents/{documentId}/approve-review")
    public DocumentResponse approveReview(
            @PathVariable UUID documentId,
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody(required = false) ReviewDocumentRequest request
    ) {
        return DocumentResponse.from(
                documentService.approveReview(
                        documentId,
                        currentUser.userId(),
                        reviewCommand(request)
                )
        );
    }

    @PostMapping("/api/v1/documents/{documentId}/reject-review")
    public DocumentResponse rejectReview(
            @PathVariable UUID documentId,
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody(required = false) ReviewDocumentRequest request
    ) {
        return DocumentResponse.from(
                documentService.rejectReview(
                        documentId,
                        currentUser.userId(),
                        reviewCommand(request)
                )
        );
    }

    @PostMapping("/api/v1/documents/{documentId}/deprecate")
    public DocumentResponse deprecate(
            @PathVariable UUID documentId,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return DocumentResponse.from(
                documentService.deprecate(documentId, currentUser.userId())
        );
    }

    @GetMapping("/api/v1/documents/{documentId}/versions")
    public List<DocumentVersionResponse> versions(
            @PathVariable UUID documentId,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return documentService.listVersions(documentId, currentUser.userId())
                .stream()
                .map(DocumentVersionResponse::from)
                .toList();
    }

    @GetMapping("/api/v1/documents/{documentId}/versions/{versionId}")
    public DocumentVersionResponse version(
            @PathVariable UUID documentId,
            @PathVariable UUID versionId,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return DocumentVersionResponse.from(documentService.getVersion(
                documentId,
                versionId,
                currentUser.userId()
        ));
    }

    @GetMapping("/api/v1/documents/{documentId}/review-records")
    public List<DocumentReviewRecordResponse> reviewRecords(
            @PathVariable UUID documentId,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return documentService.listReviewRecords(
                        documentId,
                        currentUser.userId()
                )
                .stream()
                .map(DocumentReviewRecordResponse::from)
                .toList();
    }

    @GetMapping("/api/v1/documents/{documentId}/timeline")
    public List<DocumentOperationLogResponse> timeline(
            @PathVariable UUID documentId,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return documentService.listTimeline(documentId, currentUser.userId())
                .stream()
                .map(DocumentOperationLogResponse::from)
                .toList();
    }

    private ReviewDocumentCommand reviewCommand(
            ReviewDocumentRequest request
    ) {
        return new ReviewDocumentCommand(
                request == null ? null : request.comment()
        );
    }
}
