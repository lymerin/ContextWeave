package com.devcollab.knowledgecore.document.core.application;

import com.devcollab.knowledgecore.common.cache.ApprovedAdrCacheService;
import com.devcollab.knowledgecore.common.cache.PublishedDocumentCacheService;
import com.devcollab.knowledgecore.common.outbox.application.OutboxEventPublisher;
import com.devcollab.knowledgecore.document.block.api.DocumentStructureResponse;
import com.devcollab.knowledgecore.document.block.application.DocumentBlockContentCodec;
import com.devcollab.knowledgecore.document.block.application.DocumentStructureDto;
import com.devcollab.knowledgecore.document.collaboration.domain.DocumentOperationLogRepository;
import com.devcollab.knowledgecore.document.core.domain.Document;
import com.devcollab.knowledgecore.document.core.domain.DocumentBlock;
import com.devcollab.knowledgecore.document.core.domain.DocumentBlockRepository;
import com.devcollab.knowledgecore.document.core.domain.DocumentBlockType;
import com.devcollab.knowledgecore.document.core.domain.DocumentRepository;
import com.devcollab.knowledgecore.document.core.domain.DocumentReviewStatus;
import com.devcollab.knowledgecore.document.core.domain.DocumentType;
import com.devcollab.knowledgecore.document.version.domain.DocumentVersionRepository;
import com.devcollab.knowledgecore.document.tree.application.DocumentTreeCacheService;
import com.devcollab.knowledgecore.workspace.application.WorkspaceApplicationService;
import com.devcollab.knowledgecore.workspace.application.WorkspacePermissionPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentStructureApplicationServiceTests {

    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final DocumentBlockRepository blockRepository = mock(DocumentBlockRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private DocumentApplicationService service;
    private UUID workspaceId;
    private UUID documentId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        service = new DocumentApplicationService(
                documentRepository, blockRepository, mock(DocumentVersionRepository.class),
                mock(DocumentOperationLogRepository.class),
                mock(WorkspaceApplicationService.class), mock(WorkspacePermissionPolicy.class),
                mock(OutboxEventPublisher.class), objectMapper, mock(DocumentBlockContentCodec.class),
                mock(DocumentTreeCacheService.class), mock(PublishedDocumentCacheService.class),
                mock(ApprovedAdrCacheService.class)
        );
        workspaceId = UUID.randomUUID();
        documentId = UUID.randomUUID();
        userId = UUID.randomUUID();
        Instant now = Instant.parse("2026-07-26T00:00:00Z");
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(new Document(
                documentId, workspaceId, null, "Design", DocumentType.REQUIREMENT,
                DocumentReviewStatus.DRAFT, userId, now, now
        )));
    }

    @Test
    void contentDisabledReturnsNoBodyAndNoCharacterOmission() {
        when(blockRepository.findAllByDocumentId(documentId)).thenReturn(List.of(
                block(0, "text", "{\"type\":\"paragraph\"}"),
                block(1, "hidden", "{\"type\":\"paragraph\"}")
        ));

        DocumentStructureDto result = service.getDocumentStructure(
                workspaceId, documentId, userId, false, 1, 1
        );

        assertThat(result.blocks()).hasSize(1);
        assertThat(result.blocks().getFirst().plainText()).isNull();
        assertThat(result.blocks().getFirst().content()).isNull();
        assertThat(result.blocks().getFirst().isContentTruncated()).isFalse();
        assertThat(result.isTruncated()).isTrue();
        assertThat(result.omittedBlockCount()).isOne();
        assertThat(result.omittedCharacterCount()).isZero();
    }

    @Test
    void globalBudgetCountsLaterAndMaxBlocksContentWithoutBreakingJson() {
        DocumentBlock first = block(0, "A😀BC", "{\"type\":\"paragraph\"}");
        DocumentBlock second = block(1, "later", "{\"type\":\"code\"}");
        DocumentBlock omitted = block(2, "outside", "{\"type\":\"todo\"}");
        when(blockRepository.findAllByDocumentId(documentId)).thenReturn(List.of(omitted, second, first));

        DocumentStructureDto result = service.getDocumentStructure(
                workspaceId, documentId, userId, true, 2, 3
        );

        assertThat(result.blocks()).hasSize(2);
        assertThat(result.blocks().get(0).plainText()).isEqualTo("A😀B");
        assertThat(result.blocks().get(0).content()).isNull();
        assertThat(result.blocks().get(0).isContentTruncated()).isTrue();
        assertThat(result.blocks().get(1).plainText()).isNull();
        assertThat(result.blocks().get(1).content()).isNull();
        assertThat(result.blocks().get(1).isContentTruncated()).isTrue();
        int expectedOmitted = 1
                + codePoints(first.contentJson())
                + codePoints(second.text()) + codePoints(second.contentJson())
                + codePoints(omitted.text()) + codePoints(omitted.contentJson());
        assertThat(result.omittedCharacterCount()).isEqualTo(expectedOmitted);
        assertThat(result.omittedBlockCount()).isOne();
        assertThat(result.isTruncated()).isTrue();
    }

    @Test
    void completeJsonIsReturnedOnlyWhenItFits() {
        String contentJson = "{\"type\":\"paragraph\",\"content\":[]}";
        when(blockRepository.findAllByDocumentId(documentId)).thenReturn(List.of(block(0, "x", contentJson)));

        DocumentStructureDto tooSmall = service.getDocumentStructure(
                workspaceId, documentId, userId, true, null, codePoints(contentJson)
        );
        DocumentStructureDto enough = service.getDocumentStructure(
                workspaceId, documentId, userId, true, null, codePoints(contentJson) + 1
        );

        assertThat(tooSmall.blocks().getFirst().content()).isNull();
        assertThat(tooSmall.omittedCharacterCount()).isEqualTo(codePoints(contentJson));
        assertThat(enough.blocks().getFirst().content()).isEqualTo(contentJson);
        assertThat(enough.omittedCharacterCount()).isZero();
    }

    @Test
    void responseJsonUsesThePublicContractNames() throws Exception {
        when(blockRepository.findAllByDocumentId(documentId))
                .thenReturn(List.of(block(0, "text", "{\"type\":\"paragraph\"}")));
        DocumentStructureDto dto = service.getDocumentStructure(
                workspaceId, documentId, userId, true, null, 1
        );

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsBytes(DocumentStructureResponse.from(dto)));

        assertThat(json.has("truncated")).isTrue();
        assertThat(json.has("isTruncated")).isFalse();
        JsonNode block = json.path("blocks").get(0);
        assertThat(block.has("blockType")).isTrue();
        assertThat(block.has("plainText")).isTrue();
        assertThat(block.has("content")).isTrue();
        assertThat(block.has("contentTruncated")).isTrue();
        assertThat(block.has("type")).isFalse();
        assertThat(block.has("text")).isFalse();
        assertThat(block.has("contentJson")).isFalse();
    }

    private DocumentBlock block(int sortOrder, String text, String contentJson) {
        Instant now = Instant.parse("2026-07-26T00:00:00Z");
        return new DocumentBlock(
                UUID.randomUUID(), documentId, DocumentBlockType.PARAGRAPH, text, 1,
                contentJson, sortOrder, sortOrder + 1L, userId, now, now
        );
    }

    private static int codePoints(String value) {
        return value.codePointCount(0, value.length());
    }
}
