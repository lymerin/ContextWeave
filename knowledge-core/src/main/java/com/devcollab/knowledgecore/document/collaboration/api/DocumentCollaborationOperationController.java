package com.devcollab.knowledgecore.document.collaboration.api;

import com.devcollab.knowledgecore.document.collaboration.application.ApplyDocumentCollaborationOperationCommand;
import com.devcollab.knowledgecore.document.collaboration.application.DocumentCollaborationOperationService;
import com.devcollab.knowledgecore.document.block.application.DocumentBlockContentCodec;
import com.devcollab.knowledgecore.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class DocumentCollaborationOperationController {

    private final DocumentCollaborationOperationService operationService;
    private final DocumentBlockContentCodec contentCodec;

    public DocumentCollaborationOperationController(
            DocumentCollaborationOperationService operationService,
            DocumentBlockContentCodec contentCodec
    ) {
        this.operationService = operationService;
        this.contentCodec = contentCodec;
    }

    @PostMapping("/api/v1/documents/{documentId}/collaboration-operations")
    public DocumentCollaborationOperationResponse apply(
            @PathVariable UUID documentId,
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody DocumentCollaborationOperationRequest request
    ) {
        return DocumentCollaborationOperationResponse.from(
                operationService.apply(
                        documentId,
                        currentUser.userId(),
                        new ApplyDocumentCollaborationOperationCommand(
                                request.clientOperationId(),
                                request.blockId(),
                                request.operationType(),
                                request.expectedVersion(),
                                request.blockType(),
                                request.targetIndex(),
                                request.content() == null
                                        ? null
                                        : request.content().text(),
                                request.content() == null
                                        ? null
                                        : request.content().schemaVersion(),
                                request.content() == null
                                        ? null
                                        : request.content().document()
                        )
                ),
                contentCodec
        );
    }

    @GetMapping("/api/v1/documents/{documentId}/collaboration-operations")
    public DocumentCollaborationOperationPageResponse listAfter(
            @PathVariable UUID documentId,
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestParam long afterSequence,
            @RequestParam(defaultValue = "100") int limit
    ) {
        return DocumentCollaborationOperationPageResponse.from(
                operationService.listAfter(
                        documentId,
                        currentUser.userId(),
                        afterSequence,
                        limit
                ),
                contentCodec
        );
    }
}
