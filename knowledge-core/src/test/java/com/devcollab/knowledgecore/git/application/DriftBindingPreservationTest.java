package com.devcollab.knowledgecore.git.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.devcollab.knowledgecore.document.core.domain.DocumentBlockRepository;
import com.devcollab.knowledgecore.documentchange.application.DocumentChangeApplicationService;
import com.devcollab.knowledgecore.documentchange.application.DocumentChangeApplicationService.CreateBindingProposalCommand;
import com.devcollab.knowledgecore.documentchange.application.DocumentChangeApplicationService.CreateCommand;
import com.devcollab.knowledgecore.documentchange.application.DocumentChangeApplicationService.CreateResult;
import com.devcollab.knowledgecore.documentchange.domain.DocumentChangeModel.BindingAction;
import com.devcollab.knowledgecore.documentchange.domain.DocumentChangeModel.Status;
import com.devcollab.knowledgecore.git.domain.CodeAnchorKind;
import com.devcollab.knowledgecore.git.domain.CodeDocumentBinding;
import com.devcollab.knowledgecore.git.domain.CodeSymbol;
import com.devcollab.knowledgecore.git.domain.GitKnowledgeRepository;
import com.devcollab.knowledgecore.git.domain.GitRepositoryFile;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 回归测试（施工计划 1.1）：漂移自愈的 proposal 必须覆盖目标上全部绑定，
 * 未漂移的绑定也要生成保留项（引用现有 bindingId），否则应用阶段
 * "先 wipe 再插回"会静默删除同目标上未漂移的合法绑定。
 *
 * 缺陷背景：DriftDetectionService 只对漂移的绑定生成 proposal（NONE 直接
 * continue），而 apply 对 proposal 触碰的 target 先 removeBindingsForTarget
 * 整删再插回 → 同 block 上未漂移的绑定被永久删除，且无日志。
 *
 * 修复后契约（本测试断言）：
 *   1. 漂移绑定 → UPSERT_BINDING（新位置）或 REMOVE_BINDING（符号/文件消失）
 *   2. 未漂移绑定 → 保留项，且 bindingId == 现有绑定 id（wipe 重建不丢身份）
 * 修复前：proposals 只有 1 条 → hasSize(2) 失败（红）。
 */
class DriftBindingPreservationTest {

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

    private void stubCreateResult(UUID requestId, Status status) {
        when(documentChangeService.create(any(), any(), any()))
                .thenReturn(new CreateResult(requestId, status, Instant.now(), false));
    }

    private List<CreateBindingProposalCommand> captureProposals() {
        ArgumentCaptor<CreateCommand> captor = ArgumentCaptor.forClass(CreateCommand.class);
        verify(documentChangeService).create(eq(WORKSPACE_ID), eq(USER_ID), captor.capture());
        return captor.getValue().bindingProposals();
    }

    @Test
    void movedAndHealthyBindingOnSameBlock_bothGetProposals() {
        // 同一 block 绑定两个 symbol：bar 移动（SYMBOL_MOVED），baz 未漂移
        var moved = makeBinding("PYTHON:src/a.py:Foo.bar:METHOD");
        var healthy = makeBinding("PYTHON:src/a.py:Foo.baz:METHOD");
        var movedSymbol = makeSymbol(
                "PYTHON:src/b.py:Foo.bar:METHOD", "Foo.bar",
                "def bar(self) -> str", 10, 20);
        var healthySymbol = makeSymbol(
                "PYTHON:src/a.py:Foo.baz:METHOD", "Foo.baz",
                "def baz(self) -> None", 30, 40);

        when(gitRepository.findBindingsByRepositoryId(REPO_ID))
                .thenReturn(List.of(moved, healthy));
        when(gitRepository.findSymbolsByRepositoryId(REPO_ID, null))
                .thenReturn(List.of(movedSymbol, healthySymbol));
        when(gitRepository.findFilesByRepositoryId(REPO_ID))
                .thenReturn(List.of(makeFile("src/a.py"), makeFile("src/b.py")));
        stubCreateResult(UUID.randomUUID(), Status.APPLIED);

        service.detectAndSubmit(WORKSPACE_ID, REPO_ID, "abc1234", USER_ID);

        var proposals = captureProposals();
        // 修复前：只有 moved 生成 proposal（1 条）→ apply 时 healthy 被 wipe 掉
        assertThat(proposals).hasSize(2);

        // 漂移绑定 → UPSERT 到新位置
        assertThat(proposals).anySatisfy(p -> {
            assertThat(p.action()).isEqualTo(BindingAction.UPSERT_BINDING);
            assertThat(p.symbolKey()).isEqualTo("PYTHON:src/b.py:Foo.bar:METHOD");
            assertThat(p.documentId()).isEqualTo(DOC_ID);
            assertThat(p.blockId()).isEqualTo(BLOCK_ID);
        });

        // 未漂移绑定 → 保留项必须引用现有绑定 id（不能是 null/新 id）
        assertThat(proposals).anySatisfy(p ->
                assertThat(p.bindingId()).isEqualTo(healthy.id()));
    }

    @Test
    void brokenAndHealthyBindingOnSameBlock_bothGetProposals() {
        // 同一 block：bar 所在文件已删除（FILE_REMOVED），baz 未漂移
        var broken = makeBinding("PYTHON:src/gone.py:Foo.bar:METHOD");
        var healthy = makeBinding("PYTHON:src/a.py:Foo.baz:METHOD");
        var healthySymbol = makeSymbol(
                "PYTHON:src/a.py:Foo.baz:METHOD", "Foo.baz",
                "def baz(self) -> None", 30, 40);

        when(gitRepository.findBindingsByRepositoryId(REPO_ID))
                .thenReturn(List.of(broken, healthy));
        when(gitRepository.findSymbolsByRepositoryId(REPO_ID, null))
                .thenReturn(List.of(healthySymbol));
        when(gitRepository.findFilesByRepositoryId(REPO_ID))
                .thenReturn(List.of(makeFile("src/a.py")));
        stubCreateResult(UUID.randomUUID(), Status.APPLIED);

        service.detectAndSubmit(WORKSPACE_ID, REPO_ID, "abc1234", USER_ID);

        var proposals = captureProposals();
        assertThat(proposals).hasSize(2);

        // 断裂绑定 → REMOVE（且绑定 id 明确）
        assertThat(proposals).anySatisfy(p -> {
            assertThat(p.action()).isEqualTo(BindingAction.REMOVE_BINDING);
            assertThat(p.bindingId()).isEqualTo(broken.id());
        });

        // 未漂移绑定 → 保留项引用现有绑定 id
        assertThat(proposals).anySatisfy(p ->
                assertThat(p.bindingId()).isEqualTo(healthy.id()));
    }

    // ── Helpers（与 DriftDetectionServiceTest 同源） ────────────────────────

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
        // 与 DriftDetectionServiceTest.makeBinding 一致：同一 DOC_ID + BLOCK_ID
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
