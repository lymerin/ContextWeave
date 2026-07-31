package com.devcollab.knowledgecore.document.core.application.exception;

public class DocumentBlockNotFoundException extends RuntimeException {

    public DocumentBlockNotFoundException() {
        super("文档 Block 不存在");
    }
}
