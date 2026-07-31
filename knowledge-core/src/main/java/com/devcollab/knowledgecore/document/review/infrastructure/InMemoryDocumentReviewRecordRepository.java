package com.devcollab.knowledgecore.document.review.infrastructure;

import com.devcollab.knowledgecore.document.review.domain.DocumentReviewRecord;
import com.devcollab.knowledgecore.document.review.domain.DocumentReviewRecordRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@Profile("in-memory")
public class InMemoryDocumentReviewRecordRepository
        implements DocumentReviewRecordRepository {

    private final Map<UUID, DocumentReviewRecord> records =
            new ConcurrentHashMap<>();

    @Override
    public DocumentReviewRecord save(DocumentReviewRecord record) {
        records.put(record.id(), record);
        return record;
    }

    @Override
    public List<DocumentReviewRecord> findAllByDocumentId(UUID documentId) {
        return records.values().stream()
                .filter(record -> record.documentId().equals(documentId))
                .sorted(Comparator
                        .comparing(DocumentReviewRecord::createdAt)
                        .reversed())
                .toList();
    }
}
