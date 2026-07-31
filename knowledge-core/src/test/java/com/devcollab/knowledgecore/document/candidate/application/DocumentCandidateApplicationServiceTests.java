package com.devcollab.knowledgecore.document.candidate.application;

import com.devcollab.knowledgecore.document.core.domain.Document;
import com.devcollab.knowledgecore.document.core.domain.DocumentBlock;
import com.devcollab.knowledgecore.document.core.domain.DocumentBlockRepository;
import com.devcollab.knowledgecore.document.core.domain.DocumentBlockType;
import com.devcollab.knowledgecore.document.core.domain.DocumentRepository;
import com.devcollab.knowledgecore.document.core.domain.DocumentReviewStatus;
import com.devcollab.knowledgecore.document.core.domain.DocumentType;
import com.devcollab.knowledgecore.document.candidate.api.DocumentCandidateResponse;
import com.devcollab.knowledgecore.git.application.exception.GitRepositoryNotFoundException;
import com.devcollab.knowledgecore.git.domain.*;
import com.devcollab.knowledgecore.workspace.application.WorkspaceApplicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentCandidateApplicationServiceTests {

    private final WorkspaceApplicationService workspaceService = mock(WorkspaceApplicationService.class);
    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final DocumentBlockRepository blockRepository = mock(DocumentBlockRepository.class);
    private final GitKnowledgeRepository gitRepository = mock(GitKnowledgeRepository.class);
    private final DocumentCandidateApplicationService service =
            new DocumentCandidateApplicationService(
                    workspaceService, documentRepository, blockRepository, gitRepository
            );
    private UUID workspaceId;
    private UUID repositoryId;
    private UUID userId;
    private Instant now;

    @BeforeEach
    void setUp() {
        workspaceId = UUID.randomUUID();
        repositoryId = UUID.randomUUID();
        userId = UUID.randomUUID();
        now = Instant.parse("2026-07-26T00:00:00Z");
        when(gitRepository.findRepositoryById(repositoryId)).thenReturn(Optional.of(repository(workspaceId)));
    }

    @Test
    void aggregatesRealSignalsAndExplainsScore() {
        Document document = document("Order API Design");
        DocumentBlock block = block(document.id(), "Order controller creates an API endpoint");
        UUID bindingId = UUID.randomUUID();
        CodeDocumentBinding binding = new CodeDocumentBinding(
                bindingId, workspaceId, repositoryId, document.id(), block.id(),
                "src/order/OrderController.java", userId, now
        );
        when(documentRepository.findAllByWorkspaceId(workspaceId)).thenReturn(List.of(document));
        when(blockRepository.findAllByDocumentId(document.id())).thenReturn(List.of(block));
        when(gitRepository.findBindingsByRepositoryId(repositoryId)).thenReturn(List.of(binding, binding));

        DocumentCandidateResult result = service.findCandidates(
                workspaceId, repositoryId, "src/order/OrderController.java",
                "Order API Design", 20, userId
        );

        assertThat(result.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.matchReasons()).extracting(DocumentCandidateMatchReason::code)
                    .containsExactlyInAnyOrder(
                            "DIRECT_BINDING", "TITLE_EXACT", "TITLE_TOKEN",
                            "BLOCK_TEXT", "FILE_NAME", "PATH_TOKEN"
                    );
            assertThat(candidate.matchReasons()).extracting(DocumentCandidateMatchReason::code)
                    .doesNotHaveDuplicates();
            assertThat(candidate.score()).isEqualTo(
                    DocumentCandidateApplicationService.DIRECT_BINDING_WEIGHT
                            + DocumentCandidateApplicationService.TITLE_EXACT_WEIGHT
                            + DocumentCandidateApplicationService.TITLE_TOKEN_WEIGHT
                            + DocumentCandidateApplicationService.BLOCK_TEXT_WEIGHT
                            + DocumentCandidateApplicationService.FILE_NAME_WEIGHT
                            + DocumentCandidateApplicationService.PATH_TOKEN_WEIGHT
            );
            assertThat(candidate.existingBindingCount()).isOne();
            assertThat(candidate.matchedBlockIds()).containsExactly(block.id());
        });
    }

    @Test
    void danglingBindingDoesNotCreateCandidate() {
        CodeDocumentBinding dangling = new CodeDocumentBinding(
                UUID.randomUUID(), workspaceId, repositoryId, UUID.randomUUID(), null,
                "src/App.java", userId, now
        );
        when(documentRepository.findAllByWorkspaceId(workspaceId)).thenReturn(List.of());
        when(gitRepository.findBindingsByRepositoryId(repositoryId)).thenReturn(List.of(dangling));

        DocumentCandidateResult result = service.findCandidates(
                workspaceId, repositoryId, "src/App.java", null, 20, userId
        );

        assertThat(result.candidates()).isEmpty();
    }

    @Test
    void stableSortAndTruncationAreComputedBeforeLimit() {
        Document alpha = document("Alpha Guide");
        Document beta = document("beta guide");
        Document gamma = document("Gamma Guide");
        when(documentRepository.findAllByWorkspaceId(workspaceId)).thenReturn(List.of(gamma, beta, alpha));
        when(blockRepository.findAllByDocumentId(any())).thenReturn(List.of());

        DocumentCandidateResult result = service.findCandidates(
                workspaceId, null, null, "guide", 2, userId
        );

        assertThat(result.candidates()).extracting(DocumentCandidateItem::title)
                .containsExactly("Alpha Guide", "beta guide");
        assertThat(result.truncated()).isTrue();
        assertThat(result.omittedCandidateCount()).isOne();
    }

    @Test
    void noMatchesIsSuccessfulEmptyResult() {
        Document document = document("Architecture");
        when(documentRepository.findAllByWorkspaceId(workspaceId)).thenReturn(List.of(document));
        when(blockRepository.findAllByDocumentId(document.id())).thenReturn(List.of());

        DocumentCandidateResult result = service.findCandidates(
                workspaceId, null, null, "unrelated", 20, userId
        );

        assertThat(result.candidates()).isEmpty();
        assertThat(result.truncated()).isFalse();
        assertThat(result.omittedCandidateCount()).isZero();
        verify(workspaceService).requireMembership(workspaceId, userId);
    }

    @Test
    void coreResponseJsonMatchesTheCandidateContract() throws Exception {
        Document document = document("Architecture");
        when(documentRepository.findAllByWorkspaceId(workspaceId)).thenReturn(List.of(document));
        when(blockRepository.findAllByDocumentId(document.id())).thenReturn(List.of());
        DocumentCandidateResult result = service.findCandidates(
                workspaceId, null, null, "Architecture", 20, userId
        );

        JsonNode json = new ObjectMapper().readTree(
                new ObjectMapper().writeValueAsBytes(DocumentCandidateResponse.from(result))
        );

        assertThat(json.has("workspaceId")).isTrue();
        assertThat(json.path("repositoryId").isNull()).isTrue();
        assertThat(json.path("filePath").isNull()).isTrue();
        assertThat(json.path("query").asText()).isEqualTo("Architecture");
        assertThat(json.has("truncated")).isTrue();
        assertThat(json.has("omittedCandidateCount")).isTrue();
        JsonNode candidate = json.path("candidates").get(0);
        assertThat(candidate.has("documentId")).isTrue();
        assertThat(candidate.has("title")).isTrue();
        assertThat(candidate.has("score")).isTrue();
        assertThat(candidate.has("matchReasons")).isTrue();
        assertThat(candidate.has("matchedBlockIds")).isTrue();
        assertThat(candidate.has("existingBindingCount")).isTrue();
    }

    @Test
    void validatesRepositoryBelongsToWorkspace() {
        when(gitRepository.findRepositoryById(repositoryId))
                .thenReturn(Optional.of(repository(UUID.randomUUID())));

        assertThatThrownBy(() -> service.findCandidates(
                workspaceId, repositoryId, null, "query", 20, userId
        )).isInstanceOf(GitRepositoryNotFoundException.class);
        verify(workspaceService).requireMembership(workspaceId, userId);
    }

    @Test
    void requiresRepositoryForFilePath() {
        assertThatThrownBy(() -> service.findCandidates(
                workspaceId, null, "src/App.java", null, 20, userId
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingAndBlankSearchInputs() {
        assertThatThrownBy(() -> service.findCandidates(
                workspaceId, null, null, null, 20, userId
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.findCandidates(
                workspaceId, null, null, "   ", 20, userId
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "../a", "a/../b", "a\\..\\b", "/etc/passwd", "\\absolute",
            "C:\\file", "C:/file", "C:file", "\\\\server\\share"
    })
    void rejectsUnsafePaths(String path) {
        assertThatThrownBy(() -> service.findCandidates(
                workspaceId, repositoryId, path, null, 20, userId
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidLimitAndOversizedQuery() {
        assertThatThrownBy(() -> service.findCandidates(
                workspaceId, null, null, "query", 0, userId
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.findCandidates(
                workspaceId, null, null, "x".repeat(501), 20, userId
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private Document document(String title) {
        return new Document(
                UUID.randomUUID(), workspaceId, null, title, DocumentType.REQUIREMENT,
                DocumentReviewStatus.DRAFT, userId, now, now
        );
    }

    private DocumentBlock block(UUID documentId, String text) {
        return new DocumentBlock(
                UUID.randomUUID(), documentId, DocumentBlockType.PARAGRAPH, text,
                1, "{\"type\":\"paragraph\"}", 0, 1, userId, now, now
        );
    }

    private GitRepository repository(UUID ownerWorkspaceId) {
        return new GitRepository(
                repositoryId, ownerWorkspaceId, "repo", GitProvider.GITHUB,
                "https://example.test/repo", "main", userId, now, now,
                GitRepositoryStatus.READY, "abc1234", now, null
        );
    }
}
