package com.devcollab.knowledgecore.document.core.application;

import com.devcollab.knowledgecore.document.core.domain.DocumentBlock;

public final class DocumentBlockVisibility {

    private static final String AGENT_PLACEHOLDER_MARKER =
            "\u23f3 \u672c\u6761\u76ee\u7531\u7cfb\u7edf\u81ea\u52a8\u751f\u6210\u4e2d"
                    + "\uff08\u6279\u6b21\u586b\u5145\u540e\u81ea\u52a8\u66ff\u6362\uff09";

    private DocumentBlockVisibility() {
    }

    public static boolean isUserVisible(DocumentBlock block) {
        return block == null || !isAgentPlaceholderText(block.text());
    }

    public static boolean isAgentPlaceholderText(String text) {
        return text != null && text.contains(AGENT_PLACEHOLDER_MARKER);
    }
}
