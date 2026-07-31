package com.devcollab.knowledgecore.document.collaboration.application;

import com.devcollab.knowledgecore.document.collaboration.application.exception.CollaborationOperationIdReusedException;
import com.devcollab.knowledgecore.document.core.domain.Document;
import com.devcollab.knowledgecore.document.core.domain.DocumentBlock;
import com.devcollab.knowledgecore.document.collaboration.domain.DocumentCollaborationOperation;
import com.devcollab.knowledgecore.document.collaboration.domain.DocumentCollaborationOperationPayload;
import com.devcollab.knowledgecore.document.collaboration.domain.DocumentCollaborationOperationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import com.devcollab.knowledgecore.document.core.application.DocumentApplicationService;
import com.devcollab.knowledgecore.document.core.application.DocumentBlockApplicationService;
import com.devcollab.knowledgecore.document.core.application.UpdateDocumentBlockCommand;
import com.devcollab.knowledgecore.document.core.application.CreateDocumentBlockCommand;
import com.devcollab.knowledgecore.document.core.application.MoveDocumentBlockCommand;

@Service
public class DocumentCollaborationOperationService {

    public static final int MAX_CATCH_UP_PAGE_SIZE = 200;

    public static final String UPDATE_TEXT = "UPDATE_TEXT";
    public static final String CREATE_BLOCK = "CREATE_BLOCK";
    public static final String DELETE_BLOCK = "DELETE_BLOCK";
    public static final String MOVE_BLOCK = "MOVE_BLOCK";

    private final DocumentApplicationService documentService;
    private final DocumentBlockApplicationService blockService;
    private final DocumentCollaborationOperationRepository operationRepository;

    public DocumentCollaborationOperationService(
            DocumentApplicationService documentService,
            DocumentBlockApplicationService blockService,
            DocumentCollaborationOperationRepository operationRepository
    ) {
        this.documentService = documentService;
        this.blockService = blockService;
        this.operationRepository = operationRepository;
    }

    @Transactional
    public DocumentCollaborationOperationResult apply(
            UUID documentId,
            UUID currentUserId,
            ApplyDocumentCollaborationOperationCommand command
    ) {
        validate(command);

        Document document = documentService.get(documentId, currentUserId);
        String fingerprint = fingerprint(command);

        // Serializes formal operations for one document across Core instances.
        // It also closes the race between duplicate lookup and result insert.
        operationRepository.lockDocument(documentId);

        var existing = operationRepository.findByClientOperationId(
                        documentId,
                        command.clientOperationId()
                );
        if (existing.isPresent()) {
            return duplicate(existing.get(), currentUserId, fingerprint);
        }

        documentService.ensureEditable(document);
        return applyFirst(
                documentId,
                currentUserId,
                command,
                fingerprint
        );
    }

    @Transactional(readOnly = true)
    public DocumentCollaborationOperationPage listAfter(
            UUID documentId,
            UUID currentUserId,
            long afterSequence,
            int limit
    ) {
        if (afterSequence < 0) {
            throw new IllegalArgumentException(
                    "afterSequence must be zero or greater"
            );
        }
        if (limit < 1 || limit > MAX_CATCH_UP_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "limit must be between 1 and " + MAX_CATCH_UP_PAGE_SIZE
            );
        }

