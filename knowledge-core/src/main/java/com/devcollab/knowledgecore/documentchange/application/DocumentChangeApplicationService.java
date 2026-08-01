package com.devcollab.knowledgecore.documentchange.application;

import com.devcollab.knowledgecore.auth.domain.UserAccount;
import com.devcollab.knowledgecore.auth.domain.UserRepository;
import com.devcollab.knowledgecore.document.core.domain.Document;
import com.devcollab.knowledgecore.document.core.domain.DocumentBlock;
import com.devcollab.knowledgecore.document.core.domain.DocumentBlockType;
import com.devcollab.knowledgecore.document.core.domain.DocumentBlockRepository;
import com.devcollab.knowledgecore.document.core.domain.DocumentRepository;
import com.devcollab.knowledgecore.document.core.domain.DocumentReviewStatus;
import com.devcollab.knowledgecore.document.core.domain.DocumentType;
import com.devcollab.knowledgecore.document.core.application.CreateDocumentBlockCommand;
import com.devcollab.knowledgecore.document.core.application.CreateDocumentCommand;
import com.devcollab.knowledgecore.document.core.application.DocumentApplicationService;
import com.devcollab.knowledgecore.document.core.application.DocumentBlockApplicationService;
import com.devcollab.knowledgecore.document.block.application.DocumentBlockContentCodec;
import com.devcollab.knowledgecore.document.block.application.DocumentBlockContentFormat;
import com.devcollab.knowledgecore.document.core.application.UpdateDocumentBlockCommand;
import com.devcollab.knowledgecore.document.collaboration.domain.DocumentOperationLog;
import com.devcollab.knowledgecore.document.collaboration.domain.DocumentOperationLogRepository;
import com.devcollab.knowledgecore.common.outbox.application.OutboxEventPublisher;
import com.devcollab.knowledgecore.common.util.RepositoryPathValidator;
import com.devcollab.knowledgecore.documentchange.domain.DocumentChangeModel;
import com.devcollab.knowledgecore.documentchange.domain.DocumentChangeRepository;
import com.devcollab.knowledgecore.git.domain.GitKnowledgeRepository;
import com.devcollab.knowledgecore.git.domain.GitRepository;
import com.devcollab.knowledgecore.git.domain.CodeDocumentBinding;
import com.devcollab.knowledgecore.git.domain.CodeAnchorKind;
import com.devcollab.knowledgecore.git.domain.BindingRole;
import com.devcollab.knowledgecore.git.application.CreateCodeBindingCommand;
import com.devcollab.knowledgecore.git.application.CodeBindingAnchorValidator;
import com.devcollab.knowledgecore.git.application.GitKnowledgeApplicationService;
import com.devcollab.knowledgecore.git.application.exception.DuplicateCodeBindingException;
import com.devcollab.knowledgecore.workspace.application.WorkspaceApplicationService;
import com.devcollab.knowledgecore.workspace.application.exception.WorkspaceAccessDeniedException;
import com.devcollab.knowledgecore.workspace.application.exception.WorkspaceNotFoundException;
import com.devcollab.knowledgecore.workspace.domain.WorkspaceRole;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Comparator;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.devcollab.knowledgecore.documentchange.application.DocumentChangeViews.*;
import static com.devcollab.knowledgecore.documentchange.domain.DocumentChangeModel.*;

@Service
public class DocumentChangeApplicationService {

    private final DocumentChangeRepository repository;
    private final WorkspaceApplicationService workspaceService;
    private final DocumentRepository documentRepository;
    private final DocumentBlockRepository blockRepository;
    private final GitKnowledgeRepository gitRepository;
    private final GitKnowledgeApplicationService gitKnowledgeService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final DocumentBlockContentCodec contentCodec;
    private final DocumentApplicationService documentService;
    private final DocumentBlockApplicationService blockService;
    private final DocumentOperationLogRepository operationLogRepository;
    private final OutboxEventPublisher outboxEventPublisher;

    public DocumentChangeApplicationService(
            DocumentChangeRepository repository,
            WorkspaceApplicationService workspaceService,
            DocumentRepository documentRepository,
            DocumentBlockRepository blockRepository,
            GitKnowledgeRepository gitRepository,
            GitKnowledgeApplicationService gitKnowledgeService,
            UserRepository userRepository,
            ObjectMapper objectMapper,
            DocumentBlockContentCodec contentCodec,
            DocumentApplicationService documentService,
            DocumentBlockApplicationService blockService,
            DocumentOperationLogRepository operationLogRepository,
            OutboxEventPublisher outboxEventPublisher
    ) {
        this.repository = repository;
        this.workspaceService = workspaceService;
        this.documentRepository = documentRepository;
        this.blockRepository = blockRepository;
        this.gitRepository = gitRepository;
        this.gitKnowledgeService = gitKnowledgeService;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
        this.contentCodec = contentCodec;
        this.documentService = documentService;
        this.blockService = blockService;
        this.operationLogRepository = operationLogRepository;
        this.outboxEventPublisher = outboxEventPublisher;
    }

    public record CreateCommand(
            String clientRequestId,
            String summary,
            String rationale,
            List<CreateOperationCommand> operations,
            List<CreateBindingProposalCommand> bindingProposals,
            List<CreateEvidenceCommand> evidence
    ) {
    }

    public record CreateBindingProposalCommand(
            String clientBindingProposalId,
            int sequenceNumber,
            BindingAction action,
            UUID repositoryId,
            String revision,
            String filePath,
            CodeAnchorKind anchorKind,
            String symbolKey,
            Integer startLine,
            Integer endLine,
            UUID documentId,
            String createdDocumentClientOperationId,
            UUID blockId,
            String createdBlockClientOperationId,
            UUID bindingId,
            String candidateId,
            String documentAnchorCandidateId,
            String reason,
            Double confidence,
            BindingRole bindingRole,
            int bindingOrdinal
    ) {
        public CreateBindingProposalCommand(
                String clientBindingProposalId, int sequenceNumber,
                BindingAction action, UUID repositoryId, String revision,
                String filePath, CodeAnchorKind anchorKind, String symbolKey,
                Integer startLine, Integer endLine, UUID documentId,
                String createdDocumentClientOperationId, UUID blockId,
                String createdBlockClientOperationId, UUID bindingId,
                String candidateId, String documentAnchorCandidateId,
                String reason, Double confidence
        ) {
            this(clientBindingProposalId, sequenceNumber, action, repositoryId,
                    revision, filePath, anchorKind, symbolKey, startLine, endLine,
                    documentId, createdDocumentClientOperationId, blockId,
                    createdBlockClientOperationId, bindingId, candidateId,
                    documentAnchorCandidateId, reason, confidence,
                    BindingRole.PRIMARY, 1);
        }

        public CreateBindingProposalCommand(
                String clientBindingProposalId,
                int sequenceNumber,
                BindingAction action,
                UUID repositoryId,
                String filePath,
                UUID documentId,
                String createdDocumentClientOperationId,
                UUID bindingId,
                String reason
        ) {
            this(
                    clientBindingProposalId, sequenceNumber, action,
                    repositoryId, null, filePath, CodeAnchorKind.FILE,
                    null, null, null, documentId,
                    createdDocumentClientOperationId, null, null, bindingId,
                    null, null, reason, null, BindingRole.PRIMARY, 1
            );
        }
    }

