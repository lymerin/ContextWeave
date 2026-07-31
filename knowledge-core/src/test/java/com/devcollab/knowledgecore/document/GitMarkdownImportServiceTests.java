package com.devcollab.knowledgecore.git.application;

import com.devcollab.knowledgecore.document.core.domain.DocumentType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GitMarkdownImportServiceTests {

    @Test
    void recognizesMarkdownAndBuildsDocumentMetadata() {
        assertThat(GitMarkdownImportService.isMarkdown("docs/guide.MD")).isTrue();
        assertThat(GitMarkdownImportService.isMarkdown("src/App.java")).isFalse();
        assertThat(GitMarkdownImportService.parentSegments("docs/api/auth.md"))
                .containsExactly("docs", "api");
        assertThat(GitMarkdownImportService.title(
                "docs/api/auth.md", "# 登录接口\n正文"
        )).isEqualTo("登录接口");
        assertThat(GitMarkdownImportService.title(
                "README.md", "# Project [![Build](badge.svg)](build-url)"
        )).isEqualTo("Project");
        assertThat(GitMarkdownImportService.documentType("docs/api/auth.md"))
                .isEqualTo(DocumentType.API);
    }

    @Test
    void splitsLargeMarkdownIntoSafeBlocks() {
        String content = "x".repeat(39_000);

        assertThat(GitMarkdownImportService.chunks(content))
                .hasSize(3)
                .allMatch(chunk -> chunk.length() <= 19_000);
    }
}
