package com.devcollab.knowledgecore.git.application;

import com.devcollab.knowledgecore.document.core.application.CreateDocumentBlockCommand;
import com.devcollab.knowledgecore.document.core.application.CreateDocumentCommand;
import com.devcollab.knowledgecore.document.core.application.DocumentApplicationService;
import com.devcollab.knowledgecore.document.core.application.DocumentBlockApplicationService;
import com.devcollab.knowledgecore.document.core.domain.Document;
import com.devcollab.knowledgecore.document.core.domain.DocumentBlockType;
import com.devcollab.knowledgecore.document.core.domain.DocumentType;
import com.devcollab.knowledgecore.git.domain.GitRepositoryFile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class GitMarkdownImportService {

    private static final int BLOCK_TEXT_LIMIT = 19_000;

    private final GitKnowledgeApplicationService gitService;
    private final DocumentApplicationService documentService;
    private final DocumentBlockApplicationService blockService;
    private final JdbcTemplate jdbcTemplate;

    public GitMarkdownImportService(
            GitKnowledgeApplicationService gitService,
            DocumentApplicationService documentService,
            DocumentBlockApplicationService blockService,
            JdbcTemplate jdbcTemplate
    ) {
        this.gitService = gitService;
        this.documentService = documentService;
        this.blockService = blockService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public GitMarkdownImportResult importReadyMarkdown(
            UUID workspaceId,
            UUID repositoryId,
            UUID currentUserId
    ) {
        List<GitRepositoryFile> markdownFiles = gitService.listFiles(
                        workspaceId, repositoryId, currentUserId
                ).stream()
                .filter(file -> isMarkdown(file.path()))
                .sorted(Comparator.comparing(GitRepositoryFile::path))
                .toList();

        Map<String, UUID> imported = importedDocuments(repositoryId);
        Map<String, UUID> directories = new LinkedHashMap<>();
        int created = 0;
        int skipped = 0;
        int unavailable = 0;

        for (GitRepositoryFile file : markdownFiles) {
            if (imported.containsKey(file.path())) {
                skipped++;
                continue;
            }
            if (file.contentText() == null || file.contentText().isBlank()) {
                unavailable++;
                continue;
            }

            UUID parentId = ensureDirectories(
                    workspaceId, repositoryId, currentUserId,
                    parentSegments(file.path()), imported, directories
            );
            Document document = documentService.create(
                    workspaceId,
                    currentUserId,
                    new CreateDocumentCommand(
                            parentId,
                            title(file.path(), file.contentText()),
                            documentType(file.path())
                    )
            );
            for (String chunk : chunks(file.contentText())) {
                blockService.create(
                        document.id(),
                        currentUserId,
                        new CreateDocumentBlockCommand(
                                DocumentBlockType.PARAGRAPH,
                                chunk,
                                null,
                                null
                        )
                );
            }
            recordImport(
                    workspaceId, repositoryId, file.path(), file.blobSha(),
                    document.id()
            );
            imported.put(file.path(), document.id());
            created++;
        }
        return new GitMarkdownImportResult(created, skipped, unavailable);
    }

    private UUID ensureDirectories(
            UUID workspaceId,
            UUID repositoryId,
            UUID currentUserId,
            List<String> segments,
            Map<String, UUID> imported,
            Map<String, UUID> directories
    ) {
        UUID parentId = null;
        StringBuilder path = new StringBuilder();
        for (String segment : segments) {
            path.append(segment).append('/');
            String sourcePath = path.toString();
            UUID existing = directories.get(sourcePath);
            if (existing == null) {
                existing = imported.get(sourcePath);
            }
            if (existing == null) {
                Document directory = documentService.create(
                        workspaceId,
                        currentUserId,
                        new CreateDocumentCommand(
                                parentId, segment, DocumentType.REQUIREMENT
                        )
                );
                existing = directory.id();
                recordImport(
                        workspaceId, repositoryId, sourcePath,
                        "directory", existing
                );
                imported.put(sourcePath, existing);
            }
            directories.put(sourcePath, existing);
            parentId = existing;
        }
        return parentId;
    }

    private Map<String, UUID> importedDocuments(UUID repositoryId) {
        Map<String, UUID> result = new LinkedHashMap<>();
        jdbcTemplate.query(
                """
                SELECT source_path, document_id
                  FROM git_document_imports
                 WHERE repository_id = ?
                """,
                (rs, rowNum) -> Map.entry(
                        rs.getString("source_path"),
                        rs.getObject("document_id", UUID.class)
                ),
                repositoryId
        ).forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }

    private void recordImport(
            UUID workspaceId,
            UUID repositoryId,
            String sourcePath,
            String blobSha,
            UUID documentId
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO git_document_imports
                    (id, workspace_id, repository_id, source_path,
                     source_blob_sha, document_id, imported_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), workspaceId, repositoryId, sourcePath,
                blobSha, documentId, Timestamp.from(Instant.now())
        );
    }

    static boolean isMarkdown(String path) {
        String value = path.toLowerCase(Locale.ROOT);
        return value.endsWith(".md") || value.endsWith(".markdown");
    }

    static String title(String path, String content) {
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.matches("^#\\s+.+")) {
                return normalizeTitle(trimmed.substring(2));
            }
        }
        String filename = path.substring(path.lastIndexOf('/') + 1);
        int dot = filename.lastIndexOf('.');
        return normalizeTitle(dot > 0 ? filename.substring(0, dot) : filename);
    }

    private static String normalizeTitle(String value) {
        String title = value.trim();
        int badge = title.indexOf("[![");
        if (badge > 0) {
            title = title.substring(0, badge).trim();
        }
        return title.length() <= 200 ? title : title.substring(0, 200).trim();
    }

    static List<String> parentSegments(String path) {
        int slash = path.lastIndexOf('/');
        if (slash < 0) {
            return List.of();
        }
        return List.of(path.substring(0, slash).split("/"));
    }

    static DocumentType documentType(String path) {
        String value = path.toLowerCase(Locale.ROOT);
        if (value.contains("adr")) return DocumentType.ADR;
        if (value.contains("api")) return DocumentType.API;
        if (value.contains("architect")) return DocumentType.ARCHITECTURE;
        if (value.contains("deploy")) return DocumentType.DEPLOYMENT;
        if (value.contains("test")) return DocumentType.TEST;
        if (value.contains("database") || value.contains("schema")) {
            return DocumentType.DATABASE;
        }
        return DocumentType.REQUIREMENT;
    }

    static List<String> chunks(String content) {
        String normalized = content.trim();
        List<String> result = new ArrayList<>();
        for (int start = 0; start < normalized.length(); start += BLOCK_TEXT_LIMIT) {
            result.add(normalized.substring(
                    start,
                    Math.min(start + BLOCK_TEXT_LIMIT, normalized.length())
            ));
        }
        return result;
    }
}
