package com.devcollab.knowledgecore.document.core.application.exception;

public class InvalidDocumentParentException extends RuntimeException {

    public InvalidDocumentParentException() {
        super("父文档不属于当前工作空间");
    }
}