    public record CreateOperationCommand(
            String clientOperationId,
            int sequenceNumber,
            OperationType operationType,
            UUID documentId,
            String createdDocumentClientOperationId,
            UUID blockId,
            Long baseBlockVersion,
            String proposedDocumentTitle,
            DocumentType proposedDocumentType,
            UUID proposedParentDocumentId,
            DocumentBlockType proposedBlockType,
            String proposedPlainText,
            DocumentBlockContentFormat proposedContentFormat,
            Integer proposedContentSchemaVersion,
            JsonNode proposedContent
    ) {
        public CreateOperationCommand(
                String clientOperationId,
                int sequenceNumber,
                OperationType operationType,
                UUID documentId,
                String createdDocumentClientOperationId,
                UUID blockId,
                Long baseBlockVersion,
                String proposedDocumentTitle,
                DocumentType proposedDocumentType,
                UUID proposedParentDocumentId,
                DocumentBlockType proposedBlockType,
                String proposedPlainText,
                Integer proposedContentSchemaVersion,
                JsonNode proposedContent
        ) {
            this(
                    clientOperationId, sequenceNumber, operationType,
                    documentId, createdDocumentClientOperationId,
                    blockId, baseBlockVersion, proposedDocumentTitle,
                    proposedDocumentType, proposedParentDocumentId,
                    proposedBlockType, proposedPlainText, null,
                    proposedContentSchemaVersion, proposedContent
            );
        }
    }

    public record CreateEvidenceCommand(
            String clientOperationId,
            UUID repositoryId,
            String filePath,
            Integer startLine,
            Integer endLine,
            String description
    ) {
    }

    public record CreateResult(
            UUID changeRequestId,
            Status status,
            Instant createdAt,
            boolean idempotentReplay
    ) {
    }

    public record DecisionResult(DetailView detail, boolean stale) {
    }

