package com.devcollab.knowledgecore.document.candidate.application;

import com.devcollab.knowledgecore.common.util.RepositoryPathValidator;
import com.devcollab.knowledgecore.document.core.domain.Document;
import com.devcollab.knowledgecore.document.core.domain.DocumentBlock;
import com.devcollab.knowledgecore.document.core.domain.DocumentBlockRepository;
import com.devcollab.knowledgecore.document.core.domain.DocumentRepository;
import com.devcollab.knowledgecore.git.application.exception.GitRepositoryNotFoundException;
import com.devcollab.knowledgecore.git.domain.CodeDocumentBinding;
import com.devcollab.knowledgecore.git.domain.GitKnowledgeRepository;
import com.devcollab.knowledgecore.git.domain.GitRepository;
import com.devcollab.knowledgecore.workspace.application.WorkspaceApplicationService;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class DocumentCandidateApplicationService {

    public static final int DIRECT_BINDING_WEIGHT = 100;
    public static final int TITLE_EXACT_WEIGHT = 60;
    public static final int FILE_NAME_WEIGHT = 40;
    public static final int TITLE_TOKEN_WEIGHT = 30;
    public static final int PATH_TOKEN_WEIGHT = 20;
    public static final int BLOCK_TEXT_WEIGHT = 15;
    private static final int MAX_QUERY_CHARACTERS = 500;
    private static final int MAX_LIMIT = 100;
    private static final Set<String> IGNORED_PATH_TOKENS = Set.of(
            "src", "main", "test", "java", "resources", "com", "org", "net"
    );

    private final WorkspaceApplicationService workspaceService;
    private final DocumentRepository documentRepository;
    private final DocumentBlockRepository blockRepository;
    private final GitKnowledgeRepository gitRepository;

    public DocumentCandidateApplicationService(
            WorkspaceApplicationService workspaceService,
            DocumentRepository documentRepository,
            DocumentBlockRepository blockRepository,
            GitKnowledgeRepository gitRepository
    ) {
        this.workspaceService = workspaceService;
        this.documentRepository = documentRepository;
        this.blockRepository = blockRepository;
        this.gitRepository = gitRepository;
    }

    public DocumentCandidateResult findCandidates(
            UUID workspaceId,
            UUID repositoryId,
            String filePath,
            String query,
            Integer limit,
            UUID currentUserId
    ) {
        workspaceService.requireMembership(workspaceId, currentUserId);
        Inputs inputs = validateInputs(repositoryId, filePath, query, limit);
        if (inputs.repositoryId() != null) {
            requireRepository(inputs.repositoryId(), workspaceId);
        }

        List<Document> documents = documentRepository.findAllByWorkspaceId(workspaceId);
        Map<UUID, CandidateAccumulator> candidates = new HashMap<>();
        Map<UUID, List<DocumentBlock>> blocksByDocument = new HashMap<>();
        for (Document document : documents) {
            blocksByDocument.put(document.id(), blockRepository.findAllByDocumentId(document.id()));
        }

        if (inputs.filePath() != null) {
            collectDirectBindings(
                    workspaceId, inputs.repositoryId(), inputs.filePath(), documents, candidates
            );
        }

        Set<String> queryTokens = tokenize(inputs.query());
        FileTerms fileTerms = fileTerms(inputs.filePath());
        for (Document document : documents) {
            List<DocumentBlock> blocks = blocksByDocument.getOrDefault(document.id(), List.of());
            String normalizedTitle = normalize(document.title());

            if (inputs.query() != null && normalizedTitle.equals(normalize(inputs.query()))) {
                candidate(candidates, document).addReason(
                        "TITLE_EXACT", TITLE_EXACT_WEIGHT, inputs.query(), List.of()
                );
            }

            if (!queryTokens.isEmpty() && queryTokens.stream().anyMatch(normalizedTitle::contains)) {
                candidate(candidates, document).addReason(
                        "TITLE_TOKEN", TITLE_TOKEN_WEIGHT, firstMatch(normalizedTitle, queryTokens), List.of()
                );
            }

            List<UUID> queryBlockMatches = matchingBlocks(blocks, queryTokens);
            if (!queryBlockMatches.isEmpty()) {
                candidate(candidates, document).addReason(
                        "BLOCK_TEXT", BLOCK_TEXT_WEIGHT, firstMatchingBlockTerm(blocks, queryTokens),
                        queryBlockMatches
                );
            }

            if (!fileTerms.fileNames().isEmpty()
                    && (matchesAny(normalizedTitle, fileTerms.fileNames())
                    || !matchingBlocks(blocks, fileTerms.fileNames()).isEmpty())) {
                List<UUID> blockMatches = matchingBlocks(blocks, fileTerms.fileNames());
                candidate(candidates, document).addReason(
                        "FILE_NAME", FILE_NAME_WEIGHT,
                        firstMatchAcross(normalizedTitle, blocks, fileTerms.fileNames()), blockMatches
                );
            }

            if (!fileTerms.pathTokens().isEmpty()
                    && (matchesAny(normalizedTitle, fileTerms.pathTokens())
                    || !matchingBlocks(blocks, fileTerms.pathTokens()).isEmpty())) {
                List<UUID> blockMatches = matchingBlocks(blocks, fileTerms.pathTokens());
                candidate(candidates, document).addReason(
                        "PATH_TOKEN", PATH_TOKEN_WEIGHT,
                        firstMatchAcross(normalizedTitle, blocks, fileTerms.pathTokens()), blockMatches
                );
            }
        }

        List<DocumentCandidateItem> ordered = candidates.values().stream()
                .map(CandidateAccumulator::toItem)
                .filter(item -> item.score() > 0)
                .sorted(Comparator
                        .comparingInt(DocumentCandidateItem::score).reversed()
                        .thenComparing(DocumentCandidateItem::title,
                                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                        .thenComparing(DocumentCandidateItem::documentId))
                .toList();
        int returnedCount = Math.min(inputs.limit(), ordered.size());
        List<DocumentCandidateItem> returned = ordered.subList(0, returnedCount);
        return new DocumentCandidateResult(
                workspaceId, inputs.repositoryId(), inputs.filePath(), inputs.query(), returned,
                ordered.size() > returnedCount, ordered.size() - returnedCount
        );
    }

    private Inputs validateInputs(UUID repositoryId, String filePath, String query, Integer limit) {
        String normalizedQuery = query == null ? null : query.trim();
        String normalizedPath = filePath == null ? null : filePath.trim();
        if (query != null && normalizedQuery.isEmpty()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (normalizedQuery != null
                && normalizedQuery.codePointCount(0, normalizedQuery.length()) > MAX_QUERY_CHARACTERS) {
            throw new IllegalArgumentException("query exceeds maximum length");
        }
        if (normalizedPath != null && normalizedPath.isEmpty()) {
            throw new IllegalArgumentException("filePath must not be blank");
        }
        if (normalizedPath == null && normalizedQuery == null) {
            throw new IllegalArgumentException("filePath or query is required");
        }
        if (normalizedPath != null && repositoryId == null) {
            throw new IllegalArgumentException("repositoryId is required with filePath");
        }
        if (normalizedPath != null) {
            RepositoryPathValidator.validate(normalizedPath, "filePath must be repository-relative");
            normalizedPath = RepositoryPathValidator.normalize(normalizedPath);
        }
        int effectiveLimit = limit == null ? 20 : limit;
        if (effectiveLimit < 1 || effectiveLimit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        return new Inputs(repositoryId, normalizedPath, normalizedQuery, effectiveLimit);
    }

    private void collectDirectBindings(
            UUID workspaceId,
            UUID repositoryId,
            String filePath,
            List<Document> documents,
            Map<UUID, CandidateAccumulator> candidates
    ) {
        Map<UUID, Document> documentsById = new HashMap<>();
        for (Document document : documents) {
            documentsById.put(document.id(), document);
        }
        Set<UUID> seenBindingIds = new HashSet<>();
        for (CodeDocumentBinding binding : gitRepository.findBindingsByRepositoryId(repositoryId)) {
            if (!seenBindingIds.add(binding.id())
                    || !binding.workspaceId().equals(workspaceId)
                    || !matchesPath(binding.pathPattern(), filePath)) {
                continue;
            }
            Document document = documentsById.get(binding.documentId());
            if (document == null) {
                continue;
            }
            CandidateAccumulator accumulator = candidate(candidates, document);
            accumulator.existingBindingCount++;
            if (binding.blockId() != null) {
                accumulator.matchedBlockIds.add(binding.blockId());
            }
            accumulator.addReason(
                    "DIRECT_BINDING", DIRECT_BINDING_WEIGHT, filePath,
                    binding.blockId() == null ? List.of() : List.of(binding.blockId())
            );
        }
    }

    private GitRepository requireRepository(UUID repositoryId, UUID workspaceId) {
        GitRepository repository = gitRepository.findRepositoryById(repositoryId)
                .orElseThrow(GitRepositoryNotFoundException::new);
        if (!repository.workspaceId().equals(workspaceId)) {
            throw new GitRepositoryNotFoundException();
        }
        return repository;
    }

    private CandidateAccumulator candidate(
            Map<UUID, CandidateAccumulator> candidates,
            Document document
    ) {
        return candidates.computeIfAbsent(
                document.id(), ignored -> new CandidateAccumulator(document.id(), document.title())
        );
    }

    private static List<UUID> matchingBlocks(List<DocumentBlock> blocks, Set<String> terms) {
        if (terms.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<UUID> matches = new LinkedHashSet<>();
        for (DocumentBlock block : blocks) {
            if (matchesAny(normalize(block.text()), terms)) {
                matches.add(block.id());
            }
        }
        return List.copyOf(matches);
    }

    private static String firstMatchingBlockTerm(List<DocumentBlock> blocks, Set<String> terms) {
        for (DocumentBlock block : blocks) {
            String text = normalize(block.text());
            String match = firstMatch(text, terms);
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private static String firstMatchAcross(
            String normalizedTitle,
            List<DocumentBlock> blocks,
            Set<String> terms
    ) {
        String titleMatch = firstMatch(normalizedTitle, terms);
        return titleMatch != null ? titleMatch : firstMatchingBlockTerm(blocks, terms);
    }

    private static boolean matchesAny(String value, Set<String> terms) {
        return firstMatch(value, terms) != null;
    }

    private static String firstMatch(String value, Set<String> terms) {
        if (value == null) {
            return null;
        }
        return terms.stream().sorted().filter(value::contains).findFirst().orElse(null);
    }

    private static Set<String> tokenize(String value) {
        if (value == null) {
            return Set.of();
        }
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (String token : normalize(value).split("[^\\p{L}\\p{N}]+")) {
            if (token.codePointCount(0, token.length()) >= 2) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static FileTerms fileTerms(String path) {
        if (path == null) {
            return new FileTerms(Set.of(), Set.of());
        }
        String[] segments = path.split("/");
        String rawFileName = segments[segments.length - 1];
        String fileName = normalize(rawFileName);
        int dot = fileName.lastIndexOf('.');
        String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (fileName.codePointCount(0, fileName.length()) >= 2) {
            names.add(fileName);
        }
        if (stem.codePointCount(0, stem.length()) >= 2) {
            names.add(stem);
        }
        String rawStem = rawFileName.lastIndexOf('.') > 0
                ? rawFileName.substring(0, rawFileName.lastIndexOf('.'))
                : rawFileName;
        names.addAll(tokenize(rawStem.replaceAll("(?<=[a-z0-9])(?=[A-Z])", " ")));
        LinkedHashSet<String> pathTokens = new LinkedHashSet<>();
        for (int index = 0; index < segments.length - 1; index++) {
            for (String token : tokenize(segments[index])) {
                if (!IGNORED_PATH_TOKENS.contains(token)) {
                    pathTokens.add(token);
                }
            }
        }
        return new FileTerms(names, pathTokens);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean matchesPath(String pattern, String path) {
        if (pattern.endsWith("/**")) {
            return path.startsWith(pattern.substring(0, pattern.length() - 2));
        }
        if (pattern.startsWith("**/*.")) {
            return path.endsWith(pattern.substring(4));
        }
        return pattern.equals(path);
    }

    private record Inputs(UUID repositoryId, String filePath, String query, int limit) {
    }

    private record FileTerms(Set<String> fileNames, Set<String> pathTokens) {
    }

    private static final class CandidateAccumulator {
        private final UUID documentId;
        private final String title;
        private final Map<String, DocumentCandidateMatchReason> reasons = new LinkedHashMap<>();
        private final Set<UUID> matchedBlockIds = new LinkedHashSet<>();
        private int existingBindingCount;

        private CandidateAccumulator(UUID documentId, String title) {
            this.documentId = documentId;
            this.title = title;
        }

        private void addReason(String code, int weight, String matchedTerm, List<UUID> blockIds) {
            matchedBlockIds.addAll(blockIds);
            reasons.computeIfAbsent(code, ignored -> new DocumentCandidateMatchReason(
                    code, weight, matchedTerm, List.copyOf(new LinkedHashSet<>(blockIds))
            ));
        }

        private DocumentCandidateItem toItem() {
            List<DocumentCandidateMatchReason> orderedReasons = reasons.values().stream()
                    .sorted(Comparator.comparing(DocumentCandidateMatchReason::code))
                    .toList();
            int score = orderedReasons.stream().mapToInt(DocumentCandidateMatchReason::weight).sum();
            return new DocumentCandidateItem(
                    documentId, title, score, orderedReasons,
                    matchedBlockIds.stream().sorted().toList(), existingBindingCount
            );
        }
    }
}
