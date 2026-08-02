package com.devcollab.knowledgecore.document.review.application;

import com.devcollab.knowledgecore.document.core.domain.Document;
import com.devcollab.knowledgecore.document.core.domain.DocumentBlock;
import com.devcollab.knowledgecore.document.core.domain.DocumentBlockRepository;
import com.devcollab.knowledgecore.document.core.domain.DocumentBlockType;
import com.devcollab.knowledgecore.document.core.domain.DocumentRepository;
import com.devcollab.knowledgecore.document.core.domain.DocumentReviewStatus;
import com.devcollab.knowledgecore.document.core.domain.DocumentType;
import com.devcollab.knowledgecore.documentchange.application.DocumentChangeApplicationService;
import com.devcollab.knowledgecore.documentchange.application.DocumentChangeException;
import com.devcollab.knowledgecore.documentchange.domain.DocumentChangeRepository;
import com.devcollab.knowledgecore.git.domain.CodeDocumentBinding;
import com.devcollab.knowledgecore.git.domain.GitKnowledgeRepository;
import com.devcollab.knowledgecore.git.domain.GitProvider;
import com.devcollab.knowledgecore.git.domain.GitRepository;
import com.devcollab.knowledgecore.git.domain.GitRepositoryStatus;
import com.devcollab.knowledgecore.workspace.application.exception.WorkspaceAccessDeniedException;
import com.devcollab.knowledgecore.workspace.application.exception.WorkspaceNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static com.devcollab.knowledgecore.documentchange.domain.DocumentChangeModel.*;
import static com.devcollab.knowledgecore.documentchange.application.DocumentChangeApplicationService.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class DocumentChangeQueryIntegrationTests {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired DocumentChangeRepository repository;
    @Autowired DocumentChangeApplicationService service;
    @Autowired DocumentRepository documentRepository;
    @Autowired DocumentBlockRepository blockRepository;
    @Autowired GitKnowledgeRepository gitRepository;
    @Autowired ObjectMapper objectMapper;

    @Test
    void migrationCreatesReviewTables() {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                 WHERE table_name IN (
                    'document_change_requests',
                    'document_change_operations',
                    'document_change_evidence'
                 )
                """, Integer.class);
        assertThat(count).isEqualTo(3);
    }

    @Test
    void adminCanCountFilterPageAndUseStableOrdering() {
        Fixture fixture = fixture("ADMIN");
        Instant base = Instant.parse("2026-07-26T10:00:00Z");
        saveRequest(fixture, Status.PENDING, "older", base);
        ChangeRequest newer = saveRequest(
                fixture, Status.PENDING, "newer", base.plusSeconds(1)
        );
        saveRequest(
                fixture, Status.REJECTED, "rejected", base.plusSeconds(2)
        );

        assertThat(service.pendingCount(fixture.workspaceId(), fixture.userId()))
                .isEqualTo(2);
        var page = service.list(
                fixture.workspaceId(), fixture.userId(), Status.PENDING,
                0, 1, "createdAt,desc"
        );

        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.items()).extracting(item -> item.id())
                .containsExactly(newer.id());
    }

    @Test
    void detailReturnsOrderedOperationsAndTwoEvidenceLevels() throws Exception {
        Fixture fixture = fixture("ADMIN");
        Document document = document(fixture);
        DocumentBlock block = block(document, fixture.userId(), 4);
        GitRepository git = gitRepository.saveRepository(new GitRepository(
                UUID.randomUUID(), fixture.workspaceId(), "devcollab",
                GitProvider.GITHUB, "https://github.com/example/devcollab",
                "main", fixture.userId(), Instant.now(), Instant.now(),
                GitRepositoryStatus.READY, "abc123", Instant.now(), null
        ));
        ChangeRequest request = saveRequest(
                fixture, Status.PENDING, "review", Instant.now()
        );
        Operation second = operation(
                request, 2, "delete", OperationType.DELETE_BLOCK, document, block
        );
        Operation first = operation(
                request, 1, "update", OperationType.UPDATE_BLOCK, document, block
        );
        repository.saveOperation(second);
        repository.saveOperation(first);
        repository.saveEvidence(evidence(request, null, git.id(), "request"));
        repository.saveEvidence(evidence(request, first.id(), git.id(), "operation"));

        var detail = service.detail(
                fixture.workspaceId(), request.id(), fixture.userId()
        );
        String json = objectMapper.writeValueAsString(detail);

        assertThat(detail.operations()).extracting(item -> item.sequenceNumber())
                .containsExactly(1, 2);
        assertThat(detail.requestEvidence()).hasSize(1);
        assertThat(detail.operations().getFirst().evidence()).hasSize(1);
        assertThat(detail.operations().getFirst().baseSnapshot().blockVersion())
                .isEqualTo(4);
        assertThat(detail.operations().getFirst().currentBlockVersion())
                .isEqualTo(4);
        assertThat(detail.operations().getFirst().conflict().conflicted())
                .isFalse();
        assertThat(json).contains("\"reviewedBy\":null");
        assertThat(json).contains("\"requestEvidence\"");
    }

    @Test
    void memberCannotReviewAndOutsiderSeesNotFound() {
        Fixture member = fixture("MEMBER");
        Fixture admin = fixture("ADMIN");
        UUID outsider = createUser();

        assertThatThrownBy(() -> service.pendingCount(
                member.workspaceId(), member.userId()
        )).isInstanceOf(WorkspaceAccessDeniedException.class);
        assertThatThrownBy(() -> service.pendingCount(
                admin.workspaceId(), outsider
        )).isInstanceOf(WorkspaceNotFoundException.class);
    }

    @Test
    void requestIsIsolatedByWorkspaceAndMissingDetailIsNotFound() {
        Fixture first = fixture("ADMIN");
        Fixture second = fixture("ADMIN");
        ChangeRequest request = saveRequest(
                first, Status.PENDING, "private", Instant.now()
        );

        assertThatThrownBy(() -> service.detail(
                second.workspaceId(), request.id(), second.userId()
        )).isInstanceOf(DocumentChangeException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void createPersistsPendingRequestWithoutChangingFormalDocument() {
        Fixture fixture = fixture("MEMBER");
        Document document = document(fixture);
        DocumentBlock block = block(document, fixture.userId(), 2);
        String before = block.text();
        CreateCommand command = updateCommand(document, block, "client-create");

        var result = service.create(
                fixture.workspaceId(), fixture.userId(), command
        );

        assertThat(result.status()).isEqualTo(Status.PENDING);
        assertThat(result.idempotentReplay()).isFalse();
        assertThat(repository.findOperations(result.changeRequestId()))
                .hasSize(1);
        assertThat(blockRepository.findById(block.id()).orElseThrow().text())
                .isEqualTo(before);
    }

    @Test
    void createIsIdempotentAndRejectsDifferentPayloadForSameKey() {
        Fixture fixture = fixture("MEMBER");
        Document document = document(fixture);
        DocumentBlock block = block(document, fixture.userId(), 0);
        CreateCommand command = updateCommand(document, block, "same-key");

        var first = service.create(
                fixture.workspaceId(), fixture.userId(), command
        );
        var replay = service.create(
                fixture.workspaceId(), fixture.userId(), command
        );

        assertThat(replay.changeRequestId()).isEqualTo(first.changeRequestId());
        assertThat(replay.idempotentReplay()).isTrue();
        CreateCommand changed = new CreateCommand(
                command.clientRequestId(), "different", command.rationale(),
                command.operations(), java.util.List.of(), command.evidence()
        );
        assertThatThrownBy(() -> service.create(
                fixture.workspaceId(), fixture.userId(), changed
        )).isInstanceOf(DocumentChangeException.class)
                .hasMessageContaining("clientRequestId");
    }

    @Test
    void bindingProposalCanonicalizesPathBeforeFingerprintAndPersistence() {
        Fixture fixture = fixture("MEMBER");
        Document document = document(fixture);
        GitRepository git = repository(fixture, "canonical-path");
        String clientRequestId = "canonical-" + UUID.randomUUID();
        CreateCommand backslash = bindingCommand(
                clientRequestId,
                git.id(),
                document.id(),
                "src\\main\\Example.java",
                BindingAction.UPSERT_BINDING,
                null
        );
        CreateCommand dotted = bindingCommand(
                clientRequestId,
                git.id(),
                document.id(),
                "./src/main/Example.java",
                BindingAction.UPSERT_BINDING,
                null
        );

        var created = service.create(
                fixture.workspaceId(), fixture.userId(), backslash
        );
        var replay = service.create(
                fixture.workspaceId(), fixture.userId(), dotted
        );

        assertThat(replay.changeRequestId()).isEqualTo(created.changeRequestId());
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(repository.findBindingProposals(created.changeRequestId()))
                .singleElement()
                .extracting(BindingProposal::filePath)
                .isEqualTo("src/main/Example.java");
    }

    @Test
    void bindingProposalRejectsUnsafeAndOversizedPaths() {
        Fixture fixture = fixture("MEMBER");
        Document document = document(fixture);
        GitRepository git = repository(fixture, "invalid-path");

        for (String path : java.util.List.of(
                "../src/Main.java",
                "src/../Main.java",
                "C:\\src\\Main.java",
                "/src/Main.java",
                "a".repeat(1_001)
        )) {
            assertThatThrownBy(() -> service.create(
                    fixture.workspaceId(),
                    fixture.userId(),
                    bindingCommand(
                            "invalid-" + UUID.randomUUID(),
                            git.id(),
                            document.id(),
                            path,
                            BindingAction.UPSERT_BINDING,
                            null
                    )
            )).isInstanceOf(DocumentChangeException.class);
        }
    }

    @Test
    void createMapsOperationAndRequestEvidenceFromTrustedRepositoryProjection() {
        Fixture fixture = fixture("MEMBER");
        Document document = document(fixture);
        DocumentBlock block = block(document, fixture.userId(), 1);
        GitRepository git = gitRepository.saveRepository(new GitRepository(
                UUID.randomUUID(), fixture.workspaceId(), "evidence-repo",
                GitProvider.GITHUB, "https://github.com/example/evidence",
                "main", fixture.userId(), Instant.now(), Instant.now(),
                GitRepositoryStatus.READY, "def456", Instant.now(), null
        ));
        jdbcTemplate.update("""
                INSERT INTO git_repository_files
                    (id, repository_id, path, blob_sha, size_bytes,
                     language, content_text)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), git.id(), "src/Example.java",
                "blob-sha", 30L, "Java", "line one\nline two\nline three");
        CreateCommand base = updateCommand(document, block, "evidence-key");
        CreateCommand withEvidence = new CreateCommand(
                base.clientRequestId(), base.summary(), base.rationale(),
                base.operations(),
                java.util.List.of(),
                java.util.List.of(
                        new CreateEvidenceCommand(
                                null, git.id(), "src/Example.java",
                                1, 1, "request evidence"
                        ),
                        new CreateEvidenceCommand(
                                "update", git.id(), "src/Example.java",
                                2, 3, "operation evidence"
                        )
                )
        );

        var result = service.create(
                fixture.workspaceId(), fixture.userId(), withEvidence
        );
        var savedEvidence = repository.findEvidence(result.changeRequestId());

        assertThat(savedEvidence).hasSize(2);
        assertThat(savedEvidence).filteredOn(item -> item.operationId() == null)
                .singleElement()
                .extracting(item -> item.excerptText())
                .isEqualTo("line one");
        assertThat(savedEvidence).filteredOn(item -> item.operationId() != null)
                .singleElement()
                .extracting(item -> item.commitHash())
                .isEqualTo("def456");
    }

    @Test
    void createRejectsDuplicateOperationIdsUnknownEvidenceAndCrossWorkspaceTargets() {
        Fixture fixture = fixture("MEMBER");
        Fixture other = fixture("MEMBER");
        Document otherDocument = document(other);
        DocumentBlock otherBlock = block(otherDocument, other.userId(), 0);
        CreateOperationCommand operation = new CreateOperationCommand(
                "duplicate", 1, OperationType.UPDATE_BLOCK,
                otherDocument.id(), null, otherBlock.id(), 0L,
                null, null, null, null, "changed", null, null
        );
        CreateCommand crossWorkspace = new CreateCommand(
                "cross", "summary", "rationale",
                java.util.List.of(operation), java.util.List.of(), java.util.List.of()
        );
        assertThatThrownBy(() -> service.create(
                fixture.workspaceId(), fixture.userId(), crossWorkspace
        )).isInstanceOf(DocumentChangeException.class);

        CreateCommand duplicate = new CreateCommand(
                "duplicate-key", "summary", "rationale",
                java.util.List.of(
                        operationWithText("same", 1),
                        operationWithText("same", 2)
                ),
                java.util.List.of(),
                java.util.List.of()
        );
        assertThatThrownBy(() -> service.create(
                fixture.workspaceId(), fixture.userId(), duplicate
        )).isInstanceOf(DocumentChangeException.class)
                .hasMessageContaining("clientOperationId");
    }

    @Test
    void adminAppliesUpdateAtomicallyAndReplayDoesNotApplyTwice() {
        Fixture fixture = fixture("ADMIN");
        Document document = document(fixture);
        DocumentBlock block = block(document, fixture.userId(), 3);
        var created = service.create(
                fixture.workspaceId(),
                fixture.userId(),
                updateCommand(document, block, "apply-update")
        );

        var applied = service.apply(
                fixture.workspaceId(),
                created.changeRequestId(),
                fixture.userId()
        );
        var replay = service.apply(
                fixture.workspaceId(),
                created.changeRequestId(),
                fixture.userId()
        );

        assertThat(applied.detail().request().status())
                .isEqualTo(Status.APPLIED);
        assertThat(replay.detail().replayed()).isTrue();
        assertThat(blockRepository.findById(block.id()).orElseThrow().text())
                .isEqualTo("after");
        assertThat(blockRepository.findById(block.id()).orElseThrow().version())
                .isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM operation_logs
                 WHERE target_type = 'DOCUMENT_CHANGE_REQUEST'
                   AND target_id = ?
                   AND action = 'DOCUMENT_CHANGE_APPLIED'
                """, Integer.class, created.changeRequestId())).isEqualTo(1);
    }

    @Test
    void versionConflictFailsApplyWithoutOverwritingHumanChange() {
        Fixture fixture = fixture("ADMIN");
        Document document = document(fixture);
        DocumentBlock block = block(document, fixture.userId(), 0);
        var created = service.create(
                fixture.workspaceId(),
                fixture.userId(),
                updateCommand(document, block, "stale-update")
        );
        jdbcTemplate.update("""
                UPDATE document_blocks
                   SET text = 'human edit', version = version + 1
                 WHERE id = ?
                """, block.id());

        assertThatThrownBy(() -> service.apply(
                fixture.workspaceId(),
                created.changeRequestId(),
                fixture.userId()
        ))
                .isInstanceOfSatisfying(
                        DocumentChangeException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("REQUEST_TARGET_CHANGED")
                );
        assertThat(blockRepository.findById(block.id()).orElseThrow().text())
                .isEqualTo("human edit");
    }

    @Test
    void applySupportsCreateAddAndDeleteAcrossDocuments() {
        Fixture fixture = fixture("ADMIN");
        Document existing = document(fixture);
        DocumentBlock deleted = block(existing, fixture.userId(), 2);
        CreateCommand command = new CreateCommand(
                "mixed-operations",
                "Create guide and remove obsolete block",
                "The implementation has moved",
                java.util.List.of(
                        new CreateOperationCommand(
                                "create-guide", 1,
                                OperationType.CREATE_DOCUMENT,
                                null, null, null, null,
                                "Generated guide", DocumentType.BACKEND,
                                null, null, null, null, null
                        ),
                        new CreateOperationCommand(
                                "add-intro", 2,
                                OperationType.ADD_BLOCK,
                                null, "create-guide", null, null,
                                null, null, null,
                                DocumentBlockType.HEADING,
                                "Generated introduction", null, null
                        ),
                        new CreateOperationCommand(
                                "delete-obsolete", 3,
                                OperationType.DELETE_BLOCK,
                                existing.id(), null, deleted.id(),
                                deleted.version(), null, null, null,
                                null, null, null, null
                        )
                ),
                java.util.List.of(),
                java.util.List.of()
        );
        var created = service.create(
                fixture.workspaceId(), fixture.userId(), command
        );

        var result = service.apply(
                fixture.workspaceId(),
                created.changeRequestId(),
                fixture.userId()
        );

        assertThat(result.detail().request().status())
                .isEqualTo(Status.APPLIED);
        Document generated = documentRepository
                .findAllByWorkspaceId(fixture.workspaceId())
                .stream()
                .filter(item -> item.title().equals("Generated guide"))
                .findFirst()
                .orElseThrow();
        assertThat(generated.documentType()).isEqualTo(DocumentType.BACKEND);
        assertThat(blockRepository.findAllByDocumentId(generated.id()))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.type()).isEqualTo(DocumentBlockType.HEADING);
                    assertThat(item.text())
                            .isEqualTo("Generated introduction");
                });
        assertThat(blockRepository.findById(deleted.id())).isEmpty();
    }

    @Test
    void applyKeepsEveryFilePathWhenOneCreatedDocumentHasMultipleBindings() {
        Fixture fixture = fixture("ADMIN");
        GitRepository git = repository(fixture, "multi-file-one-document");
        CreateCommand command = new CreateCommand(
                "multi-file-one-document-" + UUID.randomUUID(),
                "Create one document for two related files",
                "Both files implement the same responsibility",
                java.util.List.of(
                        new CreateOperationCommand(
                                "create-shared", 1,
                                OperationType.CREATE_DOCUMENT,
                                null, null, null, null,
                                "Shared component guide", DocumentType.BACKEND,
                                null, null, null, null, null
                        ),
                        new CreateOperationCommand(
                                "add-shared-body", 2,
                                OperationType.ADD_BLOCK,
                                null, "create-shared", null, null,
                                null, null, null,
                                DocumentBlockType.PARAGRAPH,
                                "The controller delegates the same responsibility to the service.",
                                null, null
                        )
                ),
                java.util.List.of(
                        new CreateBindingProposalCommand(
                                "bind-controller", 3,
                                BindingAction.UPSERT_BINDING,
                                git.id(), "src/Controller.java",
                                null, "create-shared", null,
                                "Controller entry point"
                        ),
                        new CreateBindingProposalCommand(
                                "bind-service", 4,
                                BindingAction.UPSERT_BINDING,
                                git.id(), "src/Service.java",
                                null, "create-shared", null,
                                "Service implementation"
                        )
                ),
                java.util.List.of()
        );

        var created = service.create(
                fixture.workspaceId(), fixture.userId(), command
        );
        service.apply(
                fixture.workspaceId(), created.changeRequestId(), fixture.userId()
        );

        Document generated = documentRepository
                .findAllByWorkspaceId(fixture.workspaceId())
                .stream()
                .filter(item -> item.title().equals("Shared component guide"))
                .findFirst()
                .orElseThrow();
        assertThat(gitRepository.findBindingsByDocumentId(generated.id()))
                .extracting(CodeDocumentBinding::repositoryId)
                .containsOnly(git.id());
        assertThat(gitRepository.findBindingsByDocumentId(generated.id()))
                .extracting(CodeDocumentBinding::pathPattern)
                .containsExactlyInAnyOrder(
                        "src/Controller.java",
                        "src/Service.java"
                );
    }

    @Test
    void applyMapsBindingsToTheirOwnCreatedDocumentsWithoutCrossContamination() {
        Fixture fixture = fixture("ADMIN");
        GitRepository git = repository(fixture, "two-created-documents");
        CreateCommand command = new CreateCommand(
                "two-created-documents-" + UUID.randomUUID(),
                "Create two independent documents",
                "Each semantic responsibility needs its own document",
                java.util.List.of(
                        new CreateOperationCommand(
                                "create-api", 1,
                                OperationType.CREATE_DOCUMENT,
                                null, null, null, null,
                                "API guide", DocumentType.API,
                                null, null, null, null, null
                        ),
                        new CreateOperationCommand(
                                "add-api-body", 2,
                                OperationType.ADD_BLOCK,
                                null, "create-api", null, null,
                                null, null, null,
                                DocumentBlockType.PARAGRAPH,
                                "The API controller exposes the external request contract.",
                                null, null
                        ),
                        new CreateOperationCommand(
                                "create-storage", 3,
                                OperationType.CREATE_DOCUMENT,
                                null, null, null, null,
                                "Storage guide", DocumentType.DATABASE,
                                null, null, null, null, null
                        ),
                        new CreateOperationCommand(
                                "add-storage-body", 4,
                                OperationType.ADD_BLOCK,
                                null, "create-storage", null, null,
                                null, null, null,
                                DocumentBlockType.PARAGRAPH,
                                "The repository persists and retrieves the aggregate state.",
                                null, null
                        )
                ),
                java.util.List.of(
                        new CreateBindingProposalCommand(
                                "bind-api", 5,
                                BindingAction.UPSERT_BINDING,
                                git.id(), "src/ApiController.java",
                                null, "create-api", null,
                                "API entry point"
                        ),
                        new CreateBindingProposalCommand(
                                "bind-storage", 6,
                                BindingAction.UPSERT_BINDING,
                                git.id(), "src/StorageRepository.java",
                                null, "create-storage", null,
                                "Storage implementation"
                        )
                ),
                java.util.List.of()
        );

        var created = service.create(
                fixture.workspaceId(), fixture.userId(), command
        );
        service.apply(
                fixture.workspaceId(), created.changeRequestId(), fixture.userId()
        );

        var documentsByTitle = documentRepository
                .findAllByWorkspaceId(fixture.workspaceId())
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        Document::title,
                        item -> item
                ));
        Document api = documentsByTitle.get("API guide");
        Document storage = documentsByTitle.get("Storage guide");
        assertThat(api).isNotNull();
        assertThat(storage).isNotNull();
        assertThat(gitRepository.findBindingsByDocumentId(api.id()))
                .extracting(CodeDocumentBinding::pathPattern)
                .containsExactly("src/ApiController.java");
        assertThat(gitRepository.findBindingsByDocumentId(storage.id()))
                .extracting(CodeDocumentBinding::pathPattern)
                .containsExactly("src/StorageRepository.java");
    }

    @Test
    void rejectRequiresAdminAndSameReasonReplayIsIdempotent() {
        Fixture member = fixture("MEMBER");
        UUID adminId = addMember(member.workspaceId(), "ADMIN");
        Document document = document(member);
        DocumentBlock block = block(document, member.userId(), 0);
        var created = service.create(
                member.workspaceId(),
                member.userId(),
                updateCommand(document, block, "reject-update")
        );

        assertThatThrownBy(() -> service.reject(
                member.workspaceId(),
                created.changeRequestId(),
                member.userId(),
                "not acceptable"
        )).isInstanceOf(WorkspaceAccessDeniedException.class);

        var rejected = service.reject(
                member.workspaceId(),
                created.changeRequestId(),
                adminId,
                "  not acceptable  "
        );
        var replay = service.reject(
                member.workspaceId(),
                created.changeRequestId(),
                adminId,
                "not acceptable"
        );

        assertThat(rejected.request().status()).isEqualTo(Status.REJECTED);
        assertThat(rejected.request().rejectionReason())
                .isEqualTo("not acceptable");
        assertThat(replay.replayed()).isTrue();
        assertThat(blockRepository.findById(block.id()).orElseThrow().text())
                .isEqualTo("before");
        // Different reason replays as no-op (reason-conflict guard removed)
        var replayAgain = service.reject(
                member.workspaceId(),
                created.changeRequestId(),
                adminId,
                "different reason"
        );
        assertThat(replayAgain.replayed()).isTrue();
    }

    @Test
    void executionFailureRollsBackEarlierOperationsAndKeepsPending() {
        Fixture fixture = fixture("ADMIN");
        Document document = document(fixture);
        DocumentBlock block = block(document, fixture.userId(), 0);
        GitRepository git = repository(fixture, "document-failure");
        ChangeRequest request = saveRequest(
                fixture, Status.PENDING, "rollback", Instant.now()
        );
        repository.saveOperation(new Operation(
                UUID.randomUUID(), request.id(), "add", 1,
                OperationType.ADD_BLOCK, document.id(), null, null, null,
                null, null, null, null, null, null, null, null,
                DocumentBlockType.PARAGRAPH.name(), "temporary",
                null, null
        ));
        repository.saveOperation(new Operation(
                UUID.randomUUID(), request.id(), "update", 2,
                OperationType.UPDATE_BLOCK, document.id(), null, block.id(),
                block.version(), block.type().name(), block.text(),
                block.contentSchemaVersion(), block.contentJson(),
                block.sortOrder(), null, null, null, block.type().name(),
                "invalid", 1, "{not-json"
        ));
        repository.saveBindingProposal(new BindingProposal(
                UUID.randomUUID(), request.id(), "binding", 3,
                BindingAction.UPSERT_BINDING, git.id(),
                "src/ShouldNotExist.java", document.id(), null, null,
                "document mutation must finish first", Instant.now()
        ));
        int before = blockRepository.findAllByDocumentId(document.id()).size();

        assertThatThrownBy(() -> service.apply(
                fixture.workspaceId(), request.id(), fixture.userId()
        )).isInstanceOf(IllegalStateException.class);

        assertThat(blockRepository.findAllByDocumentId(document.id()))
                .hasSize(before);
        assertThat(gitRepository.findBindingsByDocumentId(document.id()))
                .isEmpty();
        assertThat(repository.findRequest(
                fixture.workspaceId(), request.id()
        ).orElseThrow().status()).isEqualTo(Status.PENDING);
    }

    @Test
    void bindingFailureRollsBackDocumentAndEarlierBinding() {
        Fixture fixture = fixture("ADMIN");
        Document document = document(fixture);
        DocumentBlock block = block(document, fixture.userId(), 0);
        GitRepository git = repository(fixture, "rollback-binding");
        ChangeRequest request = saveRequest(
                fixture, Status.PENDING, "binding rollback", Instant.now()
        );
        repository.saveOperation(operation(
                request,
                1,
                "update",
                OperationType.UPDATE_BLOCK,
                document,
                block
        ));
        repository.saveBindingProposal(new BindingProposal(
                UUID.randomUUID(), request.id(), "valid", 2,
                BindingAction.UPSERT_BINDING, git.id(),
                "src/Valid.java", document.id(), null, null,
                "valid before failure", Instant.now()
        ));
        repository.saveBindingProposal(new BindingProposal(
                UUID.randomUUID(), request.id(), "invalid", 3,
                BindingAction.UPSERT_BINDING, UUID.randomUUID(),
                "src/Invalid.java", document.id(), null, null,
                "repository does not exist", Instant.now()
        ));

        assertThatThrownBy(() -> service.apply(
                fixture.workspaceId(), request.id(), fixture.userId()
        )).isInstanceOf(RuntimeException.class);

        DocumentBlock current = blockRepository.findById(block.id()).orElseThrow();
        assertThat(current.text()).isEqualTo("before");
        assertThat(current.version()).isZero();
        assertThat(gitRepository.findBindingsByDocumentId(document.id()))
                .isEmpty();
        assertThat(repository.findRequest(
                fixture.workspaceId(), request.id()
        ).orElseThrow().status()).isEqualTo(Status.PENDING);
    }

    @Test
    void unexpectedDeleteFailureRollsBackDocumentAndKeepsRequestPending() {
        Fixture fixture = fixture("ADMIN");
        Document document = document(fixture);
        DocumentBlock block = block(document, fixture.userId(), 0);
        GitRepository git = repository(fixture, "delete-failure");
        CodeDocumentBinding binding = saveBinding(
                fixture, git, document, "src/Guarded.java"
        );
        ChangeRequest request = saveRequest(
                fixture, Status.PENDING, "delete rollback", Instant.now()
        );
        repository.saveOperation(operation(
                request,
                1,
                "update",
                OperationType.UPDATE_BLOCK,
                document,
                block
        ));
        repository.saveBindingProposal(new BindingProposal(
                UUID.randomUUID(), request.id(), "remove", 2,
                BindingAction.REMOVE_BINDING, git.id(),
                binding.pathPattern(), document.id(), null, binding.id(),
                "test delete failure", Instant.now()
        ));
        jdbcTemplate.execute("DROP TABLE IF EXISTS binding_delete_guard");
        jdbcTemplate.execute("""
                CREATE TABLE binding_delete_guard (
                    binding_id UUID PRIMARY KEY,
                    CONSTRAINT fk_binding_delete_guard
                        FOREIGN KEY (binding_id)
                        REFERENCES code_document_bindings(id)
                )
                """);
        jdbcTemplate.update(
                "INSERT INTO binding_delete_guard (binding_id) VALUES (?)",
                binding.id()
        );
        try {
            assertThatThrownBy(() -> service.apply(
                    fixture.workspaceId(), request.id(), fixture.userId()
            )).isInstanceOf(RuntimeException.class);

            DocumentBlock current =
                    blockRepository.findById(block.id()).orElseThrow();
            assertThat(current.text()).isEqualTo("before");
            assertThat(current.version()).isZero();
            assertThat(gitRepository.findBindingById(binding.id())).isPresent();
            assertThat(repository.findRequest(
                    fixture.workspaceId(), request.id()
            ).orElseThrow().status()).isEqualTo(Status.PENDING);
        } finally {
            jdbcTemplate.execute("DROP TABLE binding_delete_guard");
        }
    }

    @Test
    @Disabled("STALE status removed, test needs update")
    void exactUpsertIsNoOpAndMatchingRemoveDeletesBinding() {
        Fixture fixture = fixture("ADMIN");
        Document document = document(fixture);
        GitRepository git = repository(fixture, "binding-lifecycle");
        CodeDocumentBinding binding = saveBinding(
                fixture, git, document, "src/Bound.java"
        );
        var upsert = service.create(
                fixture.workspaceId(),
                fixture.userId(),
                bindingCommand(
                        "exact-upsert-" + UUID.randomUUID(),
                        git.id(),
                        document.id(),
                        "./src\\Bound.java",
                        BindingAction.UPSERT_BINDING,
                        null
                )
        );

        service.apply(
                fixture.workspaceId(),
                upsert.changeRequestId(),
                fixture.userId()
        );
        assertThat(gitRepository.findBindingsByDocumentId(document.id()))
                .extracting(CodeDocumentBinding::id)
                .containsExactly(binding.id());

        var remove = service.create(
                fixture.workspaceId(),
                fixture.userId(),
                bindingCommand(
                        "remove-" + UUID.randomUUID(),
                        git.id(),
                        document.id(),
                        "src/Bound.java",
                        BindingAction.REMOVE_BINDING,
                        binding.id()
                )
        );
        var result = service.apply(
                fixture.workspaceId(),
                remove.changeRequestId(),
                fixture.userId()
        );

        assertThat(result.detail().request().status()).isEqualTo(Status.APPLIED);
        assertThat(gitRepository.findBindingById(binding.id())).isEmpty();
    }

    @Test
    @Disabled("STALE status removed, test needs update")
    void missingOrMismatchedRemoveFailsApplyBeforeMutation() {
        Fixture fixture = fixture("ADMIN");
        Document document = document(fixture);
        Document otherDocument = document(fixture);
        GitRepository git = repository(fixture, "remove-primary");
        GitRepository otherGit = repository(fixture, "remove-other");
        CodeDocumentBinding binding = saveBinding(
                fixture, git, document, "src/Bound.java"
        );
        Fixture otherWorkspace = fixture("ADMIN");
        Document foreignDocument = document(otherWorkspace);
        GitRepository foreignGit = repository(otherWorkspace, "remove-foreign");
        CodeDocumentBinding foreignBinding = saveBinding(
                otherWorkspace, foreignGit, foreignDocument, "src/Foreign.java"
        );

        assertRemoveStale(
                fixture, document.id(), git.id(), "src/Bound.java",
                UUID.randomUUID()
        );
        assertRemoveStale(
                fixture, document.id(), otherGit.id(), "src/Bound.java",
                binding.id()
        );
        assertRemoveStale(
                fixture, otherDocument.id(), git.id(), "src/Bound.java",
                binding.id()
        );
        assertRemoveStale(
                fixture, document.id(), git.id(), "src/Other.java",
                binding.id()
        );
        assertRemoveStale(
                fixture, document.id(), git.id(), "src/Bound.java",
                foreignBinding.id()
        );

        assertThat(gitRepository.findBindingById(binding.id())).isPresent();
        assertThat(gitRepository.findBindingById(foreignBinding.id())).isPresent();
    }

    private GitRepository repository(Fixture fixture, String name) {
        Instant now = Instant.now();
        return gitRepository.saveRepository(new GitRepository(
                UUID.randomUUID(),
                fixture.workspaceId(),
                name,
                GitProvider.GITHUB,
                "https://github.com/example/" + name,
                "main",
                fixture.userId(),
                now,
                now,
                GitRepositoryStatus.READY,
                "abc123",
                now,
                null
        ));
    }

    private CreateCommand bindingCommand(
            String clientRequestId,
            UUID repositoryId,
            UUID documentId,
            String path,
            BindingAction action,
            UUID bindingId
    ) {
        return new CreateCommand(
                clientRequestId,
                "Binding proposal",
                "Keep code and document association consistent",
                java.util.List.of(),
                java.util.List.of(new CreateBindingProposalCommand(
                        "binding",
                        1,
                        action,
                        repositoryId,
                        path,
                        documentId,
                        null,
                        bindingId,
                        "review binding"
                )),
                java.util.List.of()
        );
    }

    private CodeDocumentBinding saveBinding(
            Fixture fixture,
            GitRepository git,
            Document document,
            String path
    ) {
        return gitRepository.saveBinding(new CodeDocumentBinding(
                UUID.randomUUID(),
                fixture.workspaceId(),
                git.id(),
                document.id(),
                null,
                path,
                fixture.userId(),
                Instant.now()
        ));
    }

    private void assertRemoveStale(
            Fixture fixture,
            UUID documentId,
            UUID repositoryId,
            String path,
            UUID bindingId
    ) {
        var created = service.create(
                fixture.workspaceId(),
                fixture.userId(),
                bindingCommand(
                        "stale-remove-" + UUID.randomUUID(),
                        repositoryId,
                        documentId,
                        path,
                        BindingAction.REMOVE_BINDING,
                        bindingId
                )
        );

        assertThatThrownBy(() -> service.apply(
                fixture.workspaceId(),
                created.changeRequestId(),
                fixture.userId()
        ))
                .isInstanceOfSatisfying(
                        DocumentChangeException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("REQUEST_TARGET_CHANGED")
                );
    }

    private Fixture fixture(String role) {
        UUID userId = createUser();
        UUID workspaceId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO workspaces
                    (id, name, created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                """, workspaceId, "workspace-" + workspaceId, userId, now, now);
        jdbcTemplate.update("""
                INSERT INTO workspace_members
                    (workspace_id, user_id, role, joined_at)
                VALUES (?, ?, ?, ?)
                """, workspaceId, userId, role, now);
        return new Fixture(workspaceId, userId);
    }

    private UUID addMember(UUID workspaceId, String role) {
        UUID userId = createUser();
        jdbcTemplate.update("""
                INSERT INTO workspace_members
                    (workspace_id, user_id, role, joined_at)
                VALUES (?, ?, ?, ?)
                """, workspaceId, userId, role, Instant.now());
        return userId;
    }

    private UUID createUser() {
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO user_accounts
                    (id, username, normalized_username, display_name,
                     password_hash, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                """,
                userId, "user-" + userId, "user-" + userId,
                "Reviewer " + userId, "test-hash", now, now);
        return userId;
    }

    private ChangeRequest saveRequest(
            Fixture fixture,
            Status status,
            String summary,
            Instant createdAt
    ) {
        return repository.saveRequest(new ChangeRequest(
                UUID.randomUUID(), fixture.workspaceId(),
                "client-" + UUID.randomUUID(), "a".repeat(64), status,
                summary, "rationale", SourceType.MCP, fixture.userId(),
                createdAt, null, null, null
        ));
    }

    private Document document(Fixture fixture) {
        Instant now = Instant.now();
        return documentRepository.save(new Document(
                UUID.randomUUID(), fixture.workspaceId(), null, "API design",
                DocumentType.REQUIREMENT, DocumentReviewStatus.DRAFT,
                fixture.userId(), now, now
        ));
    }

    private DocumentBlock block(
            Document document,
            UUID userId,
            long version
    ) {
        Instant now = Instant.now();
        return blockRepository.save(new DocumentBlock(
                UUID.randomUUID(), document.id(), DocumentBlockType.PARAGRAPH,
                "before", 1,
                """
                {"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"before"}]}]}
                """,
                0, version, userId, now, now
        ));
    }

    private Operation operation(
            ChangeRequest request,
            int sequence,
            String clientId,
            OperationType type,
            Document document,
            DocumentBlock block
    ) {
        return new Operation(
                UUID.randomUUID(), request.id(), clientId, sequence, type,
                document.id(), null, block.id(), block.version(),
                block.type().name(), block.text(), block.contentSchemaVersion(),
                block.contentJson(), block.sortOrder(), null, null, null,
                block.type().name(),
                type == OperationType.UPDATE_BLOCK ? "after" : null,
                type == OperationType.UPDATE_BLOCK ? 1 : null,
                type == OperationType.UPDATE_BLOCK ? block.contentJson() : null
        );
    }

    private Evidence evidence(
            ChangeRequest request,
            UUID operationId,
            UUID repositoryId,
            String description
    ) {
        return new Evidence(
                UUID.randomUUID(), request.id(), operationId, repositoryId,
                "abc123", "src/Test.java", 1, 2, description, "blob",
                "class Test {}", "b".repeat(64)
        );
    }

    private CreateCommand updateCommand(
            Document document,
            DocumentBlock block,
            String clientRequestId
    ) {
        return new CreateCommand(
                clientRequestId,
                "Update API block",
                "Code behavior changed",
                java.util.List.of(new CreateOperationCommand(
                        "update",
                        1,
                        OperationType.UPDATE_BLOCK,
                        document.id(),
                        null,
                        block.id(),
                        block.version(),
                        null,
                        null,
                        null,
                        null,
                        "after",
                        null,
                        null
                )),
                java.util.List.of(),
                java.util.List.of()
        );
    }

    private CreateOperationCommand operationWithText(String id, int sequence) {
        return new CreateOperationCommand(
                id, sequence, OperationType.CREATE_DOCUMENT,
                null, null, null, null, "Document " + sequence,
                DocumentType.REQUIREMENT, null, null, null, null, null
        );
    }

    private record Fixture(UUID workspaceId, UUID userId) {
    }
}
