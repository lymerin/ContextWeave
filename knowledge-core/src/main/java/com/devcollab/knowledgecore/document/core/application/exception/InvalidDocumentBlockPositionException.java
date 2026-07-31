package com.devcollab.knowledgecore.document.core.application.exception;

public class InvalidDocumentBlockPositionException
        extends RuntimeException {

    public InvalidDocumentBlockPositionException() {
        super("Block 目标位置超出文档范围");
    }
}