    @Transactional
    public CreateResult create(
            UUID workspaceId,
            UUID currentUserId,
            CreateCommand command
    ) {
        requireMember(workspaceId, currentUserId);
        validateCreateCommand(command);
        CreateCommand normalizedCommand = normalizeCreateCommand(command);
        String fingerprint = fingerprint(normalizedCommand);
        var existing = repository.findByClientRequestId(
                workspaceId,
                currentUserId,
                normalizedCommand.clientRequestId().trim()
        );
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint);
        }

        UUID requestId = UUID.randomUUID();
        Instant now = Instant.now();
        ChangeRequest request = new ChangeRequest(
                requestId,
                workspaceId,
                normalizedCommand.clientRequestId().trim(),
                fingerprint,
                Status.PENDING,
                normalizedCommand.summary().trim(),
                normalizedCommand.rationale().trim(),
                SourceType.MCP,
                currentUserId,
                now,
                null,
                null,
                null
        );
        try {
            repository.saveRequest(request);
        } catch (DataIntegrityViolationException exception) {
            ChangeRequest concurrent = repository.findByClientRequestId(
                            workspaceId,
                            currentUserId,
                            request.clientRequestId()
                    )
                    .orElseThrow(() -> exception);
            return replay(concurrent, fingerprint);
        }

        Map<String, Operation> savedByClientId =
                new java.util.LinkedHashMap<>();
        for (CreateOperationCommand input : normalizedCommand.operations().stream()
                .sorted(java.util.Comparator.comparingInt(
                        CreateOperationCommand::sequenceNumber
                ))
                .toList()) {
            Operation operation = buildOperation(
                    workspaceId,
                    requestId,
                    input,
                    savedByClientId
            );
            repository.saveOperation(operation);
            savedByClientId.put(operation.clientOperationId(), operation);
        }
        for (CreateBindingProposalCommand input :
                safeList(normalizedCommand.bindingProposals())) {
            repository.saveBindingProposal(buildBindingProposal(
                    workspaceId,
                    requestId,
                    input,
                    savedByClientId
            ));
        }
        for (CreateEvidenceCommand input : safeList(normalizedCommand.evidence())) {
            repository.saveEvidence(buildEvidence(
                    workspaceId,
                    requestId,
                    input,
                    savedByClientId
            ));
        }
        return new CreateResult(requestId, Status.PENDING, now, false);
    }

    @Transactional
    public DecisionResult apply(
            UUID workspaceId,
            UUID requestId,
            UUID currentUserId
    ) {
        requireAdmin(workspaceId, currentUserId);
        ChangeRequest request = lockedRequest(workspaceId, requestId);
        if (request.status() == Status.APPLIED) {
            return new DecisionResult(replayedDetail(request), false);
        }
        if (request.status() == Status.STALE) {
            return new DecisionResult(replayedDetail(request), true);
        }
        if (request.status() == Status.REJECTED) {
            throw conflict(
                    "REQUEST_REJECTED",
                    "已拒绝的变更请求不能再次应用"
            );
        }

        List<Operation> operations = repository.findOperations(request.id());
        List<BindingProposal> bindingProposals =
                repository.findBindingProposals(request.id());
        LockedTargets targets = lockAndValidateTargets(
                workspaceId,
                operations
        );
        boolean bindingTargetsStale = lockAndValidateBindingTargets(
                workspaceId,
                currentUserId,
                bindingProposals
        );
        if (targets.stale() || bindingTargetsStale) {
            Instant reviewedAt = Instant.now();
            ChangeRequest stale = repository.decide(
                    request,
                    Status.STALE,
                    currentUserId,
                    reviewedAt,
                    null
            );
            recordDecision(
                    stale,
                    currentUserId,
                    "DOCUMENT_CHANGE_STALE",
                    "文档变更请求因目标已变化而失效",
                    reviewedAt
            );
            return new DecisionResult(detail(stale), true);
        }

        Map<UUID, UUID> createdDocuments = new java.util.HashMap<>();
        Map<UUID, UUID> createdBlocks = new java.util.HashMap<>();
        for (Operation operation : operations) {
            applyOperation(
                    workspaceId,
                    currentUserId,
                    operation,
                    createdDocuments,
                    createdBlocks,
                    targets.blocks()
            );
        }

        List<BindingApplyResultView> appliedBindings = new ArrayList<>();
        for (BindingProposal proposal : bindingProposals) {
            BindingApplyResultView result = applyBindingProposal(
                    currentUserId,
                    proposal,
                    createdDocuments,
                    createdBlocks
            );
            if (result != null) {
                appliedBindings.add(result);
            }
        }

        Instant reviewedAt = Instant.now();
        ChangeRequest applied = repository.decide(
                request,
                Status.APPLIED,
                currentUserId,
                reviewedAt,
                null
        );
        recordDecision(
                applied,
                currentUserId,
                "DOCUMENT_CHANGE_APPLIED",
                "批准并原子应用文档变更请求",
                reviewedAt
        );
        return new DecisionResult(detail(
                applied,
                new ApplyResultView(
                        Map.copyOf(createdDocuments),
                        Map.copyOf(createdBlocks),
                        List.copyOf(appliedBindings)
                )
        ), false);
    }

    @Transactional
    public DetailView reject(
            UUID workspaceId,
            UUID requestId,
            UUID currentUserId,
            String reason
    ) {
        requireAdmin(workspaceId, currentUserId);
        String normalizedReason = reason == null ? "" : reason.trim();
        if (normalizedReason.isEmpty() || normalizedReason.length() > 2_000) {
            throw invalid("拒绝理由必须为 1 到 2000 个字符");
        }

        ChangeRequest request = lockedRequest(workspaceId, requestId);
        if (request.status() == Status.REJECTED) {
            if (Objects.equals(request.rejectionReason(), normalizedReason)) {
                return replayedDetail(request);
            }
            throw conflict(
                    "REJECTION_REASON_CONFLICT",
                    "该请求已使用不同理由拒绝"
            );
        }
        if (request.status() == Status.APPLIED) {
            throw conflict(
                    "REQUEST_ALREADY_APPLIED",
                    "已应用的变更请求不能拒绝"
            );
        }
        if (request.status() == Status.STALE) {
            throw conflict(
                    "REQUEST_STALE",
                    "已失效的变更请求不能拒绝"
            );
        }

        Instant reviewedAt = Instant.now();
        ChangeRequest rejected = repository.decide(
                request,
                Status.REJECTED,
                currentUserId,
                reviewedAt,
                normalizedReason
        );
        recordDecision(
                rejected,
                currentUserId,
                "DOCUMENT_CHANGE_REJECTED",
                "拒绝文档变更请求：" + normalizedReason,
                reviewedAt
        );
        return detail(rejected);
    }

    public long pendingCount(UUID workspaceId, UUID currentUserId) {
        requireAdmin(workspaceId, currentUserId);
        return repository.count(workspaceId, Status.PENDING);
    }

    public PageView list(
            UUID workspaceId,
            UUID currentUserId,
            Status status,
            int page,
            int size,
            String sort
    ) {
        requireAdmin(workspaceId, currentUserId);
        if (page < 0 || size < 1 || size > 100) {
            throw invalid("分页参数不合法");
        }
        boolean ascending = switch (sort == null ? "createdAt,desc" : sort) {
            case "createdAt,asc" -> true;
            case "createdAt,desc" -> false;
            default -> throw invalid("仅支持 createdAt,asc 或 createdAt,desc");
        };
        Status resolvedStatus = status == null ? Status.PENDING : status;
        long total = repository.count(workspaceId, resolvedStatus);
        List<ListItemView> items = repository.findPage(
                        workspaceId,
                        resolvedStatus,
                        page * size,
                        size,
                        ascending
                ).stream()
                .map(item -> new ListItemView(
                        item.id(), item.summary(), item.status(),
                        item.sourceType(), item.submittedByDisplayName(),
                        item.createdAt(), item.reviewedAt(),
                        item.operationCount(), item.bindingProposalCount(), item.evidenceCount(),
                        affectedDocumentTitles(item.id())
                ))
                .toList();
        return new PageView(
                items,
                page,
                size,
                total,
                total == 0 ? 0 : (int) ((total + size - 1) / size)
        );
    }

    public DetailView detail(
            UUID workspaceId,
            UUID requestId,
            UUID currentUserId
    ) {
        requireAdmin(workspaceId, currentUserId);
        ChangeRequest request = repository.findRequest(workspaceId, requestId)
                .orElseThrow(() -> notFound("文档变更请求不存在"));
        return detail(request);
    }

    @Transactional(readOnly = true)
    public DetailView detail(ChangeRequest request) {
        return detail(request, null);
    }

    private DetailView detail(
            ChangeRequest request,
            ApplyResultView applyResult
    ) {
        List<Operation> operations = repository.findOperations(request.id());
        List<BindingProposal> bindingProposals = repository.findBindingProposals(request.id());
        List<Evidence> evidence = repository.findEvidence(request.id());
        Map<UUID, List<Evidence>> byOperation = evidence.stream()
                .filter(item -> item.operationId() != null)
                .collect(Collectors.groupingBy(Evidence::operationId));
        List<Evidence> requestEvidence = evidence.stream()
                .filter(item -> item.operationId() == null)
                .toList();

        java.util.Set<UUID> repoIds = evidence.stream()
                .map(Evidence::repositoryId)
                .collect(Collectors.toSet());
        repoIds.addAll(bindingProposals.stream()
                .map(BindingProposal::repositoryId)
                .collect(Collectors.toSet()));

        Map<UUID, GitRepository> repositories = repoIds.stream()
                .map(gitRepository::findRepositoryById)
                .flatMap(java.util.Optional::stream)
                .collect(Collectors.toMap(GitRepository::id, Function.identity()));
        Map<UUID, Operation> operationsById = operations.stream()
                .collect(Collectors.toMap(Operation::id, Function.identity()));

        List<OperationView> operationViews = operations.stream()
                .map(operation -> operationView(
                        operation,
                        byOperation.getOrDefault(operation.id(), List.of()),
                        repositories,
                        request.status()
                ))
                .toList();

        List<BindingProposalView> bindingProposalViews = bindingProposals.stream()
                .map(bp -> bindingProposalView(
                        bp,
                        repositories,
                        operationsById
                ))
                .toList();

        return new DetailView(
                requestView(request),
                operationViews,
                bindingProposalViews,
                requestEvidence.stream()
                        .map(item -> evidenceView(item, repositories))
                        .toList(),
                applyResult
        );
    }

    private OperationView operationView(
            Operation operation,
            List<Evidence> evidence,
            Map<UUID, GitRepository> repositories,
            Status requestStatus
    ) {
        Document document = operation.documentId() == null
                ? null
                : documentRepository.findById(operation.documentId()).orElse(null);
        DocumentBlock block = operation.blockId() == null
                ? null
                : blockRepository.findById(operation.blockId())
                .filter(item -> operation.documentId() == null
                        || item.documentId().equals(operation.documentId()))
                .orElse(null);
        String documentTitle = document == null
                ? operation.proposedDocumentTitle()
                : document.title();
        Long actualVersion = block == null ? null : block.version();
        boolean requiresVersion = operation.operationType() == OperationType.UPDATE_BLOCK
                || operation.operationType() == OperationType.DELETE_BLOCK;
        boolean conflictRelevant = requestStatus == Status.PENDING
                || requestStatus == Status.STALE;
        boolean missingDocument = operation.documentId() != null
                && document == null;
        boolean parentMissing = operation.operationType()
                == OperationType.CREATE_DOCUMENT
                && operation.proposedParentDocumentId() != null
                && documentRepository.findById(
                        operation.proposedParentDocumentId()
                ).isEmpty();
        boolean documentNotEditable = document != null
                && document.reviewStatus() != DocumentReviewStatus.DRAFT;
        boolean versionConflict = requiresVersion
                && !Objects.equals(operation.baseBlockVersion(), actualVersion);
        boolean conflicted = conflictRelevant && (
                missingDocument
                        || parentMissing
                        || documentNotEditable
                        || versionConflict
        );
        String conflictReason = !conflicted
                ? null
                : missingDocument ? "DOCUMENT_NOT_FOUND"
                : parentMissing ? "PARENT_DOCUMENT_NOT_FOUND"
                : documentNotEditable ? "DOCUMENT_NOT_EDITABLE"
                : block == null ? "BLOCK_NOT_FOUND"
                : "BLOCK_VERSION_CHANGED";
        return new OperationView(
                operation.id(),
                operation.clientOperationId(),
                operation.sequenceNumber(),
                operation.operationType(),
                new TargetView(
                        operation.documentId(),
                        documentTitle,
                        operation.blockId(),
                        block == null ? operation.originalBlockType()
                                : block.type().name()
                ),
                operation.originalBlockType() == null
                        ? null
                        : new SnapshotView(
                                operation.baseBlockVersion(),
                                operation.originalBlockType(),
                                operation.originalPlainText(),
                                readJson(operation.originalContentJson()),
                                operation.originalSortOrder()
                        ),
                new ProposalView(
                        operation.proposedDocumentTitle(),
                        operation.proposedDocumentType(),
                        operation.proposedParentDocumentId(),
                        operation.proposedBlockType(),
                        operation.proposedPlainText(),
                        readJson(operation.proposedContentJson())
                ),
                actualVersion,
                new ConflictView(
                        conflicted,
                        conflictReason,
                        operation.baseBlockVersion(),
                        actualVersion
                ),
                evidence.stream()
                        .map(item -> evidenceView(item, repositories))
                        .toList()
        );
    }

    private BindingProposalView bindingProposalView(
            BindingProposal bp,
            Map<UUID, GitRepository> repositories,
            Map<UUID, Operation> operations
    ) {
        Document document = bp.documentId() == null
                ? null : documentRepository.findById(bp.documentId()).orElse(null);
        Operation documentCreator = bp.createdDocumentOperationId() == null
                ? null : operations.get(bp.createdDocumentOperationId());
        Operation blockCreator = bp.createdBlockOperationId() == null
                ? null : operations.get(bp.createdBlockOperationId());
        DocumentBlock block = bp.blockId() == null
                ? null : blockRepository.findById(bp.blockId()).orElse(null);
        String documentTitle = document != null
                ? document.title()
                : documentCreator == null
                ? null : documentCreator.proposedDocumentTitle();
        String blockType = block != null
                ? block.type().name()
                : blockCreator == null ? null : blockCreator.proposedBlockType();
        String blockPreview = block != null
                ? block.text()
                : blockCreator == null ? null : blockCreator.proposedPlainText();
        GitRepository git = repositories.get(bp.repositoryId());
        return new BindingProposalView(
                bp.id(),
                bp.clientBindingProposalId(),
                bp.sequenceNumber(),
                bp.action(),
                new TargetView(
                        bp.documentId(),
                        documentTitle,
                        bp.blockId(),
                        blockType
                ),
                new RepositoryView(
                        bp.repositoryId(),
                        git == null ? "Unavailable repository" : git.name()
                ),
                bp.filePath(),
                bp.revision(),
                bp.anchorKind(),
                bp.symbolKey(),
                bp.startLine(),
                bp.endLine(),
                documentCreator == null
                        ? null : documentCreator.clientOperationId(),
                blockCreator == null ? null : blockCreator.clientOperationId(),
                blockPreview,
                bp.bindingId(),
                bp.candidateId(),
                bp.documentAnchorCandidateId(),
                bp.reason(),
                bp.confidence(),
                bp.bindingRole(),
                bp.bindingOrdinal()
        );
    }

    private List<String> affectedDocumentTitles(UUID requestId) {
        LinkedHashSet<String> titles = new LinkedHashSet<>();
        for (Operation operation : repository.findOperations(requestId)) {
            if (operation.documentId() != null) {
                documentRepository.findById(operation.documentId())
                        .map(Document::title)
                        .ifPresent(titles::add);
            } else if (operation.proposedDocumentTitle() != null) {
                titles.add(operation.proposedDocumentTitle());
            }
        }
        return new ArrayList<>(titles);
    }

    private RequestView requestView(ChangeRequest request) {
        return new RequestView(
                request.id(),
                request.workspaceId(),
                request.status(),
                request.summary(),
                request.rationale(),
                request.sourceType(),
                userView(request.submittedBy()),
                request.createdAt(),
                userView(request.reviewedBy()),
                request.reviewedAt(),
                request.rejectionReason()
        );
    }

    private EvidenceView evidenceView(
            Evidence evidence,
            Map<UUID, GitRepository> repositories
    ) {
        GitRepository git = repositories.get(evidence.repositoryId());
        return new EvidenceView(
                evidence.id(),
                new RepositoryView(
                        evidence.repositoryId(),
                        git == null ? "Unavailable repository" : git.name()
                ),
                evidence.filePath(),
                evidence.commitHash(),
                evidence.startLine(),
                evidence.endLine(),
                evidence.description(),
                evidence.excerptText()
        );
    }

    private UserView userView(UUID userId) {
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId)
                .map(user -> new UserView(user.id(), user.displayName()))
                .orElse(new UserView(userId, "Unknown user"));
    }

    private JsonNode readJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "存储的文档变更内容不是合法 JSON",
                    exception
            );
        }
    }

    private ChangeRequest lockedRequest(UUID workspaceId, UUID requestId) {
        return repository.findRequestForUpdate(workspaceId, requestId)
                .orElseThrow(() -> notFound(
                        "文档变更请求不存在"
                ));
    }

    private record LockedTargets(
            Map<UUID, Document> documents,
            Map<UUID, DocumentBlock> blocks,
            boolean stale
    ) {
    }

    private LockedTargets lockAndValidateTargets(
            UUID workspaceId,
            List<Operation> operations
    ) {
        LinkedHashSet<UUID> documentIds = new LinkedHashSet<>();
        for (Operation operation : operations) {
            if (operation.documentId() != null) {
                documentIds.add(operation.documentId());
            }
            if (operation.proposedParentDocumentId() != null) {
                documentIds.add(operation.proposedParentDocumentId());
            }
        }

        Map<UUID, Document> documents = new java.util.LinkedHashMap<>();
        boolean stale = false;
        for (UUID documentId : documentIds.stream().sorted().toList()) {
            Document document = documentRepository
                    .findByIdForUpdate(documentId)
                    .orElse(null);
            if (document == null
                    || !document.workspaceId().equals(workspaceId)
                    || document.reviewStatus() != DocumentReviewStatus.DRAFT) {
                stale = true;
            } else {
                documents.put(documentId, document);
            }
        }

        Map<UUID, DocumentBlock> blocks = new java.util.LinkedHashMap<>();
        List<Operation> blockOperations = operations.stream()
                .filter(operation -> operation.blockId() != null)
                .sorted(Comparator.comparing(
                        Operation::blockId
                ))
                .toList();
        for (Operation operation : blockOperations) {
            DocumentBlock block = blockRepository
                    .findByIdForUpdate(operation.blockId())
                    .orElse(null);
            if (block == null
                    || operation.documentId() == null
                    || !block.documentId().equals(operation.documentId())
                    || !Objects.equals(
                            block.version(),
                            operation.baseBlockVersion()
                    )) {
                stale = true;
            } else {
                blocks.put(block.id(), block);
            }
        }
        return new LockedTargets(documents, blocks, stale);
    }

    private boolean lockAndValidateBindingTargets(
            UUID workspaceId,
            UUID currentUserId,
            List<BindingProposal> proposals
    ) {
        List<BindingProposal> removals = proposals.stream()
                .filter(item -> item.action() == BindingAction.REMOVE_BINDING)
                .sorted(Comparator.comparing(
                        BindingProposal::bindingId,
                        Comparator.nullsFirst(Comparator.naturalOrder())
                ))
                .toList();
        for (BindingProposal proposal : removals) {
            if (proposal.bindingId() == null
                    || proposal.documentId() == null
                    || proposal.createdDocumentOperationId() != null) {
                return true;
            }
            CodeDocumentBinding binding = gitKnowledgeService
                    .findBindingForUpdate(
                            workspaceId,
                            proposal.bindingId(),
                            currentUserId
                    )
                    .orElse(null);
            if (binding == null
                    || !binding.workspaceId().equals(workspaceId)
                    || !binding.repositoryId().equals(proposal.repositoryId())
                    || !binding.documentId().equals(proposal.documentId())
                    || !Objects.equals(binding.blockId(), proposal.blockId())
                    || !binding.pathPattern().equals(proposal.filePath())
                    || !Objects.equals(binding.revision(), proposal.revision())
                    || binding.anchorKind() != proposal.anchorKind()
                    || !Objects.equals(binding.symbolKey(), proposal.symbolKey())
                    || !Objects.equals(binding.startLine(), proposal.startLine())
                    || !Objects.equals(binding.endLine(), proposal.endLine())) {
                return true;
            }
        }
        return false;
    }

    private void applyOperation(
            UUID workspaceId,
            UUID currentUserId,
            Operation operation,
            Map<UUID, UUID> createdDocuments,
            Map<UUID, UUID> createdBlocks,
            Map<UUID, DocumentBlock> lockedBlocks
    ) {
        switch (operation.operationType()) {
            case CREATE_DOCUMENT -> {
                Document created = documentService.create(
                        workspaceId,
                        currentUserId,
                        new CreateDocumentCommand(
                                operation.proposedParentDocumentId(),
                                operation.proposedDocumentTitle(),
                                operation.proposedDocumentType() == null
                                        ? DocumentType.REQUIREMENT
                                        : DocumentType.valueOf(
                                                operation.proposedDocumentType()
                                        )
                        )
                );
                createdDocuments.put(operation.id(), created.id());
            }
            case ADD_BLOCK -> {
                DocumentBlock created = blockService.create(
                        resolveDocumentId(operation, createdDocuments),
                        currentUserId,
                        new CreateDocumentBlockCommand(
                                DocumentBlockType.valueOf(
                                        operation.proposedBlockType()
                                ),
                                operation.proposedPlainText(),
                                operation.proposedContentSchemaVersion(),
                                readJson(operation.proposedContentJson())
                        )
                );
                createdBlocks.put(operation.id(), created.id());
            }
            case UPDATE_BLOCK -> {
                DocumentBlock block = lockedBlocks.get(operation.blockId());
                if (block == null) {
                    throw new IllegalStateException(
                            "预检后的 Block 锁定状态丢失"
                    );
                }
                blockService.updateContent(
                        operation.documentId(),
                        operation.blockId(),
                        currentUserId,
                        new UpdateDocumentBlockCommand(
                                operation.proposedPlainText(),
                                operation.proposedContentSchemaVersion(),
                                readJson(operation.proposedContentJson()),
                                operation.baseBlockVersion()
                        )
                );
            }
            case DELETE_BLOCK -> blockService.deleteWithVersion(
                    operation.documentId(),
                    operation.blockId(),
                    currentUserId,
                    operation.baseBlockVersion()
            );
        }
    }

    private UUID resolveDocumentId(
            Operation operation,
            Map<UUID, UUID> createdDocuments
    ) {
        if (operation.documentId() != null) {
            return operation.documentId();
        }
        UUID documentId = createdDocuments.get(
                operation.createdDocumentOperationId()
        );
        if (documentId == null) {
            throw new IllegalStateException(
                    "新建文档 Operation 尚未产生目标文档"
            );
        }
        return documentId;
    }

    private UUID resolveDocumentId(
            BindingProposal proposal,
            Map<UUID, UUID> createdDocuments
    ) {
        if (proposal.documentId() != null) {
            return proposal.documentId();
        }
        UUID documentId = createdDocuments.get(
                proposal.createdDocumentOperationId()
        );
        if (documentId == null) {
            throw new IllegalStateException(
                    "新建文档 BindingProposal 尚未产生目标文档"
            );
        }
        return documentId;
    }

    private BindingApplyResultView applyBindingProposal(
            UUID currentUserId,
            BindingProposal proposal,
            Map<UUID, UUID> createdDocuments,
            Map<UUID, UUID> createdBlocks
    ) {
        if (proposal.action() == BindingAction.UPSERT_BINDING) {
            UUID documentId = resolveDocumentId(proposal, createdDocuments);
            UUID blockId = resolveBlockId(proposal, createdBlocks);
            if (blockId != null) {
                DocumentBlock block = blockRepository.findById(blockId)
                        .orElseThrow(() -> new IllegalStateException(
                                "BindingProposal 引用的 Block 不存在"
                        ));
                if (!block.documentId().equals(documentId)) {
                    throw new IllegalStateException(
                            "BindingProposal 引用的 Block 不属于目标文档"
                    );
                }
            }
            CreateCodeBindingCommand command = new CreateCodeBindingCommand(
                    proposal.repositoryId(),
                    blockId,
                    proposal.filePath(),
                    proposal.revision(),
                    proposal.anchorKind(),
                    proposal.symbolKey(),
                    proposal.startLine(),
                    proposal.endLine(),
                    proposal.bindingRole(),
                    proposal.bindingOrdinal()
            );
            var existing = gitKnowledgeService.findExactBinding(
                    documentId,
                    currentUserId,
                    command
            );
            if (existing.isPresent()) {
                return new BindingApplyResultView(
                        proposal.id(), existing.get().id(), true
                );
            }
            try {
                CodeDocumentBinding created = gitKnowledgeService.createBinding(
                        documentId,
                        currentUserId,
                        command
                );
                return new BindingApplyResultView(
                        proposal.id(), created.id(), false
                );
            } catch (DuplicateCodeBindingException
                     | DataIntegrityViolationException exception) {
                var concurrent = gitKnowledgeService.findExactBinding(
                        documentId,
                        currentUserId,
                        command
                );
                if (concurrent.isEmpty()) {
                    throw exception;
                }
                return new BindingApplyResultView(
                        proposal.id(), concurrent.get().id(), true
                );
            }
        } else if (proposal.action() == BindingAction.REMOVE_BINDING) {
            gitKnowledgeService.deleteBinding(
                    proposal.bindingId(),
                    currentUserId
            );
        }
        return null;
    }

    private UUID resolveBlockId(
            BindingProposal proposal,
            Map<UUID, UUID> createdBlocks
    ) {
        if (proposal.blockId() != null) {
            return proposal.blockId();
        }
        if (proposal.createdBlockOperationId() == null) {
            return null;
        }
        UUID blockId = createdBlocks.get(proposal.createdBlockOperationId());
        if (blockId == null) {
            throw new IllegalStateException(
                    "新建 Block BindingProposal 尚未产生目标 Block"
            );
        }
        return blockId;
    }

    private void recordDecision(
            ChangeRequest request,
            UUID currentUserId,
            String action,
            String message,
            Instant createdAt
    ) {
        operationLogRepository.save(new DocumentOperationLog(
                UUID.randomUUID(),
                request.workspaceId(),
                null,
                action,
                message,
                currentUserId,
                "DOCUMENT_CHANGE_REQUEST",
                request.id(),
                createdAt
        ));
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("workspaceId", request.workspaceId());
        payload.put("changeRequestId", request.id());
        payload.put("status", request.status().name());
        payload.put("reviewedBy", currentUserId);
        payload.put("reviewedAt", createdAt);
        outboxEventPublisher.publish(
                "DOCUMENT_CHANGE_REQUEST",
                request.id(),
                action,
                payload
        );
    }

    private DetailView replayedDetail(ChangeRequest request) {
        DetailView detail = detail(request);
        return new DetailView(
                detail.request(),
                detail.operations(),
                detail.bindingProposals(),
                detail.requestEvidence(),
                true,
                detail.applyResult()
        );
    }

    private void requireAdmin(UUID workspaceId, UUID currentUserId) {
        com.devcollab.knowledgecore.workspace.application.WorkspaceView workspace;
        try {
            workspace = workspaceService.get(workspaceId, currentUserId);
        } catch (WorkspaceAccessDeniedException exception) {
            throw new WorkspaceNotFoundException();
        }
        if (workspace.currentUserRole() != WorkspaceRole.ADMIN) {
            throw new WorkspaceAccessDeniedException();
        }
    }

    protected void requireMember(UUID workspaceId, UUID currentUserId) {
        workspaceService.requireMembership(workspaceId, currentUserId);
    }

    private Operation buildOperation(
            UUID workspaceId,
            UUID requestId,
            CreateOperationCommand input,
            Map<String, Operation> savedByClientId
    ) {
        UUID createdDocumentOperationId = null;
        if (input.createdDocumentClientOperationId() != null) {
            Operation referenced = savedByClientId.get(
                    input.createdDocumentClientOperationId()
            );
            if (referenced == null
                    || referenced.operationType() != OperationType.CREATE_DOCUMENT) {
                throw operationInvalid(
                        "createdDocumentClientOperationId 必须引用更早的 CREATE_DOCUMENT"
                );
            }
            createdDocumentOperationId = referenced.id();
        }

        Document document = input.documentId() == null
                ? null
                : requireDocument(workspaceId, input.documentId());
        DocumentBlock block = input.blockId() == null
                ? null
                : requireBlock(document, input.blockId());
        String originalBlockType = null;
        String originalPlainText = null;
        Integer originalSchemaVersion = null;
        String originalContentJson = null;
        Integer originalSortOrder = null;
        String proposedPlainText = null;
        Integer proposedSchemaVersion = null;
        String proposedContentJson = null;

        switch (input.operationType()) {
            case CREATE_DOCUMENT -> {
                if (input.documentId() != null
                        || createdDocumentOperationId != null
                        || input.blockId() != null
                        || input.baseBlockVersion() != null) {
                    throw operationInvalid("CREATE_DOCUMENT 不能包含现有文档或 Block 目标");
                }
                requireText(input.proposedDocumentTitle(), 200, "文档标题");
                if (input.proposedParentDocumentId() != null) {
                    requireDocument(workspaceId, input.proposedParentDocumentId());
                }
            }
            case ADD_BLOCK -> {
                validateDocumentTarget(document, createdDocumentOperationId);
                if (input.blockId() != null || input.baseBlockVersion() != null) {
                    throw operationInvalid("ADD_BLOCK 不能包含 blockId 或 baseBlockVersion");
                }
                if (input.proposedBlockType() == null) {
                    throw operationInvalid("ADD_BLOCK 必须包含 proposedBlockType");
                }
                var normalized = contentCodec.normalize(
                        input.proposedBlockType(),
                        input.proposedPlainText(),
                        input.proposedContentSchemaVersion(),
                        input.proposedContent(),
                        input.proposedContentFormat()
                );
                proposedPlainText = normalized.text();
                proposedSchemaVersion = normalized.schemaVersion();
                proposedContentJson = normalized.documentJson();
            }
            case UPDATE_BLOCK -> {
                if (document == null || block == null
                        || input.baseBlockVersion() == null) {
                    throw operationInvalid(
                            "UPDATE_BLOCK 必须包含 documentId、blockId 和 baseBlockVersion"
                    );
                }
                if (input.proposedBlockType() != null
                        && input.proposedBlockType() != block.type()) {
                    throw operationInvalid("UPDATE_BLOCK 不允许改变 Block 类型");
                }
                originalBlockType = block.type().name();
                originalPlainText = block.text();
                originalSchemaVersion = block.contentSchemaVersion();
                originalContentJson = block.contentJson();
                originalSortOrder = block.sortOrder();
                var normalized = contentCodec.normalize(
                        block.type(),
                        input.proposedPlainText(),
                        input.proposedContentSchemaVersion(),
                        input.proposedContent(),
                        input.proposedContentFormat()
                );
                proposedPlainText = normalized.text();
                proposedSchemaVersion = normalized.schemaVersion();
                proposedContentJson = normalized.documentJson();
            }
            case DELETE_BLOCK -> {
                if (document == null || block == null
                        || input.baseBlockVersion() == null) {
                    throw operationInvalid(
                            "DELETE_BLOCK 必须包含 documentId、blockId 和 baseBlockVersion"
                    );
                }
                if (input.proposedPlainText() != null
                        || input.proposedContent() != null) {
                    throw operationInvalid("DELETE_BLOCK 不能包含建议正文");
                }
                originalBlockType = block.type().name();
                originalPlainText = block.text();
                originalSchemaVersion = block.contentSchemaVersion();
                originalContentJson = block.contentJson();
                originalSortOrder = block.sortOrder();
            }
        }

        return new Operation(
                UUID.randomUUID(),
                requestId,
                input.clientOperationId().trim(),
                input.sequenceNumber(),
                input.operationType(),
                input.documentId(),
                createdDocumentOperationId,
                input.blockId(),
                input.baseBlockVersion(),
                originalBlockType,
                originalPlainText,
                originalSchemaVersion,
                originalContentJson,
                originalSortOrder,
                trim(input.proposedDocumentTitle()),
                input.proposedDocumentType() == null
                        ? input.operationType() == OperationType.CREATE_DOCUMENT
                        ? DocumentType.REQUIREMENT.name() : null
                        : input.proposedDocumentType().name(),
                input.proposedParentDocumentId(),
                input.proposedBlockType() == null
                        ? null : input.proposedBlockType().name(),
                proposedPlainText,
                proposedSchemaVersion,
                proposedContentJson
        );
    }

    private BindingProposal buildBindingProposal(
            UUID workspaceId,
            UUID requestId,
            CreateBindingProposalCommand input,
            Map<String, Operation> savedByClientId
    ) {
        UUID createdDocumentOperationId = null;
        Operation documentCreator = null;
        if (input.createdDocumentClientOperationId() != null) {
            Operation creator = savedByClientId.get(
                    input.createdDocumentClientOperationId()
            );
            if (creator == null) {
                throw operationInvalid(
                        "Binding 引用的新建文档操作 "
                                + input.createdDocumentClientOperationId()
                                + " 必须在当前操作之前定义"
                );
            }
            if (creator.operationType() != OperationType.CREATE_DOCUMENT) {
                throw operationInvalid(
                        "Binding 引用的操作必须是 CREATE_DOCUMENT 类型"
                );
            }
            createdDocumentOperationId = creator.id();
            documentCreator = creator;
        }

        if (input.documentId() == null && createdDocumentOperationId == null) {
            throw operationInvalid("Binding 必须指定 documentId 或 createdDocumentClientOperationId");
        }

        if (input.documentId() != null && createdDocumentOperationId != null) {
            throw operationInvalid("Binding 必须指定 documentId 或 createdDocumentClientOperationId 的其中之一，不能同时指定");
        }

        if (input.documentId() != null) {
            requireDocument(workspaceId, input.documentId());
        }

        UUID createdBlockOperationId = null;
        if (input.blockId() != null) {
            Document targetDocument = input.documentId() == null
                    ? null : requireDocument(workspaceId, input.documentId());
            requireBlock(targetDocument, input.blockId());
            if (createdDocumentOperationId != null) {
                throw operationInvalid(
                        "现有 blockId 不能引用本请求新建的文档"
                );
            }
        }
        if (input.createdBlockClientOperationId() != null) {
            Operation blockCreator = savedByClientId.get(
                    input.createdBlockClientOperationId()
            );
            if (blockCreator == null
                    || blockCreator.operationType() != OperationType.ADD_BLOCK) {
                throw operationInvalid(
                        "createdBlockClientOperationId 必须引用更早的 ADD_BLOCK"
                );
            }
            boolean sameExistingDocument = input.documentId() != null
                    && input.documentId().equals(blockCreator.documentId())
                    && blockCreator.createdDocumentOperationId() == null;
            boolean sameCreatedDocument = documentCreator != null
                    && blockCreator.documentId() == null
                    && documentCreator.id().equals(
                    blockCreator.createdDocumentOperationId()
            );
            if (!sameExistingDocument && !sameCreatedDocument) {
                throw operationInvalid(
                        "Binding 引用的新建 Block 必须属于同一目标文档"
                );
            }
            createdBlockOperationId = blockCreator.id();
        }
        if (input.blockId() != null && createdBlockOperationId != null) {
            throw operationInvalid(
                    "Binding 不能同时引用现有 Block 和新建 Block"
            );
        }
        if (input.action() == BindingAction.REMOVE_BINDING
                && (createdDocumentOperationId != null
                || createdBlockOperationId != null)) {
            throw operationInvalid(
                    "REMOVE_BINDING 不能引用本请求新建的文档或 Block"
            );
        }

        gitRepository.findRepositoryById(input.repositoryId())
                .filter(item -> item.workspaceId().equals(workspaceId))
                .orElseThrow(() -> evidenceInvalid("目标代码仓库不存在或不属于工作区"));
        String filePath = normalizeBindingPath(input.filePath());
        CodeBindingAnchorValidator.ValidatedAnchor anchor =
                CodeBindingAnchorValidator.validate(
                        input.revision(),
                        input.anchorKind(),
                        input.symbolKey(),
                        input.startLine(),
                        input.endLine()
                );
        if (input.confidence() != null
                && (input.confidence() < 0 || input.confidence() > 1)) {
            throw operationInvalid("Binding confidence 必须在 0 到 1 之间");
        }
        if (input.bindingRole() == null) {
            throw operationInvalid("bindingRole 不能为空");
        }
        if ((input.bindingRole() == BindingRole.PRIMARY && input.bindingOrdinal() != 1)
                || (input.bindingRole() == BindingRole.SUPPORTING
                && input.bindingOrdinal() < 2)) {
            throw operationInvalid("bindingRole 与 bindingOrdinal 不匹配");
        }
        requireOptionalText(input.candidateId(), 100, "candidateId");
        requireOptionalText(
                input.documentAnchorCandidateId(),
                100,
                "documentAnchorCandidateId"
        );

        return new BindingProposal(
                UUID.randomUUID(),
                requestId,
                input.clientBindingProposalId(),
                input.sequenceNumber(),
                input.action(),
                input.repositoryId(),
                anchor.revision(),
                filePath,
                anchor.anchorKind(),
                anchor.symbolKey(),
                anchor.startLine(),
                anchor.endLine(),
                input.documentId(),
                createdDocumentOperationId,
                input.blockId(),
                createdBlockOperationId,
                input.bindingId(),
                trim(input.candidateId()),
                trim(input.documentAnchorCandidateId()),
                input.reason().trim(),
                input.confidence(),
                input.bindingRole(),
                input.bindingOrdinal(),
                Instant.now()
        );
    }

    private Evidence buildEvidence(
            UUID workspaceId,
            UUID requestId,
            CreateEvidenceCommand input,
            Map<String, Operation> savedByClientId
    ) {
        requireText(input.description(), 1000, "Evidence description");
        String filePath = normalizeEvidencePath(input.filePath());
        if ((input.startLine() == null) != (input.endLine() == null)
                || input.startLine() != null
                && (input.startLine() < 1
                || input.endLine() < input.startLine())) {
            throw evidenceInvalid("Evidence 行范围不合法");
        }
        Operation operation = input.clientOperationId() == null
                ? null
                : savedByClientId.get(input.clientOperationId());
        if (input.clientOperationId() != null && operation == null) {
            throw new DocumentChangeException(
                    HttpStatus.BAD_REQUEST,
                    "DOCUMENT_CHANGE_EVIDENCE_OPERATION_INVALID",
                    "Evidence 引用了不存在的 clientOperationId"
            );
        }
        GitRepository git = gitRepository.findRepositoryById(input.repositoryId())
                .filter(item -> item.workspaceId().equals(workspaceId))
                .orElseThrow(() -> evidenceInvalid("Evidence Repository 不属于工作区"));
        var file = gitRepository.findFileByRepositoryIdAndPath(
                        git.id(),
                        filePath
                )
                .orElseThrow(() -> evidenceInvalid("Evidence 文件不存在"));
        String excerpt = excerpt(
                file.contentText(),
                input.startLine(),
                input.endLine()
        );
        return new Evidence(
                UUID.randomUUID(),
                requestId,
                operation == null ? null : operation.id(),
                git.id(),
                trim(git.lastSyncedCommit()),
                filePath,
                input.startLine(),
                input.endLine(),
                input.description().trim(),
                file.blobSha(),
                excerpt,
                excerpt == null ? null : sha256(excerpt)
        );
    }

    private void validateCreateCommand(CreateCommand command) {
        if (command == null) {
            throw invalid("请求体不能为空");
        }
        requireText(command.clientRequestId(), 100, "clientRequestId");
        requireText(command.summary(), 300, "summary");
        requireText(command.rationale(), 10_000, "rationale");
        List<CreateOperationCommand> operations = safeList(command.operations());
        List<CreateBindingProposalCommand> bindingProposals = safeList(command.bindingProposals());
        if (operations.isEmpty() && bindingProposals.isEmpty()) {
            throw operationInvalid("Operations 和 Binding Proposals 不能同时为空");
        }
        if (operations.size() > 50) {
            throw operationInvalid("Operations 数量不能超过 50");
        }
        if (bindingProposals.size() > 50) {
            throw operationInvalid("Binding Proposals 数量不能超过 50");
        }
        if (safeList(command.evidence()).size() > 50) {
            throw evidenceInvalid("Evidence 数量不能超过 50");
        }
        java.util.Set<String> ids = new java.util.HashSet<>();
        java.util.Set<Integer> sequences = new java.util.HashSet<>();
        for (CreateOperationCommand operation : operations) {
            if (operation == null || operation.operationType() == null) {
                throw operationInvalid("Operation 类型不能为空");
            }
            requireText(operation.clientOperationId(), 100, "clientOperationId");
            if (!ids.add(operation.clientOperationId().trim())) {
                throw operationInvalid("clientOperationId 在请求内必须唯一");
            }
            if (operation.sequenceNumber() < 1
                    || !sequences.add(operation.sequenceNumber())) {
                throw operationInvalid("sequenceNumber 必须为唯一正整数");
            }
        }
        java.util.Set<String> bindingIds = new java.util.HashSet<>();
        for (CreateBindingProposalCommand proposal : bindingProposals) {
            if (proposal == null || proposal.action() == null) {
                throw operationInvalid("BindingProposal action 不能为空");
            }
            requireText(proposal.clientBindingProposalId(), 100, "clientBindingProposalId");
            if (!bindingIds.add(proposal.clientBindingProposalId().trim())) {
                throw operationInvalid("clientBindingProposalId 在请求内必须唯一");
            }
            if (proposal.sequenceNumber() < 1
                    || !sequences.add(proposal.sequenceNumber())) {
                throw operationInvalid("sequenceNumber 必须为唯一正整数");
            }
        }
        validateBindingRoleGroups(bindingProposals);
        int total = operations.size() + bindingProposals.size();
        for (int sequence = 1; sequence <= total; sequence++) {
            if (!sequences.contains(sequence)) {
                throw operationInvalid("sequenceNumber 必须从 1 连续递增");
            }
        }
    }

    private void validateBindingRoleGroups(
            List<CreateBindingProposalCommand> proposals
    ) {
        Map<String, List<CreateBindingProposalCommand>> groups = proposals.stream()
                .filter(item -> item.action() == BindingAction.UPSERT_BINDING)
                .collect(java.util.stream.Collectors.groupingBy(item ->
                        String.valueOf(item.documentId()) + ":"
                                + String.valueOf(item.createdDocumentClientOperationId()) + ":"
                                + String.valueOf(item.blockId()) + ":"
                                + String.valueOf(item.createdBlockClientOperationId())
                ));
        for (List<CreateBindingProposalCommand> group : groups.values()) {
            long primaryCount = group.stream()
                    .filter(item -> item.bindingRole() == BindingRole.PRIMARY)
                    .count();
            if (primaryCount != 1) {
                throw operationInvalid("每个文档 Block 必须且只能有一个 PRIMARY Binding");
            }
            List<Integer> ordinals = group.stream()
                    .map(CreateBindingProposalCommand::bindingOrdinal)
                    .sorted()
                    .toList();
            for (int index = 0; index < ordinals.size(); index++) {
                if (ordinals.get(index) != index + 1) {
                    throw operationInvalid("同一文档 Block 的 Binding ordinal 必须从 1 连续递增");
                }
            }
        }
    }

    private Document requireDocument(UUID workspaceId, UUID documentId) {
        Document document = documentRepository.findById(documentId)
                .filter(item -> item.workspaceId().equals(workspaceId))
                .orElseThrow(() -> operationInvalid("目标文档不存在或不属于工作区"));
        if (document.reviewStatus() != DocumentReviewStatus.DRAFT) {
            throw operationInvalid("目标文档当前不可编辑");
        }
        return document;
    }

    private DocumentBlock requireBlock(Document document, UUID blockId) {
        if (document == null) {
            throw operationInvalid("Block 操作必须指定 documentId");
        }
        return blockRepository.findById(blockId)
                .filter(item -> item.documentId().equals(document.id()))
                .orElseThrow(() -> operationInvalid("目标 Block 不属于指定文档"));
    }

    private void validateDocumentTarget(
            Document document,
            UUID createdDocumentOperationId
    ) {
        if ((document == null) == (createdDocumentOperationId == null)) {
            throw operationInvalid(
                    "ADD_BLOCK 必须且只能引用现有文档或本请求新建文档之一"
            );
        }
    }

    private CreateResult replay(ChangeRequest existing, String fingerprint) {
        if (!existing.requestFingerprint().equals(fingerprint)) {
            throw new DocumentChangeException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_CONFLICT",
                    "clientRequestId 已用于不同的变更请求"
            );
        }
        return new CreateResult(
                existing.id(),
                existing.status(),
                existing.createdAt(),
                true
        );
    }

    private CreateCommand normalizeCreateCommand(CreateCommand command) {
        List<CreateBindingProposalCommand> bindingProposals =
                safeList(command.bindingProposals()).stream()
                        .map(proposal -> new CreateBindingProposalCommand(
                                proposal.clientBindingProposalId(),
                                proposal.sequenceNumber(),
                                proposal.action(),
                                proposal.repositoryId(),
                                trim(proposal.revision()),
                                normalizeBindingPath(proposal.filePath()),
                                proposal.anchorKind() == null
                                        ? CodeAnchorKind.FILE : proposal.anchorKind(),
                                trim(proposal.symbolKey()),
                                proposal.startLine(),
                                proposal.endLine(),
                                proposal.documentId(),
                                proposal.createdDocumentClientOperationId(),
                                proposal.blockId(),
                                proposal.createdBlockClientOperationId(),
                                proposal.bindingId(),
                                trim(proposal.candidateId()),
                                trim(proposal.documentAnchorCandidateId()),
                                proposal.reason(),
                                proposal.confidence(),
                                proposal.bindingRole(),
                                proposal.bindingOrdinal()
                        ))
                        .toList();
        List<CreateEvidenceCommand> evidence = safeList(command.evidence()).stream()
                .map(item -> new CreateEvidenceCommand(
                        item.clientOperationId(),
                        item.repositoryId(),
                        normalizeEvidencePath(item.filePath()),
                        item.startLine(),
                        item.endLine(),
                        item.description()
                ))
                .toList();
        return new CreateCommand(
                command.clientRequestId(),
                command.summary(),
                command.rationale(),
                command.operations(),
                bindingProposals,
                evidence
        );
    }

    private String normalizeBindingPath(String path) {
        try {
            RepositoryPathValidator.validate(
                    path,
                    "Binding 文件路径不合法"
            );
            String normalized = RepositoryPathValidator.normalize(path);
            RepositoryPathValidator.validate(
                    normalized,
                    "Binding 文件路径不合法"
            );
            requireText(normalized, 1_000, "Binding filePath");
            return normalized;
        } catch (IllegalArgumentException exception) {
            throw operationInvalid("Binding 文件路径不合法");
        }
    }

    private String normalizeEvidencePath(String path) {
        try {
            RepositoryPathValidator.validate(
                    path,
                    "Evidence 文件路径不合法"
            );
            String normalized = RepositoryPathValidator.normalize(path);
            RepositoryPathValidator.validate(
                    normalized,
                    "Evidence 文件路径不合法"
            );
            return normalized;
        } catch (IllegalArgumentException exception) {
            throw evidenceInvalid("Evidence 文件路径不合法");
        }
    }

    private String fingerprint(CreateCommand command) {
        try {
            return sha256(objectMapper.writeValueAsString(command));
        } catch (JsonProcessingException exception) {
            throw invalid("变更请求无法序列化");
        }
    }

    private String excerpt(
            String content,
            Integer startLine,
            Integer endLine
    ) {
        if (content == null) {
            return null;
        }
        String[] lines = content.split("\\R", -1);
        int start = startLine == null ? 1 : startLine;
        int end = endLine == null ? Math.min(lines.length, 200) : endLine;
        if (start > lines.length || end > lines.length || end - start + 1 > 200) {
            throw evidenceInvalid("Evidence 行范围超出文件或超过 200 行");
        }
        String value = String.join(
                "\n",
                java.util.Arrays.copyOfRange(lines, start - 1, end)
        );
        if (value.length() > 16_000) {
            throw evidenceInvalid("Evidence 代码片段不能超过 16000 字符");
        }
        return value;
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void requireText(String value, int max, String field) {
        if (value == null || value.trim().isEmpty() || value.trim().length() > max) {
            throw invalid(field + " 必须为 1 到 " + max + " 个字符");
        }
    }

    private void requireOptionalText(String value, int max, String field) {
        if (value != null) {
            requireText(value, max, field);
        }
    }

    private DocumentChangeException operationInvalid(String message) {
        return new DocumentChangeException(
                HttpStatus.BAD_REQUEST,
                "DOCUMENT_CHANGE_OPERATION_INVALID",
                message
        );
    }

    private DocumentChangeException evidenceInvalid(String message) {
        return new DocumentChangeException(
                HttpStatus.BAD_REQUEST,
                "DOCUMENT_CHANGE_EVIDENCE_INVALID",
                message
        );
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    protected DocumentChangeException invalid(String message) {
        return new DocumentChangeException(
                HttpStatus.BAD_REQUEST,
                "DOCUMENT_CHANGE_REQUEST_INVALID",
                message
        );
    }

    protected DocumentChangeException notFound(String message) {
        return new DocumentChangeException(
                HttpStatus.NOT_FOUND,
                "DOCUMENT_CHANGE_REQUEST_NOT_FOUND",
                message
        );
    }

    protected DocumentChangeException conflict(
            String code,
            String message
    ) {
        return new DocumentChangeException(
                HttpStatus.CONFLICT,
                code,
                message
        );
    }
}
