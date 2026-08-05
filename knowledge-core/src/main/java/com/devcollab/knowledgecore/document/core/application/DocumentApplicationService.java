package com.devcollab.knowledgecore.document.core.application;

import com.devcollab.knowledgecore.common.cache.CacheKey;
import com.devcollab.knowledgecore.common.cache.ApprovedAdrCacheService;
import com.devcollab.knowledgecore.common.cache.PublishedDocumentCacheService;
import com.devcollab.knowledgecore.common.outbox.application.OutboxEventPublisher;
import com.devcollab.knowledgecore.common.outbox.application.OutboxEventTypes;
import com.devcollab.knowledgecore.document.core.application.exception.DocumentNotFoundException;
import com.devcollab.knowledgecore.document.core.application.exception.DocumentParentCycleException;
import com.devcollab.knowledgecore.document.version.application.exception.DocumentVersionNotFoundException;
import com.devcollab.knowledgecore.document.core.application.exception.InvalidDocumentParentException;
import com.devcollab.knowledgecore.document.review.application.exception.InvalidDocumentReviewStatusException;
import com.devcollab.knowledgecore.document.core.domain.Document;
import com.devcollab.knowledgecore.document.core.domain.DocumentBlock;
import com.devcollab.knowledgecore.document.core.domain.DocumentBlockRepository;
import com.devcollab.knowledgecore.document.collaboration.domain.DocumentOperationLog;
import com.devcollab.knowledgecore.document.collaboration.domain.DocumentOperationLogRepository;
import com.devcollab.knowledgecore.document.core.domain.DocumentRepository;
import com.devcollab.knowledgecore.document.core.domain.DocumentReviewStatus;
import com.devcollab.knowledgecore.document.core.domain.DocumentType;
import com.devcollab.knowledgecore.document.version.domain.DocumentVersion;
import com.devcollab.knowledgecore.document.version.domain.DocumentVersionRepository;
import com.devcollab.knowledgecore.document.version.domain.DocumentVersionStatus;
import com.devcollab.knowledgecore.workspace.application.WorkspacePermissionPolicy;
import com.devcollab.knowledgecore.workspace.application.WorkspaceApplicationService;
import com.devcollab.knowledgecore.workspace.application.exception.WorkspaceAccessDeniedException;
import com.devcollab.knowledgecore.workspace.domain.WorkspaceMember;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.devcollab.knowledgecore.document.block.application.DocumentStructureDto;
import com.devcollab.knowledgecore.document.block.application.DocumentBlockStructureDto;
import com.devcollab.knowledgecore.document.block.application.DocumentBlockContentCodec;
import com.devcollab.knowledgecore.document.tree.application.DocumentTreeCacheService;
import com.devcollab.knowledgecore.document.review.application.ReviewDocumentCommand;

@Service
public class DocumentApplicationService {

    private final DocumentRepository documentRepository;
    private final DocumentBlockRepository blockRepository;
    private final DocumentVersionRepository versionRepository;
    private final DocumentOperationLogRepository operationLogRepository;
    private final WorkspaceApplicationService workspaceService;
    private final WorkspacePermissionPolicy permissionPolicy;
    private final OutboxEventPublisher outboxEventPublisher;
    private final ObjectMapper objectMapper;
    private final DocumentBlockContentCodec blockContentCodec;
    private final DocumentTreeCacheService treeCache;
    private final PublishedDocumentCacheService publishedDocumentCache;
    private final ApprovedAdrCacheService approvedAdrCache;

    public DocumentApplicationService(
            DocumentRepository documentRepository,
            DocumentBlockRepository blockRepository,
            DocumentVersionRepository versionRepository,
            DocumentOperationLogRepository operationLogRepository,
            WorkspaceApplicationService workspaceService,
            WorkspacePermissionPolicy permissionPolicy,
            OutboxEventPublisher outboxEventPublisher,
            ObjectMapper objectMapper,
            DocumentBlockContentCodec blockContentCodec,
            DocumentTreeCacheService treeCache,
            PublishedDocumentCacheService publishedDocumentCache,
            ApprovedAdrCacheService approvedAdrCache
    ) {
        this.documentRepository = documentRepository;
        this.blockRepository = blockRepository;
        this.versionRepository = versionRepository;
        this.operationLogRepository = operationLogRepository;
        this.workspaceService = workspaceService;
        this.permissionPolicy = permissionPolicy;
        this.outboxEventPublisher = outboxEventPublisher;
        this.objectMapper = objectMapper;
        this.blockContentCodec = blockContentCodec;
        this.treeCache = treeCache;
        this.publishedDocumentCache = publishedDocumentCache;
        this.approvedAdrCache = approvedAdrCache;
    }

