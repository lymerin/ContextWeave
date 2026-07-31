package com.devcollab.knowledgecore.document.core.api;

import com.devcollab.knowledgecore.document.core.application.CreateDocumentBlockCommand;
import com.devcollab.knowledgecore.document.core.application.DocumentBlockApplicationService;
import com.devcollab.knowledgecore.document.core.application.MoveDocumentBlockCommand;
import com.devcollab.knowledgecore.document.core.application.UpdateDocumentBlockCommand;
import com.devcollab.knowledgecore.document.block.application.DocumentBlockContentCodec;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class DocumentBlockController {

    private final DocumentBlockApplicationService blockService;
    private final DocumentBlockContentCodec contentCodec;

    public DocumentBlockController(
            DocumentBlockApplicationService blockService,
            DocumentBlockContentCodec contentCodec
    ) {
        this.blockService = blockService;
        this.contentCodec = contentCodec;
    }

    @PostMapping("/api/v1/documents/{documentId}/blocks")
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentBlockResponse create(
            @PathVariable UUID documentId,
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody CreateDocumentBlockRequest request
    ) {
        return DocumentBlockResponse.from(blockService.create(
                documentId,
                currentUser.userId(),
                new CreateDocumentBlockCommand(
                        request.type(),
                        request.content().text(),
                        request.content().schemaVersion(),
                        request.content().document()
                )
        ), contentCodec);
    }

    @GetMapping("/api/v1/documents/{documentId}/blocks")
    public List<DocumentBlockResponse> list(
            @PathVariable UUID documentId,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return blockService.list(documentId, currentUser.userId())
                .stream()
                .map(block -> DocumentBlockResponse.from(block, contentCodec))
                .toList();
    }

    @PatchMapping("/api/v1/documents/{documentId}/blocks/{blockId}")
    public DocumentBlockResponse updateContent(
            @PathVariable UUID documentId,
            @PathVariable UUID blockId,
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody UpdateDocumentBlockRequest request
    ) {
        return DocumentBlockResponse.from(blockService.updateContent(
                documentId,
                blockId,
                currentUser.userId(),
                new UpdateDocumentBlockCommand(
                        request.content().text(),
                        request.content().schemaVersion(),
                        request.content().document(),
                        request.expectedVersion()
                )
        ), contentCodec);
    }

    @DeleteMapping("/api/v1/documents/{documentId}/blocks/{blockId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable UUID documentId,
            @PathVariable UUID blockId,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        blockService.delete(documentId, blockId, currentUser.userId());
    }

    @PatchMapping(
            "/api/v1/documents/{documentId}/blocks/{blockId}/position"
    )
    public List<DocumentBlockResponse> move(
            @PathVariable UUID documentId,
            @PathVariable UUID blockId,
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody MoveDocumentBlockRequest request
    ) {
        return blockService.move(
                        documentId,
                        blockId,
                        currentUser.userId(),
                        new MoveDocumentBlockCommand(request.targetIndex())
                )
                .stream()
                .map(block -> DocumentBlockResponse.from(block, contentCodec))
                .toList();
    }
}
