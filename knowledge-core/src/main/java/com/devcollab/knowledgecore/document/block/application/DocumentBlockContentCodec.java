package com.devcollab.knowledgecore.document.block.application;

import com.devcollab.knowledgecore.document.core.domain.DocumentBlock;
import com.devcollab.knowledgecore.document.core.domain.DocumentBlockType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class DocumentBlockContentCodec {

    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final int MAX_TEXT_LENGTH = 20_000;
    public static final int MAX_JSON_BYTES = 64 * 1024;
    public static final int MAX_NODE_COUNT = 512;
    public static final int MAX_DEPTH = 8;

    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "type", "content", "text", "attrs", "marks"
    );
    private static final Set<String> ALLOWED_NODE_TYPES = Set.of(
            "doc", "paragraph", "heading", "codeBlock", "taskList",
            "taskItem", "bulletList", "orderedList", "listItem",
            "blockquote", "horizontalRule", "text", "hardBreak"
    );
    private static final Set<String> ALLOWED_MARK_TYPES = Set.of(
            "bold", "italic", "code"
    );
    private static final Pattern MARKDOWN_HEADING = Pattern.compile(
            "(?m)^#{2,3}\\s+\\S+"
    );
    private static final Pattern MARKDOWN_LIST_ITEM = Pattern.compile(
            "(?m)^(?:\\s*[-*+]\\s+|\\s*\\d+[.)]\\s+)\\S+"
    );
    private static final Pattern MARKDOWN_FENCE = Pattern.compile(
            "(?m)^```[^\\r\\n]*$"
    );

    private final ObjectMapper objectMapper;
    private final MarkdownToTiptapConverter markdownConverter;

    public DocumentBlockContentCodec(
            ObjectMapper objectMapper,
            MarkdownToTiptapConverter markdownConverter
    ) {
        this.objectMapper = objectMapper;
        this.markdownConverter = markdownConverter;
    }

    public NormalizedContent normalize(
            DocumentBlockType blockType,
            String legacyText,
            Integer schemaVersion,
            JsonNode document
    ) {
        DocumentBlockContentFormat inferred = document == null || document.isNull()
                ? DocumentBlockContentFormat.PLAIN_TEXT
                : DocumentBlockContentFormat.TIPTAP_JSON;
        return normalize(blockType, legacyText, schemaVersion, document, inferred);
    }

    public NormalizedContent normalize(
            DocumentBlockType blockType,
            String legacyText,
            Integer schemaVersion,
            JsonNode document,
            DocumentBlockContentFormat contentFormat
    ) {
        DocumentBlockContentFormat format = contentFormat == null
                ? document == null || document.isNull()
                ? DocumentBlockContentFormat.PLAIN_TEXT
                : DocumentBlockContentFormat.TIPTAP_JSON
                : contentFormat;
        if (format == DocumentBlockContentFormat.MARKDOWN) {
            if (blockType != DocumentBlockType.PARAGRAPH) {
                throw new IllegalArgumentException(
                        "Markdown content must use a PARAGRAPH Block"
                );
            }
            if (document != null && !document.isNull()) {
                throw new IllegalArgumentException(
                        "Markdown content cannot also provide a Tiptap document"
                );
            }
            String markdown = normalizeText(legacyText);
            return normalizeStructured(
                    blockType,
                    schemaVersion,
                    markdownConverter.convert(markdown)
            );
        }
        if (format == DocumentBlockContentFormat.PLAIN_TEXT
                && document != null && !document.isNull()) {
            throw new IllegalArgumentException(
                    "Plain text content cannot also provide a Tiptap document"
            );
        }
        if (format == DocumentBlockContentFormat.TIPTAP_JSON
                && (document == null || document.isNull())) {
            throw new IllegalArgumentException(
                    "Tiptap JSON content requires a document"
            );
        }
        if (document == null || document.isNull()) {
            String text = normalizeText(legacyText);
            JsonNode synthesized = textDocument(blockType, text);
            return new NormalizedContent(
                    text,
                    CURRENT_SCHEMA_VERSION,
                    write(synthesized),
                    synthesized
            );
        }
        return normalizeStructured(blockType, schemaVersion, document);
    }

    private NormalizedContent normalizeStructured(
            DocumentBlockType blockType,
            Integer schemaVersion,
            JsonNode document
    ) {
        int requestedVersion = schemaVersion == null
                ? CURRENT_SCHEMA_VERSION
                : schemaVersion;
        if (requestedVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported Block content schema version: " + requestedVersion
            );
        }
        String encoded = write(document);
        if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
            throw new IllegalArgumentException(
                    "Block structured content exceeds 65536 bytes"
            );
        }
        Counter counter = new Counter();
        validateNode(document, 1, counter, true);
        validateBusinessShape(blockType, document);
        String text = normalizeText(extractDocumentText(document));
        return new NormalizedContent(
                text,
                requestedVersion,
                encoded,
                document.deepCopy()
        );
    }

    public JsonNode document(DocumentBlock block) {
        if (block.contentJson() == null || block.contentJson().isBlank()) {
            return textDocument(block.type(), block.text());
        }
        try {
            JsonNode stored = objectMapper.readTree(block.contentJson());
            if (isLegacyParagraphShape(block.type(), stored)) {
                return textDocument(block.type(), block.text());
            }
            if (isConservativeLegacyAgentMarkdown(block, stored)) {
                return markdownConverter.convert(block.text());
            }
            return stored;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Stored Block structured content is invalid",
                    exception
            );
        }
    }

    private boolean isConservativeLegacyAgentMarkdown(
            DocumentBlock block,
            JsonNode document
    ) {
        if (block.type() != DocumentBlockType.PARAGRAPH
                || !isPlainParagraphDocument(document)) {
            return false;
        }
        String text = block.text();
        if (text == null || !MARKDOWN_HEADING.matcher(text).find()) {
            return false;
        }
        long listItems = MARKDOWN_LIST_ITEM.matcher(text).results().count();
        long headings = MARKDOWN_HEADING.matcher(text).results().count();
        return listItems >= 2
                || headings >= 2
                || MARKDOWN_FENCE.matcher(text).results().count() >= 2;
    }

    private boolean isPlainParagraphDocument(JsonNode document) {
        if (!"doc".equals(document.path("type").asText())) {
            return false;
        }
        JsonNode blocks = document.get("content");
        if (blocks == null || !blocks.isArray() || blocks.isEmpty()) {
            return false;
        }
        for (JsonNode block : blocks) {
            if (!"paragraph".equals(block.path("type").asText())) {
                return false;
            }
            JsonNode content = block.get("content");
            if (content == null) {
                continue;
            }
            if (!content.isArray()) {
                return false;
            }
            for (JsonNode inline : content) {
                if (!Set.of("text", "hardBreak")
                        .contains(inline.path("type").asText())
                        || inline.has("marks")) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean isLegacyParagraphShape(
            DocumentBlockType blockType,
            JsonNode document
    ) {
        if (blockType == DocumentBlockType.PARAGRAPH
                || !"doc".equals(document.path("type").asText())) {
            return false;
        }
        JsonNode content = document.get("content");
        if (content == null || !content.isArray() || content.isEmpty()) {
            return false;
        }
        for (JsonNode child : content) {
            if (!"paragraph".equals(child.path("type").asText())) {
                return false;
            }
        }
        return true;
    }

    public int schemaVersion(DocumentBlock block) {
        return block.contentSchemaVersion() > 0
                ? block.contentSchemaVersion()
                : CURRENT_SCHEMA_VERSION;
    }

    private void validateNode(
            JsonNode node,
            int depth,
            Counter counter,
            boolean root
    ) {
        if (!node.isObject()) {
            throw new IllegalArgumentException("Every Block content node must be an object");
        }
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("Block content nesting exceeds 8 levels");
        }
        counter.value++;
        if (counter.value > MAX_NODE_COUNT) {
            throw new IllegalArgumentException("Block content exceeds 512 nodes");
        }
        Iterator<String> fieldNames = node.fieldNames();
        while (fieldNames.hasNext()) {
            String field = fieldNames.next();
            if (!ALLOWED_FIELDS.contains(field)) {
                throw new IllegalArgumentException(
                        "Unsupported Block content field: " + field
                );
            }
        }
        String type = requiredText(node, "type");
        if (!ALLOWED_NODE_TYPES.contains(type)) {
            throw new IllegalArgumentException(
                    "Unsupported Block content node type: " + type
            );
        }
        if (root && !"doc".equals(type)) {
            throw new IllegalArgumentException("Block content root type must be doc");
        }
        if (!root && "doc".equals(type)) {
            throw new IllegalArgumentException("Nested doc nodes are not allowed");
        }
        validateAttributes(node, type);
        JsonNode content = node.get("content");
        if ("text".equals(type)) {
            if (content != null) {
                throw new IllegalArgumentException("Text nodes cannot contain child nodes");
            }
            requiredText(node, "text");
            validateMarks(node);
            return;
        }
        if (node.has("marks")) {
            throw new IllegalArgumentException(
                    "Only text nodes may contain marks"
            );
        }
        if (node.has("text")) {
            throw new IllegalArgumentException(
                    "Only text nodes may contain the text field"
            );
        }
        if (Set.of("hardBreak", "horizontalRule").contains(type)) {
            if (content != null) {
                throw new IllegalArgumentException(
                        type + " cannot contain child nodes"
                );
            }
            return;
        }
        if (content == null && Set.of("paragraph", "heading", "codeBlock")
                .contains(type)) {
            return;
        }
        if (content == null || !content.isArray()) {
            throw new IllegalArgumentException(type + " content must be an array");
        }
        for (JsonNode child : content) {
            String childType = child.path("type").asText();
            if (root && !Set.of(
                    "paragraph", "heading", "codeBlock", "taskList",
                    "bulletList", "orderedList", "blockquote", "horizontalRule"
            ).contains(childType)) {
                throw new IllegalArgumentException(
                        "doc contains an unsupported top-level node"
                );
            }
            if (Set.of("paragraph", "heading").contains(type)
                    && !Set.of("text", "hardBreak").contains(childType)) {
                throw new IllegalArgumentException(
                        type + " may contain text or hardBreak nodes only"
                );
            }
            if ("codeBlock".equals(type) && !"text".equals(childType)) {
                throw new IllegalArgumentException(
                        "codeBlock may contain text nodes only"
                );
            }
            if ("taskList".equals(type) && !"taskItem".equals(childType)) {
                throw new IllegalArgumentException(
                        "taskList may contain taskItem nodes only"
                );
            }
            if (Set.of("bulletList", "orderedList").contains(type)
                    && !"listItem".equals(childType)) {
                throw new IllegalArgumentException(
                        type + " may contain listItem nodes only"
                );
            }
            if ("listItem".equals(type)
                    && !Set.of("paragraph", "bulletList", "orderedList")
                    .contains(childType)) {
                throw new IllegalArgumentException(
                        "listItem may contain paragraphs or nested lists only"
                );
            }
            if ("blockquote".equals(type)
                    && !Set.of(
                    "paragraph", "heading", "codeBlock",
                    "bulletList", "orderedList", "blockquote"
            ).contains(childType)) {
                throw new IllegalArgumentException(
                        "blockquote contains an unsupported block node"
                );
            }
            if ("taskItem".equals(type) && !"paragraph".equals(childType)) {
                throw new IllegalArgumentException(
                        "taskItem may contain one paragraph node only"
                );
            }
            validateNode(child, depth + 1, counter, false);
        }
    }

    private void validateBusinessShape(
            DocumentBlockType blockType,
            JsonNode document
    ) {
        JsonNode content = document.get("content");
        if (content == null || !content.isArray() || content.isEmpty()) {
            throw new IllegalArgumentException("Block document must contain content");
        }
        String requiredType = switch (blockType) {
            case PARAGRAPH -> null;
            case HEADING -> "heading";
            case CODE -> "codeBlock";
            case TODO -> "taskList";
        };
        if (requiredType != null) {
            for (JsonNode child : content) {
                if (!requiredType.equals(child.path("type").asText())) {
                    throw new IllegalArgumentException(
                            blockType + " Block must contain " + requiredType + " nodes"
                    );
                }
            }
        }
        if (blockType != DocumentBlockType.PARAGRAPH && content.size() != 1) {
            throw new IllegalArgumentException(
                    blockType + " Block must contain exactly one top-level node"
            );
        }
        if (blockType == DocumentBlockType.TODO) {
            JsonNode items = content.get(0).get("content");
            if (items == null || !items.isArray() || items.isEmpty()) {
                throw new IllegalArgumentException(
                        "TODO Block must contain at least one taskItem"
                );
            }
            for (JsonNode item : items) {
                JsonNode itemContent = item.get("content");
                if (itemContent == null || !itemContent.isArray()
                        || itemContent.size() != 1) {
                    throw new IllegalArgumentException(
                            "Each taskItem must contain exactly one paragraph"
                    );
                }
            }
        }
    }

    private void validateAttributes(JsonNode node, String type) {
        JsonNode attrs = node.get("attrs");
        if ("heading".equals(type)) {
            if (attrs == null || !attrs.isObject() || attrs.size() != 1
                    || !attrs.has("level") || !attrs.get("level").canConvertToInt()) {
                throw new IllegalArgumentException(
                        "heading attrs must contain level only"
                );
            }
            int level = attrs.get("level").intValue();
            if (!List.of(1, 2, 3).contains(level)) {
                throw new IllegalArgumentException(
                        "heading level must be 1, 2, or 3"
                );
            }
            return;
        }
        if ("taskItem".equals(type)) {
            if (attrs == null || !attrs.isObject() || attrs.size() != 1
                    || !attrs.has("checked") || !attrs.get("checked").isBoolean()) {
                throw new IllegalArgumentException(
                        "taskItem attrs must contain boolean checked only"
                );
            }
            return;
        }
        if (attrs != null) {
            throw new IllegalArgumentException(
                    type + " does not support attrs"
            );
        }
    }

    private void validateMarks(JsonNode node) {
        JsonNode marks = node.get("marks");
        if (marks == null) {
            return;
        }
        if (!marks.isArray()) {
            throw new IllegalArgumentException("text marks must be an array");
        }
        for (JsonNode mark : marks) {
            if (!mark.isObject() || mark.size() != 1
                    || !ALLOWED_MARK_TYPES.contains(mark.path("type").asText())) {
                throw new IllegalArgumentException(
                        "Unsupported Block content mark"
                );
            }
        }
    }

    private String extractDocumentText(JsonNode document) {
        StringBuilder result = new StringBuilder();
        ArrayNode blocks = (ArrayNode) document.get("content");
        for (int index = 0; index < blocks.size(); index++) {
            if (index > 0) {
                result.append('\n');
            }
            appendNodeText(blocks.get(index), result);
        }
        return result.toString();
    }

    private void appendNodeText(JsonNode node, StringBuilder result) {
        String type = node.path("type").asText();
        if ("text".equals(type)) {
            result.append(node.path("text").asText());
            return;
        }
        if ("hardBreak".equals(type)) {
            result.append('\n');
            return;
        }
        JsonNode content = node.get("content");
        if (content == null || !content.isArray()) {
            return;
        }
        boolean separateChildren = Set.of(
                "taskList", "taskItem", "bulletList", "orderedList",
                "listItem", "blockquote"
        ).contains(type);
        for (int index = 0; index < content.size(); index++) {
            if (separateChildren && index > 0) {
                result.append('\n');
            }
            appendNodeText(content.get(index), result);
        }
    }

    private JsonNode textDocument(DocumentBlockType blockType, String value) {
        String text = value == null ? "" : value;
        ObjectNode root = objectMapper.createObjectNode().put("type", "doc");
        ArrayNode content = root.putArray("content");
        switch (blockType) {
            case PARAGRAPH -> {
                for (String line : text.split("\\R", -1)) {
                    ObjectNode paragraph = content.addObject()
                            .put("type", "paragraph");
                    appendInlineContent(paragraph, line);
                }
            }
            case HEADING -> {
                ObjectNode heading = content.addObject().put("type", "heading");
                heading.putObject("attrs").put("level", 2);
                appendInlineContent(heading, text);
            }
            case CODE -> {
                ObjectNode codeBlock = content.addObject().put("type", "codeBlock");
                codeBlock.putArray("content").addObject()
                        .put("type", "text")
                        .put("text", text);
            }
            case TODO -> {
                ObjectNode taskItem = content.addObject()
                        .put("type", "taskList")
                        .putArray("content")
                        .addObject()
                        .put("type", "taskItem");
                taskItem.putObject("attrs").put("checked", false);
                ObjectNode paragraph = taskItem.putArray("content")
                        .addObject()
                        .put("type", "paragraph");
                appendInlineContent(paragraph, text);
            }
        }
        return root;
    }

    private void appendInlineContent(ObjectNode parent, String text) {
        ArrayNode content = parent.putArray("content");
        String[] lines = text.split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            if (index > 0) {
                content.addObject().put("type", "hardBreak");
            }
            if (!lines[index].isEmpty()) {
                content.addObject().put("type", "text").put("text", lines[index]);
            }
        }
    }

    private String normalizeText(String text) {
        if (text == null) {
            throw new IllegalArgumentException(
                    "Either content.text or content.document is required"
            );
        }
        String normalized = text.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Block content must not be blank");
        }
        if (normalized.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException(
                    "Block content must not exceed 20000 characters"
            );
        }
        return normalized;
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return value.asText();
    }

    private String write(JsonNode document) {
        try {
            return objectMapper.writeValueAsString(document);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "Block structured content is not valid JSON",
                    exception
            );
        }
    }

    public record NormalizedContent(
            String text,
            int schemaVersion,
            String documentJson,
            JsonNode document
    ) {
    }

    private static final class Counter {
        private int value;
    }
}