    @Transactional
    public Document create(
            UUID workspaceId,
            UUID currentUserId,
            CreateDocumentCommand command
    ) {
        workspaceService.requireMembership(workspaceId, currentUserId);
        validateParent(workspaceId, command.parentDocumentId());

        Instant now = Instant.now();
        Document document = new Document(
                UUID.randomUUID(),
                workspaceId,
                command.parentDocumentId(),
                command.title().trim(),
                command.documentType() == null
                        ? DocumentType.REQUIREMENT
                        : command.documentType(),
                DocumentReviewStatus.DRAFT,
                currentUserId,
                now,
                now
        );
        Document saved = documentRepository.save(document);
        logOperation(
                saved,
                "DOCUMENT_CREATED",
                "创建文档：" + saved.title(),
                currentUserId,
                "DOCUMENT",
                saved.id(),
                now
        );
        publishDocumentEvent("DOCUMENT_CREATED", saved, currentUserId);
        publishDocumentOperationApplied(
                "DOCUMENT_CREATED",
                saved,
                currentUserId,
                "DOCUMENT",
                saved.id()
        );
        treeCache.evictTree(workspaceId);
        publishDocumentTreeCacheInvalidated(workspaceId, currentUserId);
        return saved;
    }

    public List<Document> listTreeSource(
            UUID workspaceId,
            UUID currentUserId
    ) {
        workspaceService.requireMembership(workspaceId, currentUserId);
        return treeCache.listTreeSource(workspaceId);
    }

    public Document get(UUID documentId, UUID currentUserId) {
        Document document = requireDocument(documentId);
        workspaceService.requireMembership(
                document.workspaceId(),
                currentUserId
        );
        return document;
    }

    @Transactional
    public Document getForUpdate(UUID documentId, UUID currentUserId) {
        Document document = documentRepository.findByIdForUpdate(documentId)
                .orElseThrow(DocumentNotFoundException::new);
        workspaceService.requireMembership(
                document.workspaceId(),
                currentUserId
        );
        return document;
    }

