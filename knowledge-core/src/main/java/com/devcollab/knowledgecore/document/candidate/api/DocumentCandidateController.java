package com.devcollab.knowledgecore.document.candidate.api;

import com.devcollab.knowledgecore.document.candidate.application.DocumentCandidateApplicationService;
import com.devcollab.knowledgecore.security.CurrentUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class DocumentCandidateController {

    private final DocumentCandidateApplicationService service;

    public DocumentCandidateController(DocumentCandidateApplicationService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/workspaces/{workspaceId}/document-candidates")
    public DocumentCandidateResponse findCandidates(
            @PathVariable UUID workspaceId,
            @RequestParam(required = false) UUID repositoryId,
            @RequestParam(required = false) String filePath,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) Integer limit,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return DocumentCandidateResponse.from(service.findCandidates(
                workspaceId, repositoryId, filePath, query, limit, currentUser.userId()
        ));
    }
}
