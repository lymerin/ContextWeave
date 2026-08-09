package com.devcollab.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.devcollab.mcp.client.KnowledgeCoreGateway;
import com.devcollab.mcp.config.McpProperties;
import com.devcollab.mcp.error.McpToolErrorCode;
import com.devcollab.mcp.error.McpToolException;
import com.devcollab.mcp.security.McpUserIdentity;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DocumentStructureApplicationServiceTests {

    private KnowledgeCoreGateway gateway;
    private DocumentStructureApplicationService service;
    private McpUserIdentity identity;

    @BeforeEach
    void setUp() {
        gateway = mock(KnowledgeCoreGateway.class);
        McpProperties properties = new McpProperties(
                "/mcp", "test", "1", 400, 40000, 2048,
                100, 30000, 50, 20, 500, 200, 100,
                List.of("http://localhost:*"), List.of("localhost:*"),
                URI.create("http://localhost:8080"), Duration.ofSeconds(1)
        );
        service = new DocumentStructureApplicationService(gateway, properties);
        identity = new McpUserIdentity(UUID.randomUUID(), UUID.randomUUID(), "member", "token");
    }

    @Test
    void includeBlockContentFalseReturnsNoContent() {
        UUID workspaceId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        when(gateway.getDocumentStructure(eq(workspaceId), eq(documentId), eq(false), any(Integer.class), any(Integer.class), any()))
                .thenReturn(structure(documentId, workspaceId, false, false, List.of(
                        block(null, null, false)
                ), 0, 0));

        Map<String, Object> result = service.getDocumentStructure(workspaceId, documentId, false, null, null, identity);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) result.get("blocks");
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).get("plainText")).isNull();
        assertThat(blocks.get(0).get("content")).isNull();
    }

    @Test
    void includeBlockContentTrueReturnsContent() {
        UUID workspaceId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        when(gateway.getDocumentStructure(eq(workspaceId), eq(documentId), eq(true), any(Integer.class), any(Integer.class), any()))
                .thenReturn(structure(documentId, workspaceId, false, false, List.of(
                        block("Hello World", null, false)
                ), 0, 0));

        Map<String, Object> result = service.getDocumentStructure(workspaceId, documentId, true, null, null, identity);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) result.get("blocks");
        assertThat(blocks.get(0).get("plainText")).isEqualTo("Hello World");
    }

    @Test
    void truncatedAndOmittedCountsReported() {
        UUID workspaceId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        when(gateway.getDocumentStructure(eq(workspaceId), eq(documentId), eq(true), any(Integer.class), any(Integer.class), any()))
                .thenReturn(structure(documentId, workspaceId, true, true, List.of(
                        block("Hello World", null, false)
                ), 3, 25));

        Map<String, Object> result = service.getDocumentStructure(workspaceId, documentId, true, null, null, identity);

        assertThat(result.get("truncated")).isEqualTo(true);
        assertThat(result.get("omittedBlockCount")).isEqualTo(3);
        assertThat(result.get("omittedCharacterCount")).isEqualTo(25);
    }

    @Test
    void notTruncatedWhenNoBudgetTriggered() {
        UUID workspaceId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        when(gateway.getDocumentStructure(eq(workspaceId), eq(documentId), eq(true), any(Integer.class), any(Integer.class), any()))
                .thenReturn(structure(documentId, workspaceId, false, false, List.of(
                        block("short", null, false)
                ), 0, 0));

        Map<String, Object> result = service.getDocumentStructure(workspaceId, documentId, true, null, null, identity);

        assertThat(result.get("truncated")).isEqualTo(false);
        assertThat(result.get("omittedBlockCount")).isEqualTo(0);
        assertThat(result.get("omittedCharacterCount")).isEqualTo(0);
    }

    @Test
    void documentNotFoundMapped() {
        UUID workspaceId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        when(gateway.getDocumentStructure(eq(workspaceId), eq(documentId), eq(false), any(Integer.class), any(Integer.class), any()))
                .thenThrow(new McpToolException(McpToolErrorCode.DOCUMENT_NOT_FOUND, "not found"));

        assertThatThrownBy(() -> service.getDocumentStructure(workspaceId, documentId, false, null, null, identity))
                .isInstanceOf(McpToolException.class)
                .extracting("code")
                .isEqualTo(McpToolErrorCode.DOCUMENT_NOT_FOUND);
    }

    @Test
    void coreUnavailableMapped() {
        UUID workspaceId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        when(gateway.getDocumentStructure(eq(workspaceId), eq(documentId), eq(false), any(Integer.class), any(Integer.class), any()))
                .thenThrow(new McpToolException(McpToolErrorCode.CORE_UNAVAILABLE, "unavailable"));

        assertThatThrownBy(() -> service.getDocumentStructure(workspaceId, documentId, false, null, null, identity))
                .isInstanceOf(McpToolException.class)
                .extracting("code")
                .isEqualTo(McpToolErrorCode.CORE_UNAVAILABLE);
    }

    @Test
    void blocksPreserveSortOrderAndVersion() {
        UUID workspaceId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID blockId1 = UUID.randomUUID();
        UUID blockId2 = UUID.randomUUID();
        when(gateway.getDocumentStructure(eq(workspaceId), eq(documentId), eq(true), any(Integer.class), any(Integer.class), any()))
                .thenReturn(structure(documentId, workspaceId, false, false, List.of(
                        new KnowledgeCoreGateway.BlockInfo(blockId1, "PARAGRAPH", 0, 3L, "first", null, false),
                        new KnowledgeCoreGateway.BlockInfo(blockId2, "PARAGRAPH", 1, 5L, "second", null, false)
                ), 0, 0));

        Map<String, Object> result = service.getDocumentStructure(workspaceId, documentId, true, null, null, identity);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) result.get("blocks");
        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0).get("blockId")).isEqualTo(blockId1);
        assertThat(blocks.get(0).get("sortOrder")).isEqualTo(0);
        assertThat(blocks.get(0).get("version")).isEqualTo(3L);
        assertThat(blocks.get(1).get("blockId")).isEqualTo(blockId2);
        assertThat(blocks.get(1).get("sortOrder")).isEqualTo(1);
        assertThat(blocks.get(1).get("version")).isEqualTo(5L);
    }

    @Test
    void emojiInContentSurvivesWithoutCorruption() {
        UUID workspaceId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        String emojiText = "Hello \uD83D\uDE00 World";
        when(gateway.getDocumentStructure(eq(workspaceId), eq(documentId), eq(true), any(Integer.class), any(Integer.class), any()))
                .thenReturn(structure(documentId, workspaceId, false, false, List.of(
                        block(emojiText, null, false)
                ), 0, 0));

        Map<String, Object> result = service.getDocumentStructure(workspaceId, documentId, true, null, null, identity);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) result.get("blocks");
        String plainText = (String) blocks.get(0).get("plainText");
        assertThat(plainText).isEqualTo(emojiText);
    }

    @Test
    void workspaceIdPresentInOutput() {
        UUID workspaceId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        when(gateway.getDocumentStructure(eq(workspaceId), eq(documentId), eq(false), any(Integer.class), any(Integer.class), any()))
                .thenReturn(structure(documentId, workspaceId, false, false, List.of(), 0, 0));

        Map<String, Object> result = service.getDocumentStructure(workspaceId, documentId, false, null, null, identity);

        assertThat(result.get("workspaceId")).isEqualTo(workspaceId);
        assertThat(result.get("documentId")).isEqualTo(documentId);
    }

    @Test
    void batchAcceptsEmptyDocumentIdsWithoutCallingCore() {
        UUID workspaceId = UUID.randomUUID();

        Map<String, Object> result = service.getDocumentStructures(
                workspaceId, List.of(), true, null, null, identity
        );

        assertThat(result.get("includeBlockContent")).isEqualTo(true);
        assertThat((List<?>) result.get("structures")).isEmpty();
        verifyNoInteractions(gateway);
    }

    @Test
    void batchPreservesIncludeBlockContentSemantics() {
        UUID workspaceId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        when(gateway.getDocumentStructure(eq(workspaceId), eq(documentId), eq(true), any(Integer.class), any(Integer.class), any()))
                .thenReturn(structure(documentId, workspaceId, false, false, List.of(
                        block("included", null, false)
                ), 0, 0));

        Map<String, Object> result = service.getDocumentStructures(
                workspaceId, List.of(documentId), true, null, null, identity
        );

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> structures = (List<Map<String, Object>>) result.get("structures");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) structures.get(0).get("blocks");
        assertThat(blocks.get(0).get("plainText")).isEqualTo("included");
    }

    @Test
    void batchRejectsTooManyDocumentIds() {
        UUID workspaceId = UUID.randomUUID();
        List<UUID> ids = java.util.stream.IntStream.range(0, 101)
                .mapToObj(_index -> UUID.randomUUID())
                .toList();

        assertThatThrownBy(() -> service.getDocumentStructures(
                workspaceId, ids, false, null, null, identity
        )).isInstanceOfSatisfying(McpToolException.class,
                error -> assertThat(error.code()).isEqualTo(McpToolErrorCode.INVALID_ARGUMENT));
    }

    private KnowledgeCoreGateway.DocumentStructure structure(
            UUID documentId, UUID workspaceId, boolean truncated, boolean contentTruncated,
            List<KnowledgeCoreGateway.BlockInfo> blocks, int omittedBlockCount, int omittedCharacterCount) {
        return new KnowledgeCoreGateway.DocumentStructure(
                documentId, workspaceId, "Test Doc", "REQUIREMENT", "DRAFT",
                Instant.now(), blocks, truncated, omittedBlockCount, omittedCharacterCount
        );
    }

    private KnowledgeCoreGateway.BlockInfo block(String plainText, String content, boolean contentTruncated) {
        return new KnowledgeCoreGateway.BlockInfo(
                UUID.randomUUID(), "PARAGRAPH", 0, 1L, plainText, content, contentTruncated
        );
    }
}
