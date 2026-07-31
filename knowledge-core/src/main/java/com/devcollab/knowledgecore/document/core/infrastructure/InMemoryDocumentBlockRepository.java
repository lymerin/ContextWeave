package com.devcollab.knowledgecore.document.core.infrastructure;

import com.devcollab.knowledgecore.document.core.domain.DocumentBlock;
import com.devcollab.knowledgecore.document.core.domain.DocumentBlockRepository;
import org.springframework.stereotype.Repository;
import org.springframework.context.annotation.Profile;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@Profile("in-memory")
public class InMemoryDocumentBlockRepository
        implements DocumentBlockRepository {

    private final Map<UUID, DocumentBlock> blocks =
            new ConcurrentHashMap<>();

    @Override
    public DocumentBlock save(DocumentBlock block) {
        blocks.put(block.id(), block);
        return block;
    }

    @Override
    public List<DocumentBlock> saveAll(List<DocumentBlock> documentBlocks) {
        documentBlocks.forEach(this::save);
        return documentBlocks;
    }

    @Override
    public Optional<DocumentBlock> findById(UUID blockId) {
        return Optional.ofNullable(blocks.get(blockId));
    }

    @Override
    public List<DocumentBlock> findAllByDocumentId(UUID documentId) {
        return blocks.values().stream()
                .filter(block -> block.documentId().equals(documentId))
                .sorted(Comparator.comparingInt(DocumentBlock::sortOrder))
                .toList();
    }

    @Override
    public Optional<DocumentBlock> updateContentIfVersionMatches(
            UUID blockId,
            String text,
            int contentSchemaVersion,
            String contentJson,
            java.time.Instant updatedAt,
            long expectedVersion
    ) {
        DocumentBlock current = blocks.get(blockId);
        if (current == null || current.version() != expectedVersion) {
            return Optional.empty();
        }

        DocumentBlock updated = current.updateContent(
                text,
                contentSchemaVersion,
                contentJson,
                updatedAt
        );
        blocks.put(blockId, updated);
        return Optional.of(updated);
    }

    @Override
    public boolean deleteIfVersionMatches(
            UUID blockId,
            long expectedVersion
    ) {
        DocumentBlock block = blocks.get(blockId);
        if (block == null || block.version() != expectedVersion) {
            return false;
        }
        return blocks.remove(blockId, block);
    }

    @Override
    public void deleteById(UUID blockId) {
        blocks.remove(blockId);
    }
}