    public DocumentStructureDto getDocumentStructure(
            UUID workspaceId,
            UUID documentId,
            UUID currentUserId,
            boolean includeBlockContent,
            Integer maxBlocks,
            Integer maxContentCharacters
    ) {
        workspaceService.requireMembership(workspaceId, currentUserId);
        Document document = requireDocument(documentId);
        if (!document.workspaceId().equals(workspaceId)) {
            throw new DocumentNotFoundException();
        }

        List<DocumentBlock> allBlocks = new java.util.ArrayList<>(blockRepository.findAllByDocumentId(documentId));
        allBlocks.sort(java.util.Comparator
                .comparingInt(DocumentBlock::sortOrder)
                .thenComparing(DocumentBlock::id));

        int totalBlocks = allBlocks.size();
        boolean isTruncated = false;
        int omittedBlockCount = 0;
        List<DocumentBlock> visibleBlocks = allBlocks;

        if (maxBlocks != null && maxBlocks > 0 && totalBlocks > maxBlocks) {
            visibleBlocks = allBlocks.subList(0, maxBlocks);
            isTruncated = true;
            omittedBlockCount = totalBlocks - maxBlocks;
        }

        int remainingChars = (maxContentCharacters != null && maxContentCharacters > 0)
                ? maxContentCharacters : Integer.MAX_VALUE;
        int totalTruncatedChars = 0;
        if (includeBlockContent && omittedBlockCount > 0) {
            for (int index = visibleBlocks.size(); index < allBlocks.size(); index++) {
                totalTruncatedChars += contentCharacterCount(allBlocks.get(index));
            }
        }
        List<DocumentBlockStructureDto> blockDtos = new java.util.ArrayList<>();
        for (DocumentBlock block : visibleBlocks) {
            String plainText = null;
            String content = null;
            boolean isContentTruncated = false;

            if (includeBlockContent) {
                String sourceText = block.text();
                if (sourceText != null && !sourceText.isEmpty()) {
                    int codePoints = sourceText.codePointCount(0, sourceText.length());
                    if (remainingChars > 0 && codePoints <= remainingChars) {
                        plainText = sourceText;
                        remainingChars -= codePoints;
                    } else if (remainingChars > 0) {
                        int cutoff = sourceText.offsetByCodePoints(0, remainingChars);
                        plainText = sourceText.substring(0, cutoff);
                        totalTruncatedChars += (codePoints - remainingChars);
                        remainingChars = 0;
                        isContentTruncated = true;
                        isTruncated = true;
                    } else {
                        totalTruncatedChars += codePoints;
                        isContentTruncated = true;
                        isTruncated = true;
                    }
                }
                String contentJson = block.contentJson();
                if (contentJson != null && !contentJson.isEmpty()) {
                    int codePoints = contentJson.codePointCount(0, contentJson.length());
                    if (remainingChars > 0 && codePoints <= remainingChars) {
                        content = contentJson;
                        remainingChars -= codePoints;
                    } else {
                        totalTruncatedChars += codePoints;
                        isContentTruncated = true;
                        isTruncated = true;
                    }
                }
            }

            blockDtos.add(new DocumentBlockStructureDto(
                    block.id(),
                    block.type().name(),
                    block.sortOrder(),
                    block.version(),
                    plainText,
                    content,
                    isContentTruncated
            ));
        }

        return new DocumentStructureDto(
                document.id(),
                document.workspaceId(),
                document.title(),
                document.documentType().name(),
                document.reviewStatus().name(),
                document.updatedAt(),
                blockDtos,
                isTruncated,
                omittedBlockCount,
                totalTruncatedChars
        );
    }

    private int contentCharacterCount(DocumentBlock block) {
        int count = 0;
        if (block.text() != null) {
            count += block.text().codePointCount(0, block.text().length());
        }
        if (block.contentJson() != null) {
            count += block.contentJson().codePointCount(0, block.contentJson().length());
        }
        return count;
    }

    @Transactional
    public Document update(
            UUID documentId,
            UUID currentUserId,
            UpdateDocumentCommand command
    ) {
        Document document = requireDocument(documentId);
        workspaceService.requireMembership(
                document.workspaceId(),
                currentUserId
        );
        ensureEditable(document);

        Document updated = new Document(
                document.id(),
                document.workspaceId(),
                document.parentDocumentId(),
                command.title().trim(),
                document.documentType(),
                document.reviewStatus(),
                document.createdBy(),
                document.createdAt(),
                Instant.now()
        );
        Document saved = documentRepository.save(updated);
        logOperation(
                saved,
                "DOCUMENT_UPDATED",
                "更新文档标题：" + saved.title(),
                currentUserId,
                "DOCUMENT",
                saved.id(),
                saved.updatedAt()
        );
        publishDocumentEvent("DOCUMENT_UPDATED", saved, currentUserId);
        publishDocumentOperationApplied(
                "DOCUMENT_UPDATED",
                saved,
                currentUserId,
                "DOCUMENT",
                saved.id()
        );
        treeCache.evictTree(document.workspaceId());
        publishDocumentTreeCacheInvalidated(
                document.workspaceId(),
                currentUserId
        );
        return saved;
    }

    @Transactional
    public Document move(
            UUID documentId,
            UUID currentUserId,
            MoveDocumentCommand command
    ) {
        Document document = requireDocument(documentId);
        workspaceService.requireMembership(
                document.workspaceId(),
                currentUserId
        );
        ensureEditable(document);
        validateParent(document.workspaceId(), command.parentDocumentId());
        validateNoCycle(document, command.parentDocumentId());

        Document moved = new Document(
                document.id(),
                document.workspaceId(),
                command.parentDocumentId(),
                document.title(),
                document.documentType(),
                document.reviewStatus(),
                document.createdBy(),
                document.createdAt(),
                Instant.now()
        );
        Document saved = documentRepository.save(moved);
        logOperation(
                saved,
                "DOCUMENT_MOVED",
                "移动文档层级：" + saved.title(),
                currentUserId,
                "DOCUMENT",
                saved.id(),
                saved.updatedAt()
        );
        publishDocumentEvent("DOCUMENT_MOVED", saved, currentUserId);
        publishDocumentOperationApplied(
                "DOCUMENT_MOVED",
                saved,
                currentUserId,
                "DOCUMENT",
                saved.id()
        );
        treeCache.evictTree(saved.workspaceId());
        publishDocumentTreeCacheInvalidated(saved.workspaceId(), currentUserId);
        return saved;
    }

