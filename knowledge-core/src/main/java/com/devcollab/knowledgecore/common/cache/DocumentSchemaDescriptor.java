package com.devcollab.knowledgecore.common.cache;

import com.devcollab.knowledgecore.document.core.domain.DocumentBlockType;
import com.devcollab.knowledgecore.document.core.domain.DocumentType;

import java.util.List;

public record DocumentSchemaDescriptor(
        DocumentType documentType,
        String schemaVersion,
        List<DocumentBlockType> supportedBlockTypes
) {
    public int estimatedWeight() {
        return Math.max(
                1,
                documentType.name().length() * 2
                        + schemaVersion.length() * 2
                        + supportedBlockTypes.size() * 32
        );
    }
}
