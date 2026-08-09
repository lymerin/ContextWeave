package com.devcollab.knowledgecore.git.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.devcollab.knowledgecore.document.core.domain.DocumentBlockRepository;
import com.devcollab.knowledgecore.documentchange.application.DocumentChangeApplicationService;
import com.devcollab.knowledgecore.documentchange.application.DocumentChangeApplicationService.CreateResult;
import com.devcollab.knowledgecore.documentchange.domain.DocumentChangeModel.Status;
import com.devcollab.knowledgecore.git.domain.BindingRole;
import com.devcollab.knowledgecore.git.domain.CodeAnchorKind;
import com.devcollab.knowledgecore.git.domain.CodeDocumentBinding;
import com.devcollab.knowledgecore.git.domain.CodeSymbol;
import com.devcollab.knowledgecore.git.domain.GitKnowledgeRepository;
import com.devcollab.knowledgecore.git.domain.GitRepositoryFile;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DriftDetectionService}.
 */
class DriftDetectionServiceTest {

    private GitKnowledgeRepository gitRepository;
    private DocumentChangeApplicationService documentChangeService;
    private DocumentBlockRepository blockRepository;
    private DriftDetectionService service;

    private static final UUID WORKSPACE_ID = UUID.randomUUID();
    private static final UUID REPO_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID DOC_ID = UUID.randomUUID();
    private static final UUID BLOCK_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        gitRepository = mock(GitKnowledgeRepository.class);
        documentChangeService = mock(DocumentChangeApplicationService.class);
        blockRepository = mock(DocumentBlockRepository.class);
        service = new DriftDetectionService(gitRepository, documentChangeService, blockRepository);
    }

    /**
     * Stub create() to return a result, and stub apply() for tests
     * that expect a change request to be submitted.
     */
    private void stubCreateResult(UUID requestId, Status status) {
        when(documentChangeService.create(any(), any(), any()))
                .thenReturn(new CreateResult(requestId, status, Instant.now(), false));
    }

    // ── symbolKey parsing ──────────────────────────────────────────────────

    @Test
    void symbolKeyFilePath_standard() {
        assertThat(DriftDetectionService.symbolKeyFilePath(
                "PYTHON:src/service.py:Foo.bar:METHOD"))
                .isEqualTo("src/service.py");
    }

    @Test
    void symbolKeyFilePath_simple() {
        assertThat(DriftDetectionService.symbolKeyFilePath("LANG:path"))
                .isEqualTo("path");
    }

    @Test
    void symbolKeyFilePath_empty() {
        assertThat(DriftDetectionService.symbolKeyFilePath(""))
                .isEmpty();
        assertThat(DriftDetectionService.symbolKeyFilePath(null))
                .isEmpty();
    }

    @Test
    void symbolKeyQualifiedName_standard() {
        assertThat(DriftDetectionService.symbolKeyQualifiedName(
                "PYTHON:src/service.py:Foo.bar:METHOD"))
                .isEqualTo("Foo.bar");
    }

    @Test
    void symbolKeyQualifiedName_simple() {
        assertThat(DriftDetectionService.symbolKeyQualifiedName(
                "PYTHON:p/main.py:main:FUNCTION"))
                .isEqualTo("main");
    }

    @Test
    void symbolKeyQualifiedName_null() {
        assertThat(DriftDetectionService.symbolKeyQualifiedName(null))
                .isNull();
        assertThat(DriftDetectionService.symbolKeyQualifiedName(""))
                .isNull();
    }

    // ── No-op cases ────────────────────────────────────────────────────────

    @Test
    void noBindings_skips() {
        when(gitRepository.findBindingsByRepositoryId(REPO_ID))
                .thenReturn(Collections.emptyList());

        service.detectAndSubmit(WORKSPACE_ID, REPO_ID, "abc1234", USER_ID);

        verify(documentChangeService, org.mockito.Mockito.never())
                .create(any(), any(), any());
    }

    @Test
    void noSymbols_skips() {
        when(gitRepository.findBindingsByRepositoryId(REPO_ID))
                .thenReturn(List.of(makeBinding("PYTHON:src/a.py:Foo.bar:METHOD")));
        when(gitRepository.findSymbolsByRepositoryId(REPO_ID, null))
                .thenReturn(Collections.emptyList());

        service.detectAndSubmit(WORKSPACE_ID, REPO_ID, "abc1234", USER_ID);

        verify(documentChangeService, org.mockito.Mockito.never())
                .create(any(), any(), any());
    }

    // ── NONE (no drift) ────────────────────────────────────────────────────

    @Test
    void bindingMatchesSymbol_noDrift() {
        var symbol = makeSymbol(
                "PYTHON:src/a.py:Foo.bar:METHOD", "Foo.bar",
                "def bar(self) -> str", 10, 20);
        var binding = makeBinding("PYTHON:src/a.py:Foo.bar:METHOD");

        when(gitRepository.findBindingsByRepositoryId(REPO_ID))
                .thenReturn(List.of(binding));
        when(gitRepository.findSymbolsByRepositoryId(REPO_ID, null))
                .thenReturn(List.of(symbol));
        when(gitRepository.findFilesByRepositoryId(REPO_ID))
                .thenReturn(List.of(makeFile("src/a.py")));

        service.detectAndSubmit(WORKSPACE_ID, REPO_ID, "abc1234", USER_ID);

        // No drift → no submission
        verify(documentChangeService, org.mockito.Mockito.never())
                .create(any(), any(), any());
    }

    // ── COSMETIC ───────────────────────────────────────────────────────────

    @Test
    void lineShiftOnly_cosmeticDrift() {
        var symbol = makeSymbol(
                "PYTHON:src/a.py:Foo.bar:METHOD", "Foo.bar",
                "def bar(self) -> str", 15, 25);
        var binding = makeBinding("PYTHON:src/a.py:Foo.bar:METHOD");

        when(gitRepository.findBindingsByRepositoryId(REPO_ID))
                .thenReturn(List.of(binding));
        when(gitRepository.findSymbolsByRepositoryId(REPO_ID, null))
                .thenReturn(List.of(symbol));
        when(gitRepository.findFilesByRepositoryId(REPO_ID))
                .thenReturn(List.of(makeFile("src/a.py")));
        stubCreateResult(UUID.randomUUID(), Status.APPLIED);

        service.detectAndSubmit(WORKSPACE_ID, REPO_ID, "abc1234", USER_ID);

        verify(documentChangeService).create(
                eq(WORKSPACE_ID), eq(USER_ID), any());
    }

    // ── SYMBOL_MOVED ──────────────────────────────────────────────────────

    @Test
    void symbolMovedToDifferentFile_driftDetected() {
        // Binding points to symbol in src/a.py, but current symbol is in src/b.py
        var symbol = makeSymbol(
                "PYTHON:src/b.py:Foo.bar:METHOD", "Foo.bar",
                "def bar(self) -> str", 10, 20);
        // QualifiedName lookup from binding's symbolKey resolves to the moved symbol
        var binding = makeBinding("PYTHON:src/a.py:Foo.bar:METHOD");

        when(gitRepository.findBindingsByRepositoryId(REPO_ID))
                .thenReturn(List.of(binding));
        when(gitRepository.findSymbolsByRepositoryId(REPO_ID, null))
                .thenReturn(List.of(symbol));
        when(gitRepository.findFilesByRepositoryId(REPO_ID))
                .thenReturn(List.of(makeFile("src/a.py"), makeFile("src/b.py")));
        stubCreateResult(UUID.randomUUID(), Status.APPLIED);

        service.detectAndSubmit(WORKSPACE_ID, REPO_ID, "abc1234", USER_ID);

        verify(documentChangeService).create(
                eq(WORKSPACE_ID), eq(USER_ID), any());
    }

    // ── SYMBOL_REMOVED ─────────────────────────────────────────────────────

    @Test
    void symbolRemoved_bindingBroken() {
        // The bound symbol is gone, but other symbols and the file still exist
        var binding = makeBinding("PYTHON:src/a.py:Foo.bar:METHOD");
        var otherSymbol = makeSymbol(
                "PYTHON:src/b.py:Other.run:FUNCTION", "Other.run",
                "def run(self) -> None", 5, 10);

        when(gitRepository.findBindingsByRepositoryId(REPO_ID))
                .thenReturn(List.of(binding));
        when(gitRepository.findSymbolsByRepositoryId(REPO_ID, null))
                .thenReturn(List.of(otherSymbol));
        when(gitRepository.findFilesByRepositoryId(REPO_ID))
                .thenReturn(List.of(makeFile("src/a.py"), makeFile("src/b.py")));
        stubCreateResult(UUID.randomUUID(), Status.APPLIED);

        service.detectAndSubmit(WORKSPACE_ID, REPO_ID, "abc1234", USER_ID);

        verify(documentChangeService).create(
                eq(WORKSPACE_ID), eq(USER_ID), any());
    }

    // ── FILE_REMOVED ───────────────────────────────────────────────────────

    @Test
    void fileRemoved_bindingBroken() {
        // The bound file is gone, but other files and symbols still exist
        var binding = makeBinding("PYTHON:src/deleted.py:Foo.bar:METHOD");
        var otherSymbol = makeSymbol(
                "PYTHON:src/a.py:Other.run:FUNCTION", "Other.run",
                "def run(self) -> None", 5, 10);

        when(gitRepository.findBindingsByRepositoryId(REPO_ID))
                .thenReturn(List.of(binding));
        when(gitRepository.findSymbolsByRepositoryId(REPO_ID, null))
                .thenReturn(List.of(otherSymbol));
        when(gitRepository.findFilesByRepositoryId(REPO_ID))
                .thenReturn(List.of(makeFile("src/a.py")));  // other files exist
        stubCreateResult(UUID.randomUUID(), Status.APPLIED);

        service.detectAndSubmit(WORKSPACE_ID, REPO_ID, "abc1234", USER_ID);

        verify(documentChangeService).create(
                eq(WORKSPACE_ID), eq(USER_ID), any());
    }

    // ── Legacy (no symbolKey) ──────────────────────────────────────────────

    @Test
    void legacyBindingNoSymbolKey_conservativeNoDrift() {
        var binding = new CodeDocumentBinding(
                UUID.randomUUID(), WORKSPACE_ID, REPO_ID, DOC_ID, null,
                "DOCUMENT", "src/stale.py", null,
                CodeAnchorKind.FILE, null, null, null,
                USER_ID, Instant.now());

        when(gitRepository.findBindingsByRepositoryId(REPO_ID))
                .thenReturn(List.of(binding));
        when(gitRepository.findSymbolsByRepositoryId(REPO_ID, null))
                .thenReturn(List.of());
        when(gitRepository.findFilesByRepositoryId(REPO_ID))
                .thenReturn(List.of(makeFile("src/stale.py")));

        service.detectAndSubmit(WORKSPACE_ID, REPO_ID, "abc1234", USER_ID);

        // Legacy binding without symbolKey → conservative, no drift reported
        verify(documentChangeService, org.mockito.Mockito.never())
                .create(any(), any(), any());
    }

    // ── symbol_key 格式不兼容防护（回归测试） ───────────────────────────────

    @Test
    void incompatibleSymbolFormat_neverDeletesBindings() {
        // 回归测试: 同步后 code_symbols 表可能由 JavaParser 重建为 "java:..." 格式，
        // 而 binding 是 agent 生成的 "PYTHON:..." 格式。此时绝不能把绑定
        // 判为 SYMBOL_REMOVED 并删除——那会丢失全部文档绑定。
        var binding = makeBinding("PYTHON:src/a.py:Foo.bar:METHOD");
        var javaSymbol = makeSymbol(
                "java:com.devcollab.Foo@a1b2c3d4e5f60718", "com.devcollab.Foo",
                "class Foo", 10, 30);

        when(gitRepository.findBindingsByRepositoryId(REPO_ID))
                .thenReturn(List.of(binding));
        when(gitRepository.findSymbolsByRepositoryId(REPO_ID, null))
                .thenReturn(List.of(javaSymbol));
        when(gitRepository.findFilesByRepositoryId(REPO_ID))
                .thenReturn(List.of(makeFile("src/a.py")));

        service.detectAndSubmit(WORKSPACE_ID, REPO_ID, "abc1234", USER_ID);

        // 格式不兼容 → 整体跳过，不提交任何 change request，绑定保持不动
        verify(documentChangeService, org.mockito.Mockito.never())
                .create(any(), any(), any());
        // 任何情况下都不应该执行 deleteBinding / REMOVE
        verify(gitRepository, org.mockito.Mockito.never())
                .deleteBinding(any());
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private CodeSymbol makeSymbol(
            String symbolKey, String qualifiedName,
            String signature, int startLine, int endLine) {
        return new CodeSymbol(
                UUID.randomUUID(), REPO_ID,
                DriftDetectionService.symbolKeyFilePath(symbolKey),
                symbolKey, "Python", "METHOD", qualifiedName,
                qualifiedName.contains(".") && qualifiedName.lastIndexOf('.') > 0
                        ? qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1)
                        : qualifiedName,
                signature, null, startLine, endLine);
    }

    private CodeDocumentBinding makeBinding(String symbolKey) {
        return new CodeDocumentBinding(
                UUID.randomUUID(), WORKSPACE_ID, REPO_ID, DOC_ID, BLOCK_ID,
                BLOCK_ID.toString(),
                DriftDetectionService.symbolKeyFilePath(symbolKey),
                "old-rev-123",
                CodeAnchorKind.SYMBOL, symbolKey, 10, 20,
                USER_ID, Instant.now());
    }

    private GitRepositoryFile makeFile(String path) {
        return new GitRepositoryFile(
                UUID.randomUUID(), REPO_ID, path, "abc123",
                1000L, "Python", "def foo(): pass");
    }
}
