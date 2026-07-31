package com.devcollab.knowledgecore.document.core.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record MoveDocumentBlockRequest(
        @NotNull(message = "Block 目标位置不能为空")
        @Min(value = 0, message = "Block 目标位置不能小于 0")
        Integer targetIndex
) {
}
