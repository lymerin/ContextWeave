package com.devcollab.knowledgecore.document.collaboration.application.exception;

public class CollaborationOperationIdReusedException extends RuntimeException {

    public CollaborationOperationIdReusedException() {
        super("clientOperationId 已被另一项请求使用");
    }
}
