package com.devcollab.knowledgecore.document.core.api;

import com.devcollab.knowledgecore.document.collaboration.domain.DocumentOperationLog;
import com.devcollab.knowledgecore.document.core.application.CreateDocumentCommand;
import com.devcollab.knowledgecore.document.core.application.DocumentApplicationService;
import com.devcollab.knowledgecore.document.core.domain.Document;
import com.devcollab.knowledgecore.security.CurrentUser;
import com.devcollab.knowledgecore.security.DelegatedRestAccess;
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
import com.devcollab.knowledgecore.document.block.application.DocumentStructureDto;
import com.devcollab.knowledgecore.document.review.api.ReviewDocumentRequest;
import com.devcollab.knowledgecore.document.version.domain.DocumentVersion;

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
    public Document create(
            @PathVariable UUID workspaceId,
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody CreateDocumentRequest request
    ) {
        return documentService.create(
                workspaceId,
                currentUser.userId(),
                new CreateDocumentCommand(
                        request.parentDocumentId(),
                        request.title(),
                        request.documentType()
                )
        );
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
    public Document get(
            @PathVariable UUID documentId,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return documentService.get(documentId, currentUser.userId());
    }

    @GetMapping("/api/v1/workspaces/{workspaceId}/documents/{documentId}/structure")
    @DelegatedRestAccess(tools = {
            "devcollab.document.get_structure",
            "devcollab.document.get_structure_batch"
    })
    public DocumentStructureDto getStructure(
            @PathVariable UUID workspaceId,
            @PathVariable UUID documentId,
            @AuthenticationPrincipal CurrentUser currentUser,
            @org.springframework.web.bind.annotation.RequestParam(required = false, defaultValue = "false") boolean includeBlockContent,
            @org.springframework.web.bind.annotation.RequestParam(required = false, defaultValue = "false") boolean includeInternalBlocks,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Integer maxBlocks,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Integer maxContentCharacters
    ) {
        return documentService.getDocumentStructure(
                workspaceId,
                documentId,
                currentUser.userId(),
                includeBlockContent,
                includeInternalBlocks,
                maxBlocks,
                maxContentCharacters
        );
    }

    @PatchMapping("/api/v1/documents/{documentId}")
    public Document update(
            @PathVariable UUID documentId,
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody UpdateDocumentRequest request
    ) {
        return documentService.update(
                documentId,
                currentUser.userId(),
                request
        );
    }

    @PatchMapping("/api/v1/documents/{documentId}/parent")
    public Document move(
            @PathVariable UUID documentId,
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestBody MoveDocumentRequest request
    ) {
        return documentService.move(
                documentId,
                currentUser.userId(),
                request
        );
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
    public Document submitReview(
            @PathVariable UUID documentId,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return documentService.submitReview(documentId, currentUser.userId());
    }

    @PostMapping("/api/v1/documents/{documentId}/approve-review")
    public Document approveReview(
            @PathVariable UUID documentId,
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody(required = false) ReviewDocumentRequest request
    ) {
        return documentService.approveReview(
                documentId,
                currentUser.userId(),
                request
        );
    }

    @PostMapping("/api/v1/documents/{documentId}/reject-review")
    public Document rejectReview(
            @PathVariable UUID documentId,
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody(required = false) ReviewDocumentRequest request
    ) {
        return documentService.rejectReview(
                documentId,
                currentUser.userId(),
                request
        );
    }

    @PostMapping("/api/v1/documents/{documentId}/deprecate")
    public Document deprecate(
            @PathVariable UUID documentId,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return documentService.deprecate(documentId, currentUser.userId());
    }

    @GetMapping("/api/v1/documents/{documentId}/versions")
    public List<DocumentVersion> versions(
            @PathVariable UUID documentId,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return documentService.listVersions(documentId, currentUser.userId());
    }

    @GetMapping("/api/v1/documents/{documentId}/versions/{versionId}")
    public DocumentVersion version(
            @PathVariable UUID documentId,
            @PathVariable UUID versionId,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return documentService.getVersion(
                documentId,
                versionId,
                currentUser.userId()
        );
    }

    @GetMapping("/api/v1/documents/{documentId}/timeline")
    public List<DocumentOperationLog> timeline(
            @PathVariable UUID documentId,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return documentService.listTimeline(documentId, currentUser.userId());
    }
}
