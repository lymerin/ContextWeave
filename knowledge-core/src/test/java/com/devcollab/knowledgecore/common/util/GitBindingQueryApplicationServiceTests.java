package com.devcollab.knowledgecore.git.application;

import com.devcollab.knowledgecore.common.outbox.application.OutboxEventPublisher;
import com.devcollab.knowledgecore.document.core.domain.Document;
import com.devcollab.knowledgecore.document.core.domain.DocumentBlockRepository;
import com.devcollab.knowledgecore.document.core.domain.DocumentRepository;
import com.devcollab.knowledgecore.document.core.domain.DocumentReviewStatus;
import com.devcollab.knowledgecore.document.core.domain.DocumentType;
import com.devcollab.knowledgecore.git.api.CodeBindingQueryResponse;
import com.devcollab.knowledgecore.git.domain.*;
import com.devcollab.knowledgecore.workspace.application.WorkspaceApplicationService;
import com.devcollab.knowledgecore.workspace.application.WorkspacePermissionPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GitBindingQueryApplicationServiceTests {

    private final GitKnowledgeRepository gitRepository = mock(GitKnowledgeRepository.class);
    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private GitKnowledgeApplicationService service;
    private UUID workspaceId;
    private UUID repositoryId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        service = new GitKnowledgeApplicationService(
                gitRepository, mock(WorkspaceApplicationService.class),
                mock(WorkspacePermissionPolicy.class), documentRepository,
                mock(DocumentBlockRepository.class), mock(OutboxEventPublisher.class),
                new CodeMetadataInspector(), null
        );
        workspaceId = UUID.randomUUID();
        repositoryId = UUID.randomUUID();
        userId = UUID.randomUUID();
        Instant now = Instant.parse("2026-07-26T00:00:00Z");
        when(gitRepository.findRepositoryById(repositoryId)).thenReturn(Optional.of(new GitRepository(
                repositoryId, workspaceId, "repo", GitProvider.GITHUB,
                "https://example.test/repo", "main", userId, now, now,
                GitRepositoryStatus.READY, "abc1234", now, null
        )));
    }

    @Test
    void deduplicatesOnlyBindingIdAndPreservesDifferentBlocksOfOneDocument() {
        UUID documentId = UUID.randomUUID();
        UUID firstBindingId = UUID.randomUUID();
        UUID secondBindingId = UUID.randomUUID();
        UUID firstBlockId = UUID.randomUUID();
        UUID secondBlockId = UUID.randomUUID();
        CodeDocumentBinding first = binding(firstBindingId, documentId, firstBlockId);
        CodeDocumentBinding second = binding(secondBindingId, documentId, secondBlockId);
        when(gitRepository.findBindingsByRepositoryId(repositoryId))
                .thenReturn(List.of(first, second, first));
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document(documentId, "API")));

        CodeBindingQueryResult result = service.queryBindings(
                workspaceId, repositoryId, userId, "src/App.java", null
        );

        assertThat(result.bindings()).extracting(CodeBindingQueryItem::bindingId)
                .containsExactlyInAnyOrder(firstBindingId, secondBindingId);
        assertThat(result.bindings()).extracting(CodeBindingQueryItem::blockId)
                .containsExactlyInAnyOrder(firstBlockId, secondBlockId);
        assertThat(result.fileHasBindings()).isTrue();
        assertThat(result.isTruncated()).isFalse();
        assertThat(result.omittedBindingCount()).isZero();
    }

    @Test
    void sortsBeforeLimitingAndKeepsNullTitleBinding() {
        UUID alphaDocumentId = UUID.randomUUID();
        UUID betaDocumentId = UUID.randomUUID();
        UUID missingDocumentId = UUID.randomUUID();
        when(gitRepository.findBindingsByRepositoryId(repositoryId)).thenReturn(List.of(
                binding(UUID.randomUUID(), missingDocumentId, null),
                binding(UUID.randomUUID(), betaDocumentId, null),
                binding(UUID.randomUUID(), alphaDocumentId, null)
        ));
        when(documentRepository.findById(alphaDocumentId)).thenReturn(Optional.of(document(alphaDocumentId, "alpha")));
        when(documentRepository.findById(betaDocumentId)).thenReturn(Optional.of(document(betaDocumentId, "Beta")));
        when(documentRepository.findById(missingDocumentId)).thenReturn(Optional.empty());

        CodeBindingQueryResult complete = service.queryBindings(
                workspaceId, repositoryId, userId, "src/App.java", null
        );
        CodeBindingQueryResult limited = service.queryBindings(
                workspaceId, repositoryId, userId, "src/App.java", 2
        );

        assertThat(complete.bindings()).extracting(CodeBindingQueryItem::documentTitle)
                .containsExactly("alpha", "Beta", null);
        assertThat(limited.bindings()).hasSize(2);
        assertThat(limited.isTruncated()).isTrue();
        assertThat(limited.omittedBindingCount()).isOne();
        assertThat(complete.bindings().get(2).documentId()).isEqualTo(missingDocumentId);
    }

    @Test
    void emptyFileReturnsSuccessfulEmptyResult() {
        when(gitRepository.findBindingsByRepositoryId(repositoryId)).thenReturn(List.of());

        CodeBindingQueryResult result = service.queryBindings(
                workspaceId, repositoryId, userId, "src/App.java", 10
        );

        assertThat(result.bindings()).isEmpty();
        assertThat(result.fileHasBindings()).isFalse();
        assertThat(result.isTruncated()).isFalse();
        assertThat(result.omittedBindingCount()).isZero();
    }

    @Test
    void responseJsonAcceptsNullTitleAndUsesPublicContractNames() throws Exception {
        UUID documentId = UUID.randomUUID();
        when(gitRepository.findBindingsByRepositoryId(repositoryId))
                .thenReturn(List.of(binding(UUID.randomUUID(), documentId, null)));
        when(documentRepository.findById(documentId)).thenReturn(Optional.empty());

        CodeBindingQueryResult result = service.queryBindings(
                workspaceId, repositoryId, userId, "src/App.java", 10
        );
        ObjectMapper mapper = new ObjectMapper();
        JsonNode json = mapper.readTree(mapper.writeValueAsBytes(CodeBindingQueryResponse.from(result)));

        assertThat(json.has("truncated")).isTrue();
        assertThat(json.has("isTruncated")).isFalse();
        assertThat(json.path("bindings").get(0).path("documentTitle").isNull()).isTrue();
        assertThat(json.path("bindings").get(0).path("anchorKind").asText())
                .isEqualTo("FILE");
        assertThat(json.path("bindings").get(0).path("revision").isNull()).isTrue();
        assertThat(json.path("bindings").get(0).has("createdAt")).isFalse();
    }

    @Test
    void filtersExactRevisionAndOptionalLegacyBeforeLimiting() {
        UUID documentId = UUID.randomUUID();
        CodeDocumentBinding legacy = preciseBinding(
                UUID.randomUUID(), documentId, null, null, CodeAnchorKind.FILE,
                null, null, null
        );
        CodeDocumentBinding revisionA = preciseBinding(
                UUID.randomUUID(), documentId, UUID.randomUUID(), "A",
                CodeAnchorKind.RANGE, null, 3, 8
        );
        CodeDocumentBinding revisionB = preciseBinding(
                UUID.randomUUID(), documentId, UUID.randomUUID(), "B",
                CodeAnchorKind.RANGE, null, 10, 12
        );
        when(gitRepository.findBindingsByRepositoryId(repositoryId))
                .thenReturn(List.of(legacy, revisionB, revisionA));
        when(documentRepository.findById(documentId))
                .thenReturn(Optional.of(document(documentId, "API")));

        CodeBindingQueryResult withLegacy = service.queryBindings(
                workspaceId, repositoryId, userId, "src/App.java",
                "A", true, 10
        );
        CodeBindingQueryResult withoutLegacy = service.queryBindings(
                workspaceId, repositoryId, userId, "src/App.java",
                "A", false, 10
        );

        // includeLegacy=true: 保留所有绑定（含其他 revision 的），revision 只用于排序。
        // 修复行为: 同步后 lastSyncedCommit 变化不能让旧 revision 的绑定从 UI 消失。
        assertThat(withLegacy.bindings()).extracting(CodeBindingQueryItem::bindingId)
                .containsExactlyInAnyOrder(revisionA.id(), legacy.id(), revisionB.id());
        // revision 匹配的排在最前
        assertThat(withLegacy.bindings().getFirst().revision()).isEqualTo("A");
        // includeLegacy=false + revision 指定: 严格匹配该 revision
        assertThat(withoutLegacy.bindings()).extracting(CodeBindingQueryItem::bindingId)
                .containsExactly(revisionA.id());
    }

    @Test
    void batchUsesSameRevisionFilteringOrderingAndLimit() {
        UUID documentId = UUID.randomUUID();
        CodeDocumentBinding legacy = preciseBinding(
                UUID.randomUUID(), documentId, null, null, CodeAnchorKind.FILE,
                null, null, null
        );
        CodeDocumentBinding exact = preciseBinding(
                UUID.randomUUID(), documentId, UUID.randomUUID(), "A",
                CodeAnchorKind.SYMBOL, "java:demo.App", 1, 20
        );
        CodeDocumentBinding other = preciseBinding(
                UUID.randomUUID(), documentId, null, "B", CodeAnchorKind.FILE,
                null, null, null
        );
        when(gitRepository.findBindingsByRepositoryId(repositoryId))
                .thenReturn(List.of(legacy, other, exact));
        when(documentRepository.findById(documentId))
                .thenReturn(Optional.of(document(documentId, "API")));

        CodeBindingBatchQueryResult result = service.queryBindingsBatch(
                workspaceId, repositoryId, userId, List.of("src/App.java"),
                "A", true, 1
        );

        var file = result.files().getFirst();
        assertThat(file.fileHasBindings()).isTrue();
        // includeLegacy=true 保留全部 3 条，revision=A 精确匹配的排最前，maxBindings=1 截断
        assertThat(file.bindings()).extracting(CodeBindingQueryItem::bindingId)
                .containsExactly(exact.id());
        assertThat(file.isTruncated()).isTrue();
        assertThat(file.omittedBindingCount()).isEqualTo(2);
    }

    private CodeDocumentBinding binding(UUID bindingId, UUID documentId, UUID blockId) {
        return new CodeDocumentBinding(
                bindingId, workspaceId, repositoryId, documentId, blockId,
                "src/App.java", userId, Instant.parse("2026-07-26T00:00:00Z")
        );
    }

    private CodeDocumentBinding preciseBinding(
            UUID bindingId,
            UUID documentId,
            UUID blockId,
            String revision,
            CodeAnchorKind anchorKind,
            String symbolKey,
            Integer startLine,
            Integer endLine
    ) {
        return new CodeDocumentBinding(
                bindingId,
                workspaceId,
                repositoryId,
                documentId,
                blockId,
                blockId == null ? "DOCUMENT" : blockId.toString(),
                "src/App.java",
                revision,
                anchorKind,
                symbolKey,
                startLine,
                endLine,
                userId,
                Instant.parse("2026-07-26T00:00:00Z")
        );
    }

    private Document document(UUID id, String title) {
        Instant now = Instant.parse("2026-07-26T00:00:00Z");
        return new Document(
                id, workspaceId, null, title, DocumentType.REQUIREMENT,
                DocumentReviewStatus.DRAFT, userId, now, now
        );
    }
}
