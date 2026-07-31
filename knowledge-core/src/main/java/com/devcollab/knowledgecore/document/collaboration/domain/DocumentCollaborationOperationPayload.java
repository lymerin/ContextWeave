package com.devcollab.knowledgecore.document.collaboration.domain;

import java.util.List;
import com.devcollab.knowledgecore.document.core.domain.DocumentBlock;

// 协同编辑操作负载，携带操作涉及的主 Block 和上下文 Block 列表
// 紧凑构造器确保 blocks 不为 null 且不可变
public record DocumentCollaborationOperationPayload(
        DocumentBlock block,                // 操作的主 Block
        List<DocumentBlock> blocks          // 上下文 Block 列表（如排序场景下所有关联 Block）
) {
    public DocumentCollaborationOperationPayload {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }

    // 单 Block 操作（如新增、更新），无需上下文 Block
    public static DocumentCollaborationOperationPayload single(
            DocumentBlock block
    ) {
        return new DocumentCollaborationOperationPayload(block, List.of());
    }

    // 排序操作，携带主 Block 和排序后的全部关联 Block
    public static DocumentCollaborationOperationPayload ordered(
            DocumentBlock block,
            List<DocumentBlock> blocks
    ) {
        return new DocumentCollaborationOperationPayload(block, blocks);
    }
}