    @Transactional
    public void delete(UUID documentId, UUID currentUserId) {
        Document document = requireDocument(documentId);
        workspaceService.requireMembership(
                document.workspaceId(),
                currentUserId
        );
        logOperation(
                document,
                "DOCUMENT_DELETED",
                "删除文档：" + document.title(),
                currentUserId,
                "DOCUMENT",
                document.id(),
                Instant.now()
        );
        documentRepository.deleteById(documentId);
        publishDocumentEvent("DOCUMENT_DELETED", document, currentUserId);
        publishDocumentOperationApplied(
                "DOCUMENT_DELETED",
                document,
                currentUserId,
                "DOCUMENT",
                document.id()
        );
        invalidatePublishedVersionCaches(document, currentUserId);
        treeCache.evictTree(document.workspaceId());
        publishDocumentTreeCacheInvalidated(
                document.workspaceId(),
                currentUserId
        );
    }

    @Transactional
    public Document submitReview(UUID documentId, UUID currentUserId) {
        Document document = requireDocument(documentId);
        workspaceService.requireMembership(
                document.workspaceId(),
                currentUserId
        );
        ensureEditable(document);
        if (document.reviewStatus() != DocumentReviewStatus.DRAFT
                && document.reviewStatus() != DocumentReviewStatus.REJECTED
                && document.reviewStatus() != DocumentReviewStatus.PUBLISHED) {
            throw new InvalidDocumentReviewStatusException();
        }

        Document submitted = new Document(
                document.id(),
                document.workspaceId(),
                document.parentDocumentId(),
                document.title(),
                document.documentType(),
                DocumentReviewStatus.IN_REVIEW,
                document.createdBy(),
                document.createdAt(),
                Instant.now()
        );
        Document saved = documentRepository.save(submitted);
        logOperation(
                saved,
                "DOCUMENT_REVIEW_SUBMITTED",
                "提交评审：" + saved.title(),
                currentUserId,
                "DOCUMENT",
                saved.id(),
                saved.updatedAt()
        );
        publishDocumentEvent(
                "DOCUMENT_REVIEW_SUBMITTED",
                saved,
                currentUserId
        );
        publishDocumentEvent(
                OutboxEventTypes.REVIEW_REQUESTED,
                saved,
                currentUserId
        );
        return saved;
    }

    @Transactional
    public Document approveReview(
            UUID documentId,
            UUID currentUserId,
            ReviewDocumentCommand command
    ) {
        Document document = requireDocument(documentId);
        requireWorkspaceAdmin(document.workspaceId(), currentUserId);
        if (document.reviewStatus() != DocumentReviewStatus.IN_REVIEW) {
            throw new InvalidDocumentReviewStatusException();
        }

        Instant now = Instant.now();
        Document approved = new Document(
                document.id(),
                document.workspaceId(),
                document.parentDocumentId(),
                document.title(),
                document.documentType(),
                DocumentReviewStatus.PUBLISHED,
                document.createdBy(),
                document.createdAt(),
                now
        );
        Document saved = documentRepository.save(approved);
        DocumentVersion version = createPublishedVersion(saved, currentUserId, now);
        logOperation(
                saved,
                "DOCUMENT_REVIEW_APPROVED",
                "通过评审并发布 v" + version.versionNo(),
                currentUserId,
                "DOCUMENT_VERSION",
                version.id(),
                now
        );
        publishDocumentEvent(
                "DOCUMENT_REVIEW_APPROVED",
                saved,
                currentUserId
        );
        publishDocumentEvent(
                OutboxEventTypes.REVIEW_COMPLETED,
                saved,
                currentUserId
        );
        publishDocumentVersionEvent(saved, version, currentUserId);
        publishSnapshotRequestedEvent(saved, version, currentUserId);
        invalidatePublishedVersionCaches(saved, currentUserId);
        return saved;
    }

