package com.devcollab.knowledgecore.document.core.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateDocumentRequest(
        @NotBlank(message = "文档标题不能为空")
        @Size(max = 200, message = "文档标题不能超过 200 个字符")
        String title
) {
}
