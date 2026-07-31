package com.devcollab.knowledgecore.document.review.infrastructure;

import com.devcollab.knowledgecore.document.review.domain.ReviewIssue;
import com.devcollab.knowledgecore.document.review.domain.ReviewIssueRepository;
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
public class InMemoryReviewIssueRepository implements ReviewIssueRepository {

    private final Map<UUID, ReviewIssue> issues = new ConcurrentHashMap<>();

    @Override
    public ReviewIssue save(ReviewIssue issue) {
        issues.put(issue.id(), issue);
        return issue;
    }

    @Override
    public Optional<ReviewIssue> findById(UUID issueId) {
        return Optional.ofNullable(issues.get(issueId));
    }

    @Override
    public List<ReviewIssue> findAllByDocumentVersionId(
            UUID documentVersionId
    ) {
        return issues.values().stream()
                .filter(issue -> issue.documentVersionId()
                        .equals(documentVersionId))
                .sorted(Comparator
                        .comparing(ReviewIssue::createdAt)
                        .reversed())
                .toList();
    }
}
