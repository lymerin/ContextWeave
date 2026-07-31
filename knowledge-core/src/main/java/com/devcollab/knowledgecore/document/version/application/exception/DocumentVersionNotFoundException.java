package com.devcollab.knowledgecore.document.version.application.exception;

public class DocumentVersionNotFoundException extends RuntimeException {

    public DocumentVersionNotFoundException() {
        super("文档版本不存在");
    }
}
