package com.devcollab.knowledgecore.document.review.application.exception;

public class InvalidDocumentReviewStatusException extends RuntimeException {

    public InvalidDocumentReviewStatusException() {
        super("当前文档状态不允许执行该评审操作");
    }
}
