package com.devcollab.knowledgecore.document.version.infrastructure;

import com.devcollab.knowledgecore.document.version.domain.DocumentVersion;
import com.devcollab.knowledgecore.document.version.domain.DocumentVersionRepository;
import com.devcollab.knowledgecore.document.version.domain.DocumentVersionStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@Profile("in-memory")
public class InMemoryDocumentVersionRepository implements DocumentVersionRepository {

    private final Map<UUID, DocumentVersion> versions =
            new ConcurrentHashMap<>();

    @Override
    public DocumentVersion save(DocumentVersion version) {
        versions.put(version.id(), version);
        return version;
    }

    @Override
    public void supersedeCurrentVersions(UUID documentId) {
        versions.replaceAll((id, version) ->
                version.documentId().equals(documentId)
                        && version.status() == DocumentVersionStatus.CURRENT
                        ? new DocumentVersion(
                                version.id(),
                                version.documentId(),
                                version.versionNo(),
                                version.title(),
                                DocumentVersionStatus.SUPERSEDED,
                                version.snapshotPayload(),
                                version.publishedBy(),
                                version.publishedAt()
                        )
                        : version);
    }

    @Override
    public int nextVersionNo(UUID documentId) {
        return versions.values().stream()
                .filter(version -> version.documentId().equals(documentId))
                .mapToInt(DocumentVersion::versionNo)
                .max()
                .orElse(0) + 1;
    }

    @Override
    public Optional<DocumentVersion> findById(UUID versionId) {
        return Optional.ofNullable(versions.get(versionId));
    }

    @Override
    public List<DocumentVersion> findAllByDocumentId(UUID documentId) {
        return versions.values().stream()
                .filter(version -> version.documentId().equals(documentId))
                .sorted(Comparator
                        .comparing(DocumentVersion::versionNo)
                        .reversed())
                .toList();
    }
}
