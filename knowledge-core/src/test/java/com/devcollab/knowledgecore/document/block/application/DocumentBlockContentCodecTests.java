package com.devcollab.knowledgecore.document.block.application;

import com.devcollab.knowledgecore.document.core.domain.DocumentBlock;
import com.devcollab.knowledgecore.document.core.domain.DocumentBlockType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentBlockContentCodecTests {

    private ObjectMapper objectMapper;
    private DocumentBlockContentCodec codec;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        codec = new DocumentBlockContentCodec(
                objectMapper,
                new MarkdownToTiptapConverter(objectMapper)
        );
    }

    @Test
    void markdownBecomesStructuredTiptapContent() {
        String markdown = """
                ## DocumentType

                `DocumentType` 解决文档分类问题，并提供：

                - **API**：接口文档
                - `ADR`：架构决策

                ```java
                enum DocumentType { API, ADR }
                ```
                """;

        var normalized = codec.normalize(
                DocumentBlockType.PARAGRAPH,
                markdown,
                null,
                null,
                DocumentBlockContentFormat.MARKDOWN
        );

        JsonNode document = normalized.document();
        assertThat(document.path("content").get(0).path("type").asText())
                .isEqualTo("heading");
        assertThat(document.toString()).contains("\"type\":\"bulletList\"");
        assertThat(document.toString()).contains("\"type\":\"codeBlock\"");
        assertThat(document.toString()).contains("\"type\":\"code\"");
        assertThat(document.toString()).contains("\"type\":\"bold\"");
        assertThat(normalized.text()).doesNotContain("##", "```");
    }

    @Test
    void plainTextDoesNotBecomeMarkdown() {
        String text = "普通说明包含 # 标识和 - 符号，但它不是 Markdown。";

        var normalized = codec.normalize(
                DocumentBlockType.PARAGRAPH,
                text,
                null,
                null,
                DocumentBlockContentFormat.PLAIN_TEXT
        );

        assertThat(normalized.document().path("content").get(0).path("type").asText())
                .isEqualTo("paragraph");
        assertThat(normalized.text()).isEqualTo(text);
    }

    @Test
    void tiptapJsonIsPreserved() throws Exception {
        JsonNode structured = objectMapper.readTree("""
                {
                  "type":"doc",
                  "content":[
                    {
                      "type":"paragraph",
                      "content":[
                        {"type":"text","text":"DocumentType","marks":[{"type":"code"}]}
                      ]
                    }
                  ]
                }
                """);

        var normalized = codec.normalize(
                DocumentBlockType.PARAGRAPH,
                null,
                1,
                structured,
                DocumentBlockContentFormat.TIPTAP_JSON
        );

        assertThat(normalized.document()).isEqualTo(structured);
    }

    @Test
    void legacyAgentMarkdownUsesConservativeReadOnlyCompatibility() throws Exception {
        String markdown = """
                ## 模块职责

                - 读取代码上下文
                - 生成待评审文档
                """;
        String legacyJson = objectMapper.writeValueAsString(
                objectMapper.createObjectNode()
                        .put("type", "doc")
                        .set("content", objectMapper.createArrayNode()
                                .add(objectMapper.createObjectNode()
                                        .put("type", "paragraph")
                                        .set("content", objectMapper.createArrayNode()
                                                .add(objectMapper.createObjectNode()
                                                        .put("type", "text")
                                                        .put("text", markdown)))))
        );
        Instant now = Instant.now();
        DocumentBlock block = new DocumentBlock(
                UUID.randomUUID(), UUID.randomUUID(),
                DocumentBlockType.PARAGRAPH, markdown, 1, legacyJson,
                0, 1, UUID.randomUUID(), now, now
        );

        JsonNode compatible = codec.document(block);

        assertThat(compatible.path("content").get(0).path("type").asText())
                .isEqualTo("heading");
        assertThat(compatible.toString()).contains("\"type\":\"bulletList\"");
    }
}
