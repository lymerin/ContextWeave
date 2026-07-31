package com.devcollab.knowledgecore.document.block.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class MarkdownToTiptapConverter {

    private final ObjectMapper objectMapper;
    private final Parser parser;

    public MarkdownToTiptapConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.parser = Parser.builder().build();
    }

    public JsonNode convert(String markdown) {
        Node parsed = parser.parse(markdown);
        ObjectNode root = objectMapper.createObjectNode().put("type", "doc");
        ArrayNode content = root.putArray("content");
        for (Node child = parsed.getFirstChild(); child != null; child = child.getNext()) {
            ObjectNode converted = block(child);
            if (converted != null) {
                content.add(converted);
            }
        }
        if (content.isEmpty()) {
            content.addObject().put("type", "paragraph");
        }
        return root;
    }

    private ObjectNode block(Node node) {
        if (node instanceof Paragraph paragraph) {
            return inlineBlock("paragraph", paragraph, null);
        }
        if (node instanceof Heading heading) {
            int level = Math.max(1, Math.min(3, heading.getLevel()));
            ObjectNode result = inlineBlock("heading", heading, null);
            result.putObject("attrs").put("level", level);
            return result;
        }
        if (node instanceof FencedCodeBlock fenced) {
            return codeBlock(fenced.getLiteral());
        }
        if (node instanceof IndentedCodeBlock indented) {
            return codeBlock(indented.getLiteral());
        }
        if (node instanceof BulletList list) {
            return list("bulletList", list);
        }
        if (node instanceof OrderedList list) {
            return list("orderedList", list);
        }
        if (node instanceof BlockQuote quote) {
            ObjectNode result = objectMapper.createObjectNode().put("type", "blockquote");
            appendBlocks(result.putArray("content"), quote);
            return result;
        }
        if (node instanceof ThematicBreak) {
            return objectMapper.createObjectNode().put("type", "horizontalRule");
        }
        if (node instanceof HtmlBlock html) {
            return literalParagraph(html.getLiteral());
        }
        return null;
    }

    private ObjectNode list(String type, Node list) {
        ObjectNode result = objectMapper.createObjectNode().put("type", type);
        ArrayNode items = result.putArray("content");
        for (Node child = list.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof ListItem) {
                ObjectNode item = items.addObject().put("type", "listItem");
                appendBlocks(item.putArray("content"), child);
            }
        }
        return result;
    }

    private void appendBlocks(ArrayNode target, Node parent) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
            ObjectNode converted = block(child);
            if (converted != null) {
                target.add(converted);
            }
        }
        if (target.isEmpty()) {
            target.addObject().put("type", "paragraph");
        }
    }

    private ObjectNode codeBlock(String literal) {
        ObjectNode result = objectMapper.createObjectNode().put("type", "codeBlock");
        if (literal != null && !literal.isEmpty()) {
            result.putArray("content").addObject()
                    .put("type", "text")
                    .put("text", literal.stripTrailing());
        }
        return result;
    }

    private ObjectNode inlineBlock(String type, Node parent, List<String> marks) {
        ObjectNode result = objectMapper.createObjectNode().put("type", type);
        ArrayNode content = result.putArray("content");
        appendInline(content, parent, marks == null ? List.of() : marks);
        if (content.isEmpty()) {
            result.remove("content");
        }
        return result;
    }

    private ObjectNode literalParagraph(String literal) {
        ObjectNode result = objectMapper.createObjectNode().put("type", "paragraph");
        if (literal != null && !literal.isBlank()) {
            result.putArray("content").add(text(literal.strip(), List.of()));
        }
        return result;
    }

    private void appendInline(ArrayNode target, Node parent, List<String> inheritedMarks) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Text value) {
                target.add(text(value.getLiteral(), inheritedMarks));
            } else if (child instanceof Code value) {
                target.add(text(value.getLiteral(), withMark(inheritedMarks, "code")));
            } else if (child instanceof StrongEmphasis) {
                appendInline(target, child, withMark(inheritedMarks, "bold"));
            } else if (child instanceof Emphasis) {
                appendInline(target, child, withMark(inheritedMarks, "italic"));
            } else if (child instanceof SoftLineBreak) {
                target.add(text(" ", inheritedMarks));
            } else if (child instanceof HardLineBreak) {
                target.add(objectMapper.createObjectNode().put("type", "hardBreak"));
            } else if (child instanceof Link) {
                appendInline(target, child, inheritedMarks);
            } else if (child instanceof HtmlInline html) {
                target.add(text(html.getLiteral(), inheritedMarks));
            } else {
                appendInline(target, child, inheritedMarks);
            }
        }
    }

    private ObjectNode text(String value, List<String> marks) {
        ObjectNode result = objectMapper.createObjectNode()
                .put("type", "text")
                .put("text", value);
        if (!marks.isEmpty()) {
            ArrayNode markNodes = result.putArray("marks");
            for (String mark : marks) {
                markNodes.addObject().put("type", mark);
            }
        }
        return result;
    }

    private List<String> withMark(List<String> inherited, String mark) {
        if (inherited.contains(mark)) {
            return inherited;
        }
        List<String> result = new ArrayList<>(inherited);
        result.add(mark);
        return List.copyOf(result);
    }
}
