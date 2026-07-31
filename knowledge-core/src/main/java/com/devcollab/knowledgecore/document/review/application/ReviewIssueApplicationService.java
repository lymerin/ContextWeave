package com.devcollab.knowledgecore.document.review.application;

import com.devcollab.knowledgecore.common.outbox.application.OutboxEventPublisher;
import com.devcollab.knowledgecore.common.outbox.application.OutboxEventTypes;
import com.devcollab.knowledgecore.document.core.domain.Document;
import com.devcollab.knowledgecore.document.review.application.exception.ReviewIssueNotFoundException;
import com.devcollab.knowledgecore.document.version.domain.DocumentVersion;
import com.devcollab.knowledgecore.document.review.domain.ReviewIssue;
import com.devcollab.knowledgecore.document.review.domain.ReviewIssueRepository;
import com.devcollab.knowledgecore.document.review.domain.ReviewIssueStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.devcollab.knowledgecore.document.core.application.DocumentApplicationService;

@Service
public class ReviewIssueApplicationService {

    private final ReviewIssueRepository issueRepository;
    private final DocumentApplicationService documentService;
    private final OutboxEventPublisher outboxEventPublisher;

    public ReviewIssueApplicationService(
            ReviewIssueRepository issueRepository,
            DocumentApplicationService documentService,
            OutboxEventPublisher outboxEventPublisher
    ) {
        this.issueRepository = issueRepository;
        this.documentService = documentService;
        this.outboxEventPublisher = outboxEventPublisher;
    }

    @Transactional
    public ReviewIssue create(
            UUID documentId,
            UUID versionId,
            UUID currentUserId,
            CreateReviewIssueCommand command
    ) {
        Document document = documentService.get(documentId, currentUserId);
        DocumentVersion version = documentService.getVersion(
                documentId,
                versionId,
                currentUserId
        );
        Instant now = Instant.now();
        ReviewIssue issue = new ReviewIssue(
                UUID.randomUUID(),
                version.id(),
                command.type(),
                command.severity(),
                ReviewIssueStatus.OPEN,
                command.assigneeId(),
                command.title().trim(),
                normalizeDescription(command.description()),
                currentUserId,
                now
        );
        ReviewIssue saved = issueRepository.save(issue);
        publishIssueEvent(
                OutboxEventTypes.REVIEW_ISSUE_CREATED,
                document,
                version,
                saved,
                currentUserId
        );
        return saved;
    }

    public List<ReviewIssue> list(
            UUID documentId,
            UUID versionId,
            UUID currentUserId
    ) {
        DocumentVersion version = documentService.getVersion(
                documentId,
                versionId,
                currentUserId
        );
        return issueRepository.findAllByDocumentVersionId(version.id());
    }

    @Transactional
    public ReviewIssue updateStatus(
            UUID documentId,
            UUID issueId,
            UUID currentUserId,
            UpdateReviewIssueStatusCommand command
    ) {
        Document document = documentService.get(documentId, currentUserId);
        if (command.status() == ReviewIssueStatus.OPEN) {
            throw new IllegalArgumentException(
                    "评审问题处理状态不能回退为 OPEN"
            );
        }

        ReviewIssue issue = issueRepository.findById(issueId)
                .orElseThrow(ReviewIssueNotFoundException::new);
        DocumentVersion version = documentService.getVersion(
                documentId,
                issue.documentVersionId(),
                currentUserId
        );

        ReviewIssue updated = new ReviewIssue(
                issue.id(),
                version.id(),
                issue.type(),
                issue.severity(),
                command.status(),
                issue.assigneeId(),
                issue.title(),
                issue.description(),
                issue.createdBy(),
                issue.createdAt()
        );
        ReviewIssue saved = issueRepository.save(updated);
        publishIssueEvent(
                eventTypeForStatus(command.status()),
                document,
                version,
                saved,
                currentUserId
        );
        return saved;
    }

    private String eventTypeForStatus(ReviewIssueStatus status) {
        return switch (status) {
            case RESOLVED -> OutboxEventTypes.REVIEW_ISSUE_RESOLVED;
            case ACCEPTED -> OutboxEventTypes.REVIEW_ISSUE_ACCEPTED;
            case REJECTED -> OutboxEventTypes.REVIEW_ISSUE_REJECTED;
            case OPEN -> throw new IllegalArgumentException(
                    "OPEN status is not a terminal review issue event"
            );
        };
    }

    private void publishIssueEvent(
            String eventType,
            Document document,
            DocumentVersion version,
            ReviewIssue issue,
            UUID operatorUserId
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("workspaceId", document.workspaceId());
        payload.put("documentId", document.id());
        payload.put("documentTitle", document.title());
        payload.put("versionId", version.id());
        payload.put("versionNo", version.versionNo());
        payload.put("issueId", issue.id());
        payload.put("issueType", issue.type());
        payload.put("severity", issue.severity());
        payload.put("status", issue.status());
        payload.put("assigneeId", issue.assigneeId());
        payload.put("title", issue.title());
        payload.put("description", issue.description());
        payload.put("createdBy", issue.createdBy());
        payload.put("operatorUserId", operatorUserId);
        payload.put("occurredAt", Instant.now());
        outboxEventPublisher.publish(
                "REVIEW_ISSUE",
                issue.id(),
                eventType,
                payload
        );
    }

    private String normalizeDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        return description.trim();
    }
}
