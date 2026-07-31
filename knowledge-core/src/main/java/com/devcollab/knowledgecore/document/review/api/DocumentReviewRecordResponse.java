package com.devcollab.knowledgecore.document.review.api;

import com.devcollab.knowledgecore.document.review.domain.DocumentReviewAction;
import com.devcollab.knowledgecore.document.review.domain.DocumentReviewRecord;

import java.time.Instant;
import java.util.UUID;

public record DocumentReviewRecordResponse(
        UUID id,
        UUID documentId,
        DocumentReviewAction action,
        String comment,
        UUID operatorUserId,
        Instant createdAt
) {
    public static DocumentReviewRecordResponse from(
            DocumentReviewRecord record
    ) {
        return new DocumentReviewRecordResponse(
                record.id(),
                record.documentId(),
                record.action(),
                record.comment(),
                record.operatorUserId(),
                record.createdAt()
        );
    }
}