        documentService.get(documentId, currentUserId);
        long latestSequence = operationRepository.currentDocumentSequence(
                documentId
        );
        List<DocumentCollaborationOperation> found =
                operationRepository.findAfterSequence(
                        documentId,
                        afterSequence,
                        latestSequence,
                        limit + 1
                );
        boolean hasMore = found.size() > limit;
        List<DocumentCollaborationOperationResult> operations = found.stream()
                .limit(limit)
                .map(operation -> result(operation, "APPLIED"))
                .toList();
        return new DocumentCollaborationOperationPage(
                afterSequence,
                latestSequence,
                hasMore,
                operations
        );
    }

    private DocumentCollaborationOperationResult applyFirst(
            UUID documentId,
            UUID currentUserId,
            ApplyDocumentCollaborationOperationCommand command,
            String fingerprint
    ) {
        DocumentCollaborationOperationPayload payload = switch (
                command.operationType()
        ) {
            case UPDATE_TEXT -> DocumentCollaborationOperationPayload.single(
                    blockService.updateContent(
                            documentId,
                            command.blockId(),
                            currentUserId,
                            new UpdateDocumentBlockCommand(
                                    command.text(),
                                    command.contentSchemaVersion(),
                                    command.contentDocument(),
                                    command.expectedVersion()
                            )
                    )
            );
            case CREATE_BLOCK -> DocumentCollaborationOperationPayload.single(
                    blockService.create(
                            documentId,
                            currentUserId,
                            new CreateDocumentBlockCommand(
                                    command.blockType(),
                                    command.text(),
                                    command.contentSchemaVersion(),
                                    command.contentDocument()
                            )
                    )
            );
            case DELETE_BLOCK -> {
                DocumentBlock deleted = blockService.deleteWithVersion(
                        documentId,
                        command.blockId(),
                        currentUserId,
                        command.expectedVersion()
                );
                yield DocumentCollaborationOperationPayload.ordered(
                        deleted,
                        blockService.list(documentId, currentUserId)
                );
            }
            case MOVE_BLOCK -> {
                List<DocumentBlock> blocks = blockService.move(
                        documentId,
                        command.blockId(),
                        currentUserId,
                        new MoveDocumentBlockCommand(command.targetIndex())
                );
                DocumentBlock moved = blocks.stream()
                        .filter(block -> block.id().equals(command.blockId()))
                        .findFirst()
                        .orElseThrow();
                yield DocumentCollaborationOperationPayload.ordered(
                        moved,
                        blocks
                );
            }
            default -> throw new IllegalArgumentException(
                    "Unsupported collaboration operation: "
                            + command.operationType()
            );
        };
        long sequence = operationRepository.nextDocumentSequence(documentId);
        DocumentCollaborationOperation operation = operationRepository.save(
                new DocumentCollaborationOperation(
                        UUID.randomUUID(),
                        documentId,
                        sequence,
                        command.clientOperationId(),
                        command.operationType(),
                        currentUserId,
                        fingerprint,
                        payload,
                        Instant.now()
                )
        );
        return result(operation, "APPLIED");
    }

    private DocumentCollaborationOperationResult duplicate(
            DocumentCollaborationOperation existing,
            UUID currentUserId,
            String fingerprint
    ) {
        if (!existing.operatorUserId().equals(currentUserId)
                || !existing.requestFingerprint().equals(fingerprint)) {
            throw new CollaborationOperationIdReusedException();
        }
        return result(existing, "DUPLICATE");
    }

    private DocumentCollaborationOperationResult result(
            DocumentCollaborationOperation operation,
            String status
    ) {
        return new DocumentCollaborationOperationResult(
                operation.clientOperationId(),
                operation.result().block() == null
                        ? null
                        : operation.result().block().id(),
                operation.operationType(),
                status,
                operation.documentSequence(),
                operation.operatorUserId(),
                operation.result().block(),
                operation.result().blocks()
        );
    }

    private void validate(ApplyDocumentCollaborationOperationCommand command) {
        if (command.operationType() == null || command.operationType().isBlank()) {
            throw new IllegalArgumentException("operationType is required");
        }
        switch (command.operationType()) {
            case UPDATE_TEXT -> {
                requireBlock(command);
                requireVersion(command);
                requireContent(command);
            }
            case CREATE_BLOCK -> {
                if (command.blockType() == null) {
                    throw new IllegalArgumentException("blockType is required");
                }
                requireContent(command);
            }
            case DELETE_BLOCK -> {
                requireBlock(command);
                requireVersion(command);
            }
            case MOVE_BLOCK -> {
                requireBlock(command);
                if (command.targetIndex() == null || command.targetIndex() < 0) {
                    throw new IllegalArgumentException(
                            "targetIndex must be zero or greater"
                    );
                }
            }
            default -> throw new IllegalArgumentException(
                    "Unsupported collaboration operation: "
                            + command.operationType()
            );
        }
    }

    private void requireBlock(ApplyDocumentCollaborationOperationCommand command) {
        if (command.blockId() == null) {
            throw new IllegalArgumentException("blockId is required");
        }
    }

    private void requireVersion(ApplyDocumentCollaborationOperationCommand command) {
        if (command.expectedVersion() == null || command.expectedVersion() < 0) {
            throw new IllegalArgumentException(
                    "expectedVersion must be zero or greater"
            );
        }
    }

    private void requireContent(ApplyDocumentCollaborationOperationCommand command) {
        if (command.text() == null && command.contentDocument() == null) {
            throw new IllegalArgumentException(
                    "Either content.text or content.document is required"
            );
        }
    }

    private String fingerprint(
            ApplyDocumentCollaborationOperationCommand command
    ) {
        String canonical = command.blockId()
                + "\n" + command.operationType()
                + "\n" + command.expectedVersion()
                + "\n" + command.blockType()
                + "\n" + command.targetIndex()
                + "\n" + command.text()
                + "\n" + command.contentSchemaVersion()
                + "\n" + command.contentDocument();
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
