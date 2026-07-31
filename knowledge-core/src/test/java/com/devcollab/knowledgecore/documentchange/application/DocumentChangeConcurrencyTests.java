package com.devcollab.knowledgecore.documentchange.application;

import com.devcollab.knowledgecore.document.block.application.DocumentBlockContentFormat;
import com.devcollab.knowledgecore.document.core.domain.DocumentBlockType;
import com.devcollab.knowledgecore.documentchange.domain.DocumentChangeModel.Status;
import com.devcollab.knowledgecore.documentchange.domain.DocumentChangeRepository;
import com.devcollab.knowledgecore.documentchange.application.DocumentChangeApplicationService.CreateCommand;
import com.devcollab.knowledgecore.documentchange.application.DocumentChangeApplicationService.CreateResult;
import com.devcollab.knowledgecore.documentchange.application.DocumentChangeApplicationService.CreateBindingProposalCommand;
import com.devcollab.knowledgecore.documentchange.application.DocumentChangeApplicationService.CreateOperationCommand;
import com.devcollab.knowledgecore.documentchange.application.DocumentChangeApplicationService.DecisionResult;
import com.devcollab.knowledgecore.documentchange.domain.DocumentChangeModel.BindingAction;
import com.devcollab.knowledgecore.documentchange.domain.DocumentChangeModel.OperationType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class DocumentChangeConcurrencyTests {

    @Autowired
    private DocumentChangeApplicationService service;

    @Autowired
    private DocumentChangeRepository repository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    // Helper for generating UUID
    private UUID randomUUID() { return UUID.randomUUID(); }

    private record TestContext(
            UUID workspaceId,
            UUID userId,
            UUID documentId,
            UUID blockId,
            UUID repositoryId
    ) {}

    private TestContext setupMemberAndDocument(UUID workspaceId, UUID userId) {
        java.time.Instant now = java.time.Instant.now();
        jdbcTemplate.update("""
                INSERT INTO user_accounts (id, username, normalized_username, display_name, password_hash, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'hash', 'ACTIVE', ?, ?)
                """, userId, "user-" + userId.toString().substring(0, 5), "user-" + userId.toString().substring(0, 5), "User", now, now);
        jdbcTemplate.update("""
                INSERT INTO workspaces (id, name, created_by, created_at, updated_at)
                VALUES (?, 'ws', ?, ?, ?)
                """, workspaceId, userId, now, now);
        jdbcTemplate.update("""
                INSERT INTO workspace_members (workspace_id, user_id, role, joined_at)
                VALUES (?, ?, 'ADMIN', ?)
                """, workspaceId, userId, now);
        UUID documentId = randomUUID();
        jdbcTemplate.update("""
                INSERT INTO documents (id, workspace_id, title, document_type, review_status, created_by, created_at, updated_at)
                VALUES (?, ?, 'Doc', 'REQUIREMENT', 'DRAFT', ?, ?, ?)
                """, documentId, workspaceId, userId, now, now);
        UUID blockId = randomUUID();
        jdbcTemplate.update("""
                INSERT INTO document_blocks
                    (id, document_id, type, text, content_schema_version,
                     content_json, sort_order, version, created_by,
                     created_at, updated_at)
                VALUES (?, ?, 'PARAGRAPH', 'before', 1,
                        '{"type":"doc","content":[]}', 0, 0, ?, ?, ?)
                """, blockId, documentId, userId, now, now);
        UUID repositoryId = randomUUID();
        jdbcTemplate.update("""
                INSERT INTO git_repositories (id, workspace_id, name, provider, remote_url, default_branch, sync_status, created_by, created_at, updated_at)
                VALUES (?, ?, 'Repo', 'GITHUB', 'http', 'main', 'READY', ?, ?, ?)
                """, repositoryId, workspaceId, userId, now, now);
        return new TestContext(
                workspaceId, userId, documentId, blockId, repositoryId
        );
    }

    @Test
    public void testConcurrentSubmitWithSameClientRequestId() throws InterruptedException {
        UUID workspaceId = randomUUID();
        UUID userId = randomUUID();
        TestContext ctx = setupMemberAndDocument(workspaceId, userId);
        String clientRequestId = "concur-" + randomUUID();
        
        CreateCommand command = new CreateCommand(
                clientRequestId, "Summary", "Rationale",
                List.of(),
                List.of(new CreateBindingProposalCommand(
                        "bp-1", 1, BindingAction.UPSERT_BINDING,
                        ctx.repositoryId(), "test/path.txt", ctx.documentId(), null, null, "Reason"
                )),
                List.of()
        );

        int threads = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger replayCount = new AtomicInteger(0);
        AtomicReference<Exception> error = new AtomicReference<>();

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    CreateResult result = service.create(workspaceId, userId, command);
                    if (result.idempotentReplay()) {
                        replayCount.incrementAndGet();
                    } else {
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    error.compareAndSet(null, e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        latch.countDown();
        assertThat(doneLatch.await(20, TimeUnit.SECONDS)).isTrue();
        executor.shutdownNow();

        assertThat(error.get()).isNull();
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(replayCount.get()).isEqualTo(threads - 1);
        
        // Assert conflict on changed payload
        CreateCommand changedCommand = new CreateCommand(
                clientRequestId, "Summary", "Changed Rationale",
                List.of(),
                List.of(new CreateBindingProposalCommand(
                        "bp-1", 1, BindingAction.UPSERT_BINDING,
                        ctx.repositoryId(), "test/path.txt", ctx.documentId(), null, null, "Reason"
                )),
                List.of()
        );
        assertThatThrownBy(() -> service.create(workspaceId, userId, changedCommand))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("clientRequestId");
    }

    @Test
    void concurrentApplyApplyMutatesDocumentAndBindingOnlyOnce() throws Exception {
        TestContext context = setupMemberAndDocument(randomUUID(), randomUUID());
        CreateResult created = service.create(
                context.workspaceId(),
                context.userId(),
                updateAndBindCommand(context, "apply-apply-" + randomUUID())
        );

        List<Outcome> outcomes = race(
                () -> service.apply(
                        context.workspaceId(),
                        created.changeRequestId(),
                        context.userId()
                ),
                () -> service.apply(
                        context.workspaceId(),
                        created.changeRequestId(),
                        context.userId()
                )
        );

        assertNoInfrastructureFailure(outcomes);
        assertThat(outcomes.stream().filter(Outcome::succeeded).count())
                .isGreaterThanOrEqualTo(1);
        assertThat(repository.findRequest(
                context.workspaceId(),
                created.changeRequestId()
        ).orElseThrow().status()).isEqualTo(Status.APPLIED);
        assertThat(blockText(context.blockId())).isEqualTo("after");
        assertThat(blockVersion(context.blockId())).isEqualTo(1L);
        assertThat(bindingCount(context)).isEqualTo(1);
        assertThat(appliedLogCount(created.changeRequestId())).isEqualTo(1);
    }

    @Test
    void concurrentApplyRejectHasOneDurableDecisionAndNoMixedState()
            throws Exception {
        TestContext context = setupMemberAndDocument(randomUUID(), randomUUID());
        CreateResult created = service.create(
                context.workspaceId(),
                context.userId(),
                updateAndBindCommand(context, "apply-reject-" + randomUUID())
        );

        List<Outcome> outcomes = race(
                () -> service.apply(
                        context.workspaceId(),
                        created.changeRequestId(),
                        context.userId()
                ),
                () -> {
                    service.reject(
                            context.workspaceId(),
                            created.changeRequestId(),
                            context.userId(),
                            "concurrent rejection"
                    );
                    return null;
                }
        );

        assertNoInfrastructureFailure(outcomes);
        assertThat(outcomes.stream().filter(Outcome::succeeded).count())
                .isEqualTo(1);
        var request = repository.findRequest(
                context.workspaceId(),
                created.changeRequestId()
        ).orElseThrow();
        assertThat(request.status()).isIn(Status.APPLIED, Status.REJECTED);
        assertThat(request.reviewedBy()).isEqualTo(context.userId());
        assertThat(request.reviewedAt()).isNotNull();

        if (request.status() == Status.APPLIED) {
            assertThat(blockText(context.blockId())).isEqualTo("after");
            assertThat(blockVersion(context.blockId())).isEqualTo(1L);
            assertThat(bindingCount(context)).isEqualTo(1);
            assertThat(request.rejectionReason()).isNull();
        } else {
            assertThat(blockText(context.blockId())).isEqualTo("before");
            assertThat(blockVersion(context.blockId())).isZero();
            assertThat(bindingCount(context)).isZero();
            assertThat(request.rejectionReason())
                    .isEqualTo("concurrent rejection");
        }
    }

    @Test
    void addAndUpdateApplyMarkdownWithTheSameStructuredContentSemantics() {
        TestContext context = setupMemberAndDocument(randomUUID(), randomUUID());
        String markdown = """
                ## 文档上下文

                `DocumentBlock` 负责保存一个内容块。

                - 保留排序
                - 保留版本
                """;
        CreateResult created = service.create(
                context.workspaceId(),
                context.userId(),
                new CreateCommand(
                        "markdown-format-" + randomUUID(),
                        "Apply Markdown",
                        "ADD_BLOCK 与 UPDATE_BLOCK 使用同一格式边界",
                        List.of(
                                new CreateOperationCommand(
                                        "update-markdown",
                                        1,
                                        OperationType.UPDATE_BLOCK,
                                        context.documentId(),
                                        null,
                                        context.blockId(),
                                        0L,
                                        null,
                                        null,
                                        null,
                                        null,
                                        markdown,
                                        DocumentBlockContentFormat.MARKDOWN,
                                        null,
                                        null
                                ),
                                new CreateOperationCommand(
                                        "add-markdown",
                                        2,
                                        OperationType.ADD_BLOCK,
                                        context.documentId(),
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        DocumentBlockType.PARAGRAPH,
                                        markdown,
                                        DocumentBlockContentFormat.MARKDOWN,
                                        null,
                                        null
                                )
                        ),
                        List.of(),
                        List.of()
                )
        );

        service.apply(
                context.workspaceId(),
                created.changeRequestId(),
                context.userId()
        );

        List<String> stored = jdbcTemplate.queryForList(
                """
                SELECT content_json
                  FROM document_blocks
                 WHERE document_id = ?
                 ORDER BY sort_order
                """,
                String.class,
                context.documentId()
        );
        assertThat(stored).hasSize(2);
        assertThat(stored).allSatisfy(content -> {
            assertThat(content).contains("\"type\":\"heading\"");
            assertThat(content).contains("\"type\":\"bulletList\"");
            assertThat(content).contains("\"type\":\"code\"");
            assertThat(content).doesNotContain("## 文档上下文");
        });
    }

    private List<Outcome> race(
            Callable<DecisionResult> first,
            Callable<DecisionResult> second
    ) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            Future<Outcome> firstFuture = executor.submit(
                    () -> invokeAfterBarrier(barrier, first)
            );
            Future<Outcome> secondFuture = executor.submit(
                    () -> invokeAfterBarrier(barrier, second)
            );
            return List.of(
                    firstFuture.get(20, TimeUnit.SECONDS),
                    secondFuture.get(20, TimeUnit.SECONDS)
            );
        } finally {
            executor.shutdownNow();
        }
    }

    private Outcome invokeAfterBarrier(
            CyclicBarrier barrier,
            Callable<DecisionResult> action
    ) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
            return new Outcome(action.call(), null);
        } catch (Throwable throwable) {
            return new Outcome(null, throwable);
        }
    }

    private void assertNoInfrastructureFailure(List<Outcome> outcomes) {
        assertThat(outcomes)
                .filteredOn(outcome -> outcome.error() != null)
                .allSatisfy(outcome -> {
                    assertThat(outcome.error())
                            .isInstanceOf(DocumentChangeException.class);
                    assertThat(rootCause(outcome.error()))
                            .isNotInstanceOf(
                                    org.springframework.dao.DataAccessException.class
                            );
                });
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private CreateCommand updateAndBindCommand(
            TestContext context,
            String clientRequestId
    ) {
        return new CreateCommand(
                clientRequestId,
                "Update and bind",
                "Code behavior changed",
                List.of(new CreateOperationCommand(
                        "update",
                        1,
                        OperationType.UPDATE_BLOCK,
                        context.documentId(),
                        null,
                        context.blockId(),
                        0L,
                        null,
                        null,
                        null,
                        null,
                        "after",
                        null,
                        null
                )),
                List.of(new CreateBindingProposalCommand(
                        "binding",
                        2,
                        BindingAction.UPSERT_BINDING,
                        context.repositoryId(),
                        "./src\\main/Example.java",
                        context.documentId(),
                        null,
                        null,
                        "Keep code and document linked"
                )),
                List.of()
        );
    }

    private String blockText(UUID blockId) {
        return jdbcTemplate.queryForObject(
                "SELECT text FROM document_blocks WHERE id = ?",
                String.class,
                blockId
        );
    }

    private long blockVersion(UUID blockId) {
        return jdbcTemplate.queryForObject(
                "SELECT version FROM document_blocks WHERE id = ?",
                Long.class,
                blockId
        );
    }

    private int bindingCount(TestContext context) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM code_document_bindings
                 WHERE repository_id = ?
                   AND document_id = ?
                   AND path_pattern = 'src/main/Example.java'
                """, Integer.class, context.repositoryId(), context.documentId());
    }

    private int appliedLogCount(UUID requestId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM operation_logs
                 WHERE target_type = 'DOCUMENT_CHANGE_REQUEST'
                   AND target_id = ?
                   AND action = 'DOCUMENT_CHANGE_APPLIED'
                """, Integer.class, requestId);
    }

    private record Outcome(DecisionResult result, Throwable error) {
        boolean succeeded() {
            return error == null;
        }
    }
}
