package com.devcollab.knowledgecore.document.review.api;

import com.devcollab.knowledgecore.document.review.application.CreateReviewIssueCommand;
import com.devcollab.knowledgecore.document.review.application.ReviewIssueApplicationService;
import com.devcollab.knowledgecore.document.review.application.UpdateReviewIssueStatusCommand;
import com.devcollab.knowledgecore.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class ReviewIssueController {

    private final ReviewIssueApplicationService issueService;

    public ReviewIssueController(ReviewIssueApplicationService issueService) {
        this.issueService = issueService;
    }

    @PostMapping(
            "/api/v1/documents/{documentId}/versions/{versionId}/review-issues"
    )
    @ResponseStatus(HttpStatus.CREATED)
    public ReviewIssueResponse create(
            @PathVariable UUID documentId,
            @PathVariable UUID versionId,
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody CreateReviewIssueRequest request
    ) {
        return ReviewIssueResponse.from(issueService.create(
                documentId,
                versionId,
                currentUser.userId(),
                new CreateReviewIssueCommand(
                        request.type(),
                        request.severity(),
                        request.assigneeId(),
                        request.title(),
                        request.description()
                )
        ));
    }

    @GetMapping(
            "/api/v1/documents/{documentId}/versions/{versionId}/review-issues"
    )
    public List<ReviewIssueResponse> list(
            @PathVariable UUID documentId,
            @PathVariable UUID versionId,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return issueService.list(
                        documentId,
                        versionId,
                        currentUser.userId()
                )
                .stream()
                .map(ReviewIssueResponse::from)
                .toList();
    }

    @PatchMapping("/api/v1/documents/{documentId}/review-issues/{issueId}")
    public ReviewIssueResponse updateStatus(
            @PathVariable UUID documentId,
            @PathVariable UUID issueId,
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody UpdateReviewIssueStatusRequest request
    ) {
        return ReviewIssueResponse.from(issueService.updateStatus(
                documentId,
                issueId,
                currentUser.userId(),
                new UpdateReviewIssueStatusCommand(request.status())
        ));
    }
}