    @Transactional
    public Document rejectReview(
            UUID documentId,
            UUID currentUserId,
            ReviewDocumentCommand command
    ) {
        Document document = requireDocument(documentId);
        requireWorkspaceAdmin(document.workspaceId(), currentUserId);
        if (document.reviewStatus() != DocumentReviewStatus.IN_REVIEW) {
            throw new InvalidDocumentReviewStatusException();
        }

        Document rejected = new Document(
                document.id(),
                document.workspaceId(),
                document.parentDocumentId(),
                document.title(),
                document.documentType(),
                DocumentReviewStatus.REJECTED,
                document.createdBy(),
                document.createdAt(),
                Instant.now()
        );
        Document saved = documentRepository.save(rejected);
        logOperation(
                saved,
                "DOCUMENT_REVIEW_REJECTED",
                "驳回评审：" + saved.title(),
                currentUserId,
                "DOCUMENT",
                saved.id(),
                saved.updatedAt()
        );
        publishDocumentEvent(
                "DOCUMENT_REVIEW_REJECTED",
                saved,
                currentUserId
        );
        publishDocumentEvent(
                OutboxEventTypes.REVIEW_FAILED,
                saved,
                currentUserId
        );
        return saved;
    }

    @Transactional
    public Document deprecate(UUID documentId, UUID currentUserId) {
        Document document = requireDocument(documentId);
        requireWorkspaceAdmin(document.workspaceId(), currentUserId);
        if (document.reviewStatus() == DocumentReviewStatus.DEPRECATED) {
            throw new InvalidDocumentReviewStatusException();
        }

        Instant now = Instant.now();
        Document deprecated = new Document(
                document.id(),
                document.workspaceId(),
                document.parentDocumentId(),
                document.title(),
                document.documentType(),
                DocumentReviewStatus.DEPRECATED,
                document.createdBy(),
                document.createdAt(),
                now
        );
        Document saved = documentRepository.save(deprecated);
        logOperation(
                saved,
                "DOCUMENT_DEPRECATED",
                "废弃文档：" + saved.title(),
                currentUserId,
                "DOCUMENT",
                saved.id(),
                now
        );
        publishDocumentEvent(
                "DOCUMENT_DEPRECATED",
                saved,
                currentUserId
        );
        publishDocumentOperationApplied(
                "DOCUMENT_DEPRECATED",
                saved,
                currentUserId,
                "DOCUMENT",
                saved.id()
        );
        invalidatePublishedVersionCaches(saved, currentUserId);
        return saved;
    }

    public List<DocumentVersion> listVersions(
            UUID documentId,
            UUID currentUserId
    ) {
        Document document = requireDocument(documentId);
        workspaceService.requireMembership(
                document.workspaceId(),
                currentUserId
        );
        return versionRepository.findAllByDocumentId(documentId);
    }

    public DocumentVersion getVersion(
            UUID documentId,
            UUID versionId,
            UUID currentUserId
    ) {
        Document document = requireDocument(documentId);
        workspaceService.requireMembership(
                document.workspaceId(),
                currentUserId
        );
        java.util.function.Supplier<DocumentVersion> source = () ->
                versionRepository.findById(versionId)
                        .filter(version -> version.documentId().equals(documentId))
                        .orElseThrow(DocumentVersionNotFoundException::new);
        UUID workspaceId = document.workspaceId();
        if (document.documentType() == DocumentType.ADR) {
            return approvedAdrCache.get(workspaceId, documentId, versionId, source);
        }
        return publishedDocumentCache.get(workspaceId, documentId, versionId, source);
    }

    public List<DocumentOperationLog> listTimeline(
            UUID documentId,
            UUID currentUserId
    ) {
        Document document = requireDocument(documentId);
        workspaceService.requireMembership(
                document.workspaceId(),
                currentUserId
        );
        return operationLogRepository.findAllByDocumentId(documentId);
    }

    public DocumentOperationLog logDocumentOperation(
            Document document,
            String action,
            String message,
            UUID operatorUserId,
            String targetType,
            UUID targetId,
            Instant createdAt
    ) {
        return logOperation(
                document,
                action,
                message,
                operatorUserId,
                targetType,
                targetId,
                createdAt
        );
    }

