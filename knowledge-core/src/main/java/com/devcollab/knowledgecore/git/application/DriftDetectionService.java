package com.devcollab.knowledgecore.git.application;

import com.devcollab.knowledgecore.document.core.application.exception.DocumentBlockNotFoundException;
import com.devcollab.knowledgecore.document.core.domain.DocumentBlock;
import com.devcollab.knowledgecore.document.core.domain.DocumentBlockRepository;
import com.devcollab.knowledgecore.documentchange.application.DocumentChangeApplicationService;
import com.devcollab.knowledgecore.documentchange.domain.DocumentChangeModel.BindingAction;
import com.devcollab.knowledgecore.documentchange.domain.DocumentChangeModel.OperationType;
import com.devcollab.knowledgecore.documentchange.domain.DocumentChangeModel.Status;
import com.devcollab.knowledgecore.git.domain.CodeAnchorKind;
import com.devcollab.knowledgecore.git.domain.CodeDocumentBinding;
import com.devcollab.knowledgecore.git.domain.CodeSymbol;
import com.devcollab.knowledgecore.git.domain.GitKnowledgeRepository;
import com.devcollab.knowledgecore.git.domain.GitRepositoryFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Detects and automatically fixes document drift after a new revision is ingested.
 *
 * <p>For every active binding in a repository, re-resolves the bound
 * symbol against the current symbol table.  When drift is detected the
 * service generates the appropriate binding and document-block fixes
 * and auto-applies them — no human approval required for deterministic
 * fixes.
 *
 * <h3>Fix strategy by drift level</h3>
 * <ul>
 *   <li>{@link DriftLevel#COSMETIC} — update binding position only</li>
 *   <li>{@link DriftLevel#SIGNATURE_CHANGED} — update binding + replace
 *       old signature text in the associated DocumentBlock</li>
 *   <li>{@link DriftLevel#SYMBOL_MOVED} — update binding file path</li>
 *   <li>{@link DriftLevel#SYMBOL_REMOVED} / {@link DriftLevel#FILE_REMOVED}
 *       — remove binding</li>
 * </ul>
 */
@Service
public class DriftDetectionService {

    private static final Logger LOG = LoggerFactory.getLogger(DriftDetectionService.class);

    private final GitKnowledgeRepository gitRepository;
    private final DocumentChangeApplicationService documentChangeService;
    private final DocumentBlockRepository blockRepository;

    public DriftDetectionService(
            GitKnowledgeRepository gitRepository,
            @Lazy DocumentChangeApplicationService documentChangeService,
            DocumentBlockRepository blockRepository
    ) {
        this.gitRepository = gitRepository;
        this.documentChangeService = documentChangeService;
        this.blockRepository = blockRepository;
    }

    /**
     * Detect and auto-fix drift for all bindings in a repository.
     *
     * <p>Called synchronously from {@code ingestChange()} after a new
     * revision has been ingested.  Fixes are auto-applied — this runs
     * inside the same transaction as ingestChange.
     */
    @Transactional
    public void detectAndSubmit(
            UUID workspaceId,
            UUID repositoryId,
            String newRevision,
            UUID currentUserId
    ) {
        List<CodeDocumentBinding> bindings = gitRepository
                .findBindingsByRepositoryId(repositoryId);
        if (bindings.isEmpty()) {
            LOG.info("Drift detection skipped: no bindings for repository {}", repositoryId);
            return;
        }

        List<CodeSymbol> symbols = gitRepository
                .findSymbolsByRepositoryId(repositoryId, null);
        if (symbols.isEmpty()) {
            LOG.info("Drift detection skipped: no symbols extracted for repository {}",
                    repositoryId);
            return;
        }

        Map<String, CodeSymbol> bySymbolKey = new HashMap<>();
        Map<String, List<CodeSymbol>> byQualifiedName = new HashMap<>();
        for (CodeSymbol sym : symbols) {
            bySymbolKey.put(sym.symbolKey(), sym);
            byQualifiedName
                    .computeIfAbsent(sym.qualifiedName(), k -> new ArrayList<>())
                    .add(sym);
        }

        Set<String> currentFiles = gitRepository.findFilesByRepositoryId(repositoryId)
                .stream()
                .map(GitRepositoryFile::path)
                .collect(Collectors.toSet());

        List<DocumentChangeApplicationService.CreateBindingProposalCommand> proposals =
                new ArrayList<>();
        List<DocumentChangeApplicationService.CreateOperationCommand> operations =
                new ArrayList<>();

        int seq = 0;
        for (CodeDocumentBinding binding : bindings) {
            DriftResult result = reResolve(binding, bySymbolKey, byQualifiedName, currentFiles);
            if (result.level == DriftLevel.NONE) {
                continue;
            }
            seq++;
            proposals.add(toProposal(result, binding, newRevision, seq));

            // SIGNATURE_CHANGED + has blockId → update the block text too
            if (result.level == DriftLevel.SIGNATURE_CHANGED
                    && binding.blockId() != null
                    && result.oldSignature != null
                    && result.newSignature != null) {
                var blockOp = buildBlockUpdate(binding, result, seq, currentUserId);
                if (blockOp != null) {
                    operations.add(blockOp);
                }
            }
        }

        if (proposals.isEmpty()) {
            LOG.info("Drift detection complete: no drift found ({} bindings checked)",
                    bindings.size());
            return;
        }

        String clientRequestId = String.format(
                "drift-%s-%s",
                repositoryId.toString().replace("-", "").substring(0, 8),
                newRevision.substring(0, 7)
        );

        String summary = String.format(
                "漂移检测: %d 条绑定受影响 (commit %s)",
                proposals.size(),
                newRevision.substring(0, 7)
        );
        String rationale = buildRationale(proposals, operations);

        var command = new DocumentChangeApplicationService.CreateCommand(
                clientRequestId,
                summary,
                rationale,
                operations,
                proposals,
                List.of()
        );

        // Step 1: Create the change request
        var result = documentChangeService.create(workspaceId, currentUserId, command);

        // Step 2: When there are document-block operations the create() returns
        // PENDING.  Auto-apply to keep the silent pipeline truly silent.
        if (result.status() == Status.PENDING) {
            documentChangeService.apply(workspaceId, result.changeRequestId(), currentUserId);
            LOG.info("Drift fixes auto-applied: {} binding(s), {} block update(s)",
                    proposals.size(), operations.size());
        } else {
            LOG.info("Drift fixes applied immediately: {} binding(s), {} block update(s)",
                    proposals.size(), operations.size());
        }
    }

    // ── Block text update ─────────────────────────────────────────────────

    private DocumentChangeApplicationService.CreateOperationCommand buildBlockUpdate(
            CodeDocumentBinding binding,
            DriftResult result,
            int sequenceNumber,
            UUID currentUserId
    ) {
        DocumentBlock block;
        try {
            block = blockRepository.findById(binding.blockId())
                    .orElseThrow(() -> new DocumentBlockNotFoundException());
        } catch (Exception e) {
            LOG.warn("Cannot update block {} for drift fix: block not found",
                    binding.blockId());
            return null;
        }

        String oldSig = result.oldSignature;
        String newSig = result.newSignature;
        String oldText = block.text();
        String newText = oldText;

        // Phase 1: Exact replace（精确匹配）
        if (oldText.contains(oldSig)) {
            newText = oldText.replace(oldSig, newSig);
        }
        // Phase 2: Line-based replace（行级匹配 — 签名可能跨行）
        // 当精确匹配失败时，尝试提取 oldSig 的第一行和最后一行的关键部分
        // 在 oldText 中做模糊替换。这里保持简单：只做精确替换。
        // 跨行签名的替换留给后续迭代（可通过 AST 分析确定行范围）。

        if (newText.equals(oldText)) {
            LOG.debug("Block {} text unchanged after signature replacement: "
                    + "old signature not found in block text", binding.blockId());
            return null;  // 签名没出现在正文中，无需更新
        }

        LOG.info("Auto-updating block {} text: signature replacement", binding.blockId());

        return new DocumentChangeApplicationService.CreateOperationCommand(
                "drift-block-" + sequenceNumber + "-" + binding.blockId().toString().substring(0, 8),
                sequenceNumber,
                OperationType.UPDATE_BLOCK,
                binding.documentId(),
                null,              // createdDocumentClientOperationId
                binding.blockId(),
                block.version(),   // baseBlockVersion for optimistic lock
                null,              // proposedDocumentTitle
                null,              // proposedDocumentType
                null,              // proposedParentDocumentId
                null,              // proposedBlockType (keep existing)
                newText,           // proposedPlainText
                null,              // proposedContentSchemaVersion
                null               // proposedContent (keep existing JSON)
        );
    }

    // ── Re-resolution ──────────────────────────────────────────────────────

    private DriftResult reResolve(
            CodeDocumentBinding binding,
            Map<String, CodeSymbol> bySymbolKey,
            Map<String, List<CodeSymbol>> byQualifiedName,
            Set<String> currentFiles
    ) {
        String filePath = effectiveFilePath(binding);
        boolean fileExists = filePath != null && currentFiles.contains(filePath);

        if (binding.symbolKey() == null || binding.symbolKey().isBlank()) {
            if (!fileExists && filePath != null) {
                return new DriftResult(DriftLevel.FILE_REMOVED,
                        "Bound file '" + filePath + "' no longer exists.");
            }
            return new DriftResult(DriftLevel.NONE,
                    "Legacy file/range-level binding; no symbol to compare.");
        }

        CodeSymbol currentSymbol = bySymbolKey.get(binding.symbolKey());

        if (currentSymbol != null) {
            return compareBindingToSymbol(binding, currentSymbol);
        }

        String qname = symbolKeyQualifiedName(binding.symbolKey());
        if (qname != null) {
            List<CodeSymbol> matches = byQualifiedName.get(qname);
            if (matches != null && !matches.isEmpty()) {
                currentSymbol = matches.stream()
                        .filter(s -> s.filePath().equals(filePath))
                        .findFirst()
                        .orElse(matches.get(0));
                return compareBindingToSymbol(binding, currentSymbol);
            }
        }

        if (binding.startLine() != null && filePath != null) {
            for (CodeSymbol sym : bySymbolKey.values()) {
                if (filePath.equals(sym.filePath())
                        && sym.startLine() != null && sym.endLine() != null
                        && sym.startLine() <= binding.startLine()
                        && sym.endLine() >= (binding.endLine() != null
                                ? binding.endLine() : binding.startLine())) {
                    return compareBindingToSymbol(binding, sym);
                }
            }
        }

        if (!fileExists && filePath != null) {
            return new DriftResult(DriftLevel.FILE_REMOVED,
                    "Bound file was deleted: " + filePath);
        }
        return new DriftResult(DriftLevel.SYMBOL_REMOVED,
                "Symbol '" + (qname != null ? qname : binding.symbolKey())
                        + "' was removed or renamed in " + filePath + ".");
    }

    private DriftResult compareBindingToSymbol(
            CodeDocumentBinding binding,
            CodeSymbol currentSymbol
    ) {
        String boundFile = effectiveFilePath(binding);
        String currentFile = currentSymbol.filePath();

        if (boundFile != null && !boundFile.equals(currentFile)) {
            return new DriftResult(DriftLevel.SYMBOL_MOVED,
                    "'" + currentSymbol.qualifiedName() + "' moved from "
                            + boundFile + " to " + currentFile + ".");
        }

        if (binding.boundSignature() != null
                && currentSymbol.signature() != null
                && !binding.boundSignature().equals(currentSymbol.signature())) {
            return new DriftResult(DriftLevel.SIGNATURE_CHANGED,
                    "Signature of '" + currentSymbol.qualifiedName() + "' changed.",
                    binding.boundSignature(),
                    currentSymbol.signature());
        }

        boolean lineChanged = !nullSafeEquals(binding.startLine(), currentSymbol.startLine())
                || !nullSafeEquals(binding.endLine(), currentSymbol.endLine());
        if (lineChanged) {
            return new DriftResult(DriftLevel.COSMETIC,
                    "'" + currentSymbol.qualifiedName() + "' shifted from lines "
                            + binding.startLine() + "-" + binding.endLine()
                            + " to " + currentSymbol.startLine() + "-"
                            + currentSymbol.endLine() + ".");
        }

        return new DriftResult(DriftLevel.NONE,
                "'" + currentSymbol.qualifiedName() + "' is unchanged.");
    }

    // ── Proposal construction ──────────────────────────────────────────────

    private DocumentChangeApplicationService.CreateBindingProposalCommand toProposal(
            DriftResult result,
            CodeDocumentBinding binding,
            String newRevision,
            int sequenceNumber
    ) {
        boolean isBroken = result.level == DriftLevel.SYMBOL_REMOVED
                || result.level == DriftLevel.FILE_REMOVED;

        if (isBroken) {
            return new DocumentChangeApplicationService.CreateBindingProposalCommand(
                    "drift-" + sequenceNumber + "-" + binding.id().toString().substring(0, 8),
                    sequenceNumber,
                    BindingAction.REMOVE_BINDING,
                    binding.repositoryId(),
                    binding.revision(),
                    binding.pathPattern(),
                    binding.anchorKind(),
                    binding.symbolKey(),
                    binding.startLine(),
                    binding.endLine(),
                    binding.documentId(),
                    null,
                    binding.blockId(),
                    null,
                    binding.id(),
                    null,
                    null,
                    result.detail,
                    null,
                    binding.bindingRole(),
                    binding.bindingOrdinal(),
                    null
            );
        }

        CodeSymbol newSymbol = resolveCurrentSymbol(binding);

        return new DocumentChangeApplicationService.CreateBindingProposalCommand(
                "drift-" + sequenceNumber + "-" + binding.id().toString().substring(0, 8),
                sequenceNumber,
                BindingAction.UPSERT_BINDING,
                binding.repositoryId(),
                newRevision,
                newSymbol != null ? newSymbol.filePath() : effectiveFilePath(binding),
                CodeAnchorKind.SYMBOL,
                newSymbol != null ? newSymbol.symbolKey() : binding.symbolKey(),
                newSymbol != null ? newSymbol.startLine() : binding.startLine(),
                newSymbol != null ? newSymbol.endLine() : binding.endLine(),
                binding.documentId(),
                null,
                binding.blockId(),
                null,
                null,
                null,
                null,
                result.detail,
                1.0,
                binding.bindingRole(),
                binding.bindingOrdinal(),
                newSymbol != null ? newSymbol.signature() : null
        );
    }

    private CodeSymbol resolveCurrentSymbol(CodeDocumentBinding binding) {
        List<CodeSymbol> symbols = gitRepository.findSymbolsByRepositoryId(
                binding.repositoryId(), null);
        for (CodeSymbol sym : symbols) {
            if (sym.symbolKey().equals(binding.symbolKey())) {
                return sym;
            }
        }
        String qname = symbolKeyQualifiedName(binding.symbolKey());
        if (qname != null) {
            for (CodeSymbol sym : symbols) {
                if (qname.equals(sym.qualifiedName())) {
                    return sym;
                }
            }
        }
        return null;
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static String effectiveFilePath(CodeDocumentBinding binding) {
        if (binding.symbolKey() != null && !binding.symbolKey().isBlank()) {
            String extracted = symbolKeyFilePath(binding.symbolKey());
            if (!extracted.isEmpty()) {
                return extracted;
            }
        }
        String pattern = binding.pathPattern();
        if (pattern != null) {
            return pattern.replace("/**", "").replace("**/*.", "");
        }
        return null;
    }

    static String symbolKeyFilePath(String symbolKey) {
        if (symbolKey == null || symbolKey.isBlank()) {
            return "";
        }
        int first = symbolKey.indexOf(':');
        if (first < 0) {
            return "";
        }
        int second = symbolKey.indexOf(':', first + 1);
        if (second < 0) {
            return symbolKey.substring(first + 1);
        }
        return symbolKey.substring(first + 1, second);
    }

    static String symbolKeyQualifiedName(String symbolKey) {
        if (symbolKey == null || symbolKey.isBlank()) {
            return null;
        }
        int first = symbolKey.indexOf(':');
        if (first < 0) {
            return null;
        }
        int second = symbolKey.indexOf(':', first + 1);
        if (second < 0) {
            return null;
        }
        String remainder = symbolKey.substring(second + 1);
        int lastColon = remainder.lastIndexOf(':');
        if (lastColon < 0) {
            return remainder;
        }
        return remainder.substring(0, lastColon);
    }

    private static boolean nullSafeEquals(Integer a, Integer b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.equals(b);
    }

    private String buildRationale(
            List<DocumentChangeApplicationService.CreateBindingProposalCommand> proposals,
            List<DocumentChangeApplicationService.CreateOperationCommand> operations
    ) {
        Map<BindingAction, Long> counts = proposals.stream()
                .collect(Collectors.groupingBy(
                        DocumentChangeApplicationService.CreateBindingProposalCommand::action,
                        Collectors.counting()
                ));

        StringBuilder sb = new StringBuilder("自动漂移检测完成:\n");
        long upserts = counts.getOrDefault(BindingAction.UPSERT_BINDING, 0L);
        long removals = counts.getOrDefault(BindingAction.REMOVE_BINDING, 0L);
        if (upserts > 0) {
            sb.append("- ").append(upserts)
                    .append(" 条绑定已更新（行号/签名/路径）\n");
        }
        if (removals > 0) {
            sb.append("- ").append(removals)
                    .append(" 条绑定已移除（符号或文件被删除）\n");
        }
        if (!operations.isEmpty()) {
            sb.append("- ").append(operations.size())
                    .append(" 个文档 Block 正文中的签名引用已自动替换\n");
        }
        return sb.toString();
    }

    // ── Internal result type ───────────────────────────────────────────────

    /**
     * Internal drift result, carrying old/new signatures for block text replacement.
     */
    static class DriftResult {
        final DriftLevel level;
        final String detail;
        final String oldSignature;
        final String newSignature;

        DriftResult(DriftLevel level, String detail) {
            this(level, detail, null, null);
        }

        DriftResult(DriftLevel level, String detail, String oldSignature, String newSignature) {
            this.level = level;
            this.detail = detail;
            this.oldSignature = oldSignature;
            this.newSignature = newSignature;
        }
    }
}
