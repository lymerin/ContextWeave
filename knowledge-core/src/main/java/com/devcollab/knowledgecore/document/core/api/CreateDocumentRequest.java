package com.devcollab.knowledgecore.document.core.api;

import com.devcollab.knowledgecore.document.core.domain.DocumentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateDocumentRequest(
        UUID parentDocumentId,

        @NotBlank(message = "文档标题不能为空")
        @Size(max = 200, message = "文档标题不能超过 200 个字符")
        String title,

        DocumentType documentType
) {
}