    private void validateParent(
            UUID workspaceId,
            UUID parentDocumentId
    ) {
        if (parentDocumentId == null) {
            return;
        }

        Document parent = requireDocument(parentDocumentId);
        if (!parent.workspaceId().equals(workspaceId)) {
            throw new InvalidDocumentParentException();
        }
    }

    private void validateNoCycle(Document document, UUID parentDocumentId) {
        if (parentDocumentId == null) {
            return;
        }
        if (document.id().equals(parentDocumentId)) {
            throw new DocumentParentCycleException();
        }

        List<Document> documents = documentRepository.findAllByWorkspaceId(
                document.workspaceId()
        );
        UUID cursor = parentDocumentId;
        while (cursor != null) {
            UUID current = cursor;
            if (document.id().equals(current)) {
                throw new DocumentParentCycleException();
            }
            Document parent = documents.stream()
                    .filter(candidate -> candidate.id().equals(current))
                    .findFirst()
                    .orElse(null);
            cursor = parent == null ? null : parent.parentDocumentId();
        }
    }

    private Document requireDocument(UUID documentId) {
        return documentRepository.findById(documentId)
                .orElseThrow(DocumentNotFoundException::new);
    }

    public void ensureEditable(Document document) {
        if (document.reviewStatus() == DocumentReviewStatus.DEPRECATED) {
            throw new InvalidDocumentReviewStatusException();
        }
    }

    private void requireWorkspaceAdmin(UUID workspaceId, UUID currentUserId) {
        WorkspaceMember member = workspaceService.requireMembership(
                workspaceId,
                currentUserId
        );
        if (!permissionPolicy.isAdmin(member)) {
            throw new WorkspaceAccessDeniedException();
        }
    }

    private DocumentVersion createPublishedVersion(
            Document document,
            UUID currentUserId,
            Instant publishedAt
    ) {
        versionRepository.supersedeCurrentVersions(document.id());
        DocumentVersion version = new DocumentVersion(
                UUID.randomUUID(),
                document.id(),
                versionRepository.nextVersionNo(document.id()),
                document.title(),
                DocumentVersionStatus.CURRENT,
                snapshotPayload(document, publishedAt),
                currentUserId,
                publishedAt
        );
        return versionRepository.save(version);
    }

    private DocumentOperationLog logOperation(
            Document document,
            String action,
            String message,
            UUID operatorUserId,
            String targetType,
            UUID targetId,
            Instant createdAt
    ) {
        DocumentOperationLog operationLog = new DocumentOperationLog(
                UUID.randomUUID(),
                document.workspaceId(),
                document.id(),
                action,
                message,
                operatorUserId,
                targetType,
                targetId,
                createdAt
        );
        return operationLogRepository.save(operationLog);
    }

    private String normalizeComment(String comment) {
        if (comment == null || comment.isBlank()) {
            return null;
        }
        return comment.trim();
    }

    private String snapshotPayload(Document document, Instant publishedAt) {
        List<DocumentBlock> blocks = blockRepository.findAllByDocumentId(
                document.id()
        );
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("documentId", document.id());
        snapshot.put("title", document.title());
        snapshot.put("documentType", document.documentType());
        snapshot.put("publishedAt", publishedAt);
        snapshot.put("blocks", blocks.stream()
                .map(block -> {
                    Map<String, Object> blockSnapshot = new LinkedHashMap<>();
                    blockSnapshot.put("id", block.id());
                    blockSnapshot.put("type", block.type());
                    blockSnapshot.put("text", block.text());
                    blockSnapshot.put(
                            "contentSchemaVersion",
                            blockContentCodec.schemaVersion(block)
                    );
                    blockSnapshot.put(
                            "contentJson",
                            blockContentCodec.document(block).toString()
                    );
                    blockSnapshot.put("sortOrder", block.sortOrder());
                    blockSnapshot.put("version", block.version());
                    return blockSnapshot;
                })
                .toList());

        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("文档发布快照生成失败", exception);
        }
    }

    private void publishDocumentEvent(
            String eventType,
            Document document,
            UUID currentUserId
    ) {
        outboxEventPublisher.publish(
                "DOCUMENT",
                document.id(),
                eventType,
                documentPayload(document, currentUserId)
        );
    }

    private void publishDocumentVersionEvent(
            Document document,
            DocumentVersion version,
            UUID currentUserId
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("workspaceId", document.workspaceId());
        payload.put("documentId", document.id());
        payload.put("versionId", version.id());
        payload.put("versionNo", version.versionNo());
        payload.put("operatorUserId", currentUserId);
        payload.put("publishedAt", version.publishedAt());
        outboxEventPublisher.publish(
                "DOCUMENT_VERSION",
                version.id(),
                OutboxEventTypes.DOCUMENT_VERSION_PUBLISHED,
                payload
        );
    }

    private void publishSnapshotRequestedEvent(
            Document document,
            DocumentVersion version,
            UUID currentUserId
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("workspaceId", document.workspaceId());
        payload.put("documentId", document.id());
        payload.put("versionId", version.id());
        payload.put("versionNo", version.versionNo());
        payload.put("operatorUserId", currentUserId);
        payload.put("requestedAt", version.publishedAt());
        payload.put("reason", "DOCUMENT_VERSION_PUBLISHED");
        outboxEventPublisher.publish(
                "DOCUMENT_VERSION",
                version.id(),
                OutboxEventTypes.SNAPSHOT_REQUESTED,
                payload
        );
    }

    private void publishDocumentOperationApplied(
            String operationType,
            Document document,
            UUID currentUserId,
            String targetType,
            UUID targetId
    ) {
        Map<String, Object> payload = new LinkedHashMap<>(
                documentPayload(document, currentUserId)
        );
        payload.put("operationType", operationType);
        payload.put("targetType", targetType);
        payload.put("targetId", targetId);
        outboxEventPublisher.publish(
                "DOCUMENT",
                document.id(),
                OutboxEventTypes.DOCUMENT_OPERATION_APPLIED,
                payload
        );
    }

    private void publishDocumentTreeCacheInvalidated(
            UUID workspaceId,
            UUID currentUserId
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("workspaceId", workspaceId);
        payload.put("cacheName", "workspace-documents-tree");
        payload.put("cacheKey", CacheKey.documentTree(workspaceId));
        payload.put("operatorUserId", currentUserId);
        payload.put("invalidatedAt", Instant.now());
        outboxEventPublisher.publish(
                "CACHE",
                workspaceId,
                OutboxEventTypes.CACHE_INVALIDATED,
                payload
        );
    }

    private void invalidatePublishedVersionCaches(
            Document document,
            UUID currentUserId
    ) {
        UUID workspaceId = document.workspaceId();
        String publishedPattern = CacheKey.publishedDocumentPrefix(workspaceId, document.id());
        publishedDocumentCache.invalidate(publishedPattern);
        publishCacheInvalidated(
                document,
                "published-document",
                publishedPattern,
                currentUserId
        );
        if (document.documentType() == DocumentType.ADR) {
            String adrPattern = CacheKey.approvedAdrPrefix(workspaceId, document.id());
            approvedAdrCache.invalidate(adrPattern);
            publishCacheInvalidated(
                    document,
                    "approved-adr",
                    adrPattern,
                    currentUserId
            );
        }
    }

    private void publishCacheInvalidated(
            Document document,
            String cacheName,
            String cacheKey,
            UUID currentUserId
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("workspaceId", document.workspaceId());
        payload.put("documentId", document.id());
        payload.put("cacheName", cacheName);
        payload.put("cacheKey", cacheKey);
        payload.put("operatorUserId", currentUserId);
        payload.put("invalidatedAt", Instant.now());
        outboxEventPublisher.publish(
                "CACHE",
                document.id(),
                OutboxEventTypes.CACHE_INVALIDATED,
                payload
        );
    }

    private Map<String, Object> documentPayload(
            Document document,
            UUID currentUserId
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("workspaceId", document.workspaceId());
        payload.put("documentId", document.id());
        payload.put("parentDocumentId", document.parentDocumentId());
        payload.put("title", document.title());
        payload.put("documentType", document.documentType());
        payload.put("reviewStatus", document.reviewStatus());
        payload.put("operatorUserId", currentUserId);
        payload.put("updatedAt", document.updatedAt());
        return payload;
    }
}
