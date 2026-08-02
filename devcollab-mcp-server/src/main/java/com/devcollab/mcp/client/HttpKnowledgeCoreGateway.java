package com.devcollab.mcp.client;

import com.devcollab.mcp.error.McpToolErrorCode;
import com.devcollab.mcp.error.McpToolException;
import com.devcollab.mcp.security.McpUserIdentity;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.UUID;
import java.util.Map;

@Component
public class HttpKnowledgeCoreGateway implements KnowledgeCoreGateway {

    private final RestClient restClient;

    public HttpKnowledgeCoreGateway(RestClient knowledgeCoreRestClient) {
        this.restClient = knowledgeCoreRestClient;
    }

    @Override
    public WorkspaceContext getWorkspaceContext(UUID workspaceId, McpUserIdentity identity) {
        try {
            WorkspacePayload workspace = get(
                    "/api/v1/workspaces/{workspaceId}",
                    identity,
                    WorkspacePayload.class,
                    workspaceId
            );
            List<RepositoryPayload> repositories = restClient.get()
                    .uri("/api/v1/workspaces/{workspaceId}/git/repositories", workspaceId)
                    .header(HttpHeaders.AUTHORIZATION, bearer(identity))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return new WorkspaceContext(
                    workspace.id(),
                    workspace.name(),
                    workspace.currentUserRole(),
                    repositories == null ? List.of() : repositories.stream()
                            .map(repository -> new RepositoryContext(
                                    repository.id(),
                                    repository.name(),
                                    repository.provider(),
                                    repository.remoteUrl(),
                                    repository.defaultBranch(),
                                    repository.syncStatus(),
                                    repository.lastSyncedCommit()
                            ))
                            .toList()
            );
        } catch (RuntimeException exception) {
            throw map(exception, McpToolErrorCode.WORKSPACE_NOT_FOUND);
        }
    }

    @Override
    public RepositorySource readRepositorySource(
            UUID workspaceId,
            UUID repositoryId,
            String path,
            McpUserIdentity identity
    ) {
        try {
            SourcePayload source = restClient.get()
                    .uri(builder -> builder
                            .path("/api/v1/workspaces/{workspaceId}/git/repositories/{repositoryId}/source")
                            .queryParam("path", path)
                            .build(workspaceId, repositoryId))
                    .header(HttpHeaders.AUTHORIZATION, bearer(identity))
                    .retrieve()
                    .body(SourcePayload.class);
            if (source == null) {
                throw new McpToolException(McpToolErrorCode.FILE_NOT_FOUND, "Repository file was not found");
            }
            return new RepositorySource(
                    source.repositoryId(),
                    source.commitSha(),
                    source.path(),
                    source.sizeBytes(),
                    source.language(),
                    source.readable(),
                    source.content()
            );
        } catch (RuntimeException exception) {
            throw map(exception, McpToolErrorCode.FILE_NOT_FOUND);
        }
    }

    @Override
    public DocumentStructure getDocumentStructure(UUID workspaceId, UUID documentId, boolean includeBlockContent, int maxBlocks, int maxContentChars, McpUserIdentity identity) {
        try {
            DocumentStructurePayload payload = restClient.get()
                    .uri(builder -> builder
                            .path("/api/v1/workspaces/{workspaceId}/documents/{documentId}/structure")
                            .queryParam("includeBlockContent", includeBlockContent)
                            .queryParam("maxBlocks", maxBlocks)
                            .queryParam("maxContentCharacters", maxContentChars)
                            .build(workspaceId, documentId))
                    .header(HttpHeaders.AUTHORIZATION, bearer(identity))
                    .retrieve()
                    .body(DocumentStructurePayload.class);
            if (payload == null) {
                throw new McpToolException(McpToolErrorCode.DOCUMENT_NOT_FOUND, "Document structure not found");
            }
            return new DocumentStructure(
                    payload.documentId(),
                    payload.workspaceId(),
                    payload.title(),
                    payload.documentType(),
                    payload.reviewStatus(),
                    payload.updatedAt(),
                    payload.blocks() == null ? List.of() : payload.blocks().stream()
                            .map(b -> new BlockInfo(b.blockId(), b.blockType(), b.sortOrder(), b.version(), b.plainText(), b.content(), b.contentTruncated()))
                            .toList(),
                    payload.truncated(),
                    payload.omittedBlockCount(),
                    payload.omittedCharacterCount()
            );
        } catch (RuntimeException exception) {
            throw map(exception, McpToolErrorCode.DOCUMENT_NOT_FOUND);
        }
    }

    @Override
    public BindingQueryResult getFileBindings(UUID workspaceId, UUID repositoryId, String filePath, int maxBindings, McpUserIdentity identity) {
        try {
            BindingQueryResultPayload payload = restClient.get()
                    .uri(builder -> builder.path("/api/v1/workspaces/{workspaceId}/repositories/{repositoryId}/code-bindings")
                            .queryParam("filePath", filePath)
                            .queryParam("maxBindings", maxBindings)
                            .build(workspaceId, repositoryId))
                    .header(HttpHeaders.AUTHORIZATION, bearer(identity))
                    .retrieve()
                    .body(BindingQueryResultPayload.class);
            if (payload == null) {
                throw new McpToolException(McpToolErrorCode.INTERNAL_ERROR, "Binding query returned empty response");
            }
            return new BindingQueryResult(
                    payload.workspaceId(),
                    payload.repositoryId(),
                    payload.filePath(),
                    payload.fileHasBindings(),
                    payload.bindings() == null ? List.of() : payload.bindings().stream()
                            .map(b -> new BindingInfo(
                                    b.bindingId(), b.pathPattern(), b.documentId(),
                                    b.documentTitle(), b.blockId(),
                                    b.revision(), b.anchorKind(), b.symbolKey(),
                                    b.startLine(), b.endLine(),
                                    b.bindingRole(),
                                    b.bindingOrdinal() == null ? 1 : b.bindingOrdinal()))
                            .toList(),
                    payload.truncated(),
                    payload.omittedBindingCount()
            );
        } catch (RuntimeException exception) {
            throw map(exception, McpToolErrorCode.FILE_NOT_FOUND);
        }
    }

    @Override
    public DocumentCandidateResult findDocumentCandidates(
            UUID workspaceId,
            UUID repositoryId,
            String filePath,
            String query,
            int limit,
            McpUserIdentity identity
    ) {
        try {
            DocumentCandidateResultPayload payload = restClient.get()
                    .uri(builder -> builder
                            .path("/api/v1/workspaces/{workspaceId}/document-candidates")
                            .queryParamIfPresent("repositoryId", java.util.Optional.ofNullable(repositoryId))
                            .queryParamIfPresent("filePath", java.util.Optional.ofNullable(filePath))
                            .queryParamIfPresent("query", java.util.Optional.ofNullable(query))
                            .queryParam("limit", limit)
                            .build(workspaceId))
                    .header(HttpHeaders.AUTHORIZATION, bearer(identity))
                    .retrieve()
                    .body(DocumentCandidateResultPayload.class);
            if (payload == null) {
                throw new McpToolException(
                        McpToolErrorCode.INTERNAL_ERROR,
                        "Knowledge Core returned an empty candidate response"
                );
            }
            return new DocumentCandidateResult(
                    payload.workspaceId(), payload.repositoryId(), payload.filePath(), payload.query(),
                    payload.candidates() == null ? List.of() : payload.candidates().stream()
                            .map(candidate -> new DocumentCandidate(
                                    candidate.documentId(), candidate.title(), candidate.score(),
                                    candidate.matchReasons() == null ? List.of()
                                            : candidate.matchReasons().stream()
                                                    .map(reason -> new DocumentCandidateMatchReason(
                                                            reason.code(), reason.weight(), reason.matchedTerm(),
                                                            reason.matchedBlockIds() == null
                                                                    ? List.of() : reason.matchedBlockIds()
                                                    ))
                                                    .toList(),
                                    candidate.matchedBlockIds() == null ? List.of() : candidate.matchedBlockIds(),
                                    candidate.existingBindingCount()
                            ))
                            .toList(),
                    payload.truncated(), payload.omittedCandidateCount()
            );
        } catch (RuntimeException exception) {
            if (exception instanceof RestClientResponseException responseException
                    && responseException.getStatusCode().value() == 400) {
                throw new McpToolException(
                        McpToolErrorCode.INVALID_DOCUMENT_QUERY,
                        "Document candidate query was invalid"
                );
            }
            throw map(exception, McpToolErrorCode.REPOSITORY_NOT_FOUND);
        }
    }

    @Override
    public RepositoryFilePage listRepositoryFiles(
            UUID workspaceId,
            UUID repositoryId,
            String pathPrefix,
            boolean recursive,
            String cursor,
            int limit,
            McpUserIdentity identity
    ) {
        try {
            RepositoryFilePagePayload payload = restClient.get()
                    .uri(builder -> builder
                            .path("/api/v1/workspaces/{workspaceId}/repositories/{repositoryId}/repository-files")
                            .queryParamIfPresent("pathPrefix", java.util.Optional.ofNullable(pathPrefix))
                            .queryParam("recursive", recursive)
                            .queryParamIfPresent("cursor", java.util.Optional.ofNullable(cursor))
                            .queryParam("limit", limit)
                            .build(workspaceId, repositoryId))
                    .header(HttpHeaders.AUTHORIZATION, bearer(identity))
                    .retrieve()
                    .body(RepositoryFilePagePayload.class);
            if (payload == null) {
                throw new McpToolException(McpToolErrorCode.INTERNAL_ERROR, "File page was empty");
            }
            return new RepositoryFilePage(
                    payload.workspaceId(), payload.repositoryId(),
                    payload.revision(), payload.pathPrefix(), payload.recursive(),
                    payload.files() == null ? List.of() : payload.files().stream()
                            .map(file -> new RepositoryFileInfo(
                                    file.filePath(), file.fileName(), file.extension(),
                                    file.sizeBytes(), file.language(), file.readable(),
                                    file.isDirectory()
                            )).toList(),
                    payload.nextCursor(), payload.hasMore()
            );
        } catch (RuntimeException exception) {
            throw map(exception, McpToolErrorCode.REPOSITORY_NOT_FOUND);
        }
    }

    @Override
    public RepositoryChangePage listRepositoryChanges(
            UUID workspaceId,
            UUID repositoryId,
            String cursor,
            int limit,
            McpUserIdentity identity
    ) {
        try {
            RepositoryChangePagePayload payload = restClient.get()
                    .uri(builder -> builder
                            .path("/api/v1/workspaces/{workspaceId}/repositories/{repositoryId}/repository-changes")
                            .queryParamIfPresent("cursor", java.util.Optional.ofNullable(cursor))
                            .queryParam("limit", limit)
                            .build(workspaceId, repositoryId))
                    .header(HttpHeaders.AUTHORIZATION, bearer(identity))
                    .retrieve()
                    .body(RepositoryChangePagePayload.class);
            if (payload == null) {
                throw new McpToolException(McpToolErrorCode.INTERNAL_ERROR, "Change page was empty");
            }
            return new RepositoryChangePage(
                    payload.workspaceId(), payload.repositoryId(), payload.changeId(),
                    payload.changeType(), payload.commitSha(),
                    payload.files() == null ? List.of() : payload.files().stream()
                            .map(file -> new RepositoryChangedFile(
                                    file.status(), file.filePath(), file.oldPath(),
                                    file.binaryFile()
                            )).toList(),
                    payload.nextCursor(), payload.hasMore()
            );
        } catch (RuntimeException exception) {
            throw map(exception, McpToolErrorCode.REPOSITORY_NOT_FOUND);
        }
    }

    @Override
    public BindingBatchResult getFileBindingsBatch(
            UUID workspaceId,
            UUID repositoryId,
            List<String> filePaths,
            McpUserIdentity identity
    ) {
        try {
            BindingBatchPayload payload = restClient.post()
                    .uri(
                            "/api/v1/workspaces/{workspaceId}/repositories/{repositoryId}/code-bindings/batch",
                            workspaceId, repositoryId
                    )
                    .header(HttpHeaders.AUTHORIZATION, bearer(identity))
                    .body(Map.of("filePaths", filePaths))
                    .retrieve()
                    .body(BindingBatchPayload.class);
            if (payload == null) {
                throw new McpToolException(McpToolErrorCode.INTERNAL_ERROR, "Binding batch was empty");
            }
            return new BindingBatchResult(
                    payload.workspaceId(), payload.repositoryId(),
                    payload.files() == null ? List.of() : payload.files().stream()
                            .map(file -> new FileBindingGroup(
                                    file.filePath(),
                                    file.bindings() == null ? List.of()
                                            : file.bindings().stream().map(binding ->
                                                    new BatchBindingInfo(
                                                            binding.bindingId(),
                                                            binding.repositoryId(),
                                                            binding.documentId(),
                                                            binding.documentTitle(),
                                                            binding.blockId(),
                                                            binding.pathPattern(),
                                                            binding.revision(),
                                                            binding.anchorKind(),
                                                            binding.symbolKey(),
                                                            binding.startLine(),
                                                            binding.endLine(),
                                                            binding.bindingRole(),
                                                            binding.bindingOrdinal() == null ? 1 : binding.bindingOrdinal()
                                                    )).toList()
                            )).toList()
            );
        } catch (RuntimeException exception) {
            throw map(exception, McpToolErrorCode.REPOSITORY_NOT_FOUND);
        }
    }

    @Override
    public CodeMetadataBatch inspectCodeMetadata(
            UUID workspaceId,
            UUID repositoryId,
            String revision,
            List<String> filePaths,
            McpUserIdentity identity
    ) {
        try {
            CodeMetadataBatchPayload payload = restClient.post()
                    .uri(
                            "/api/v1/workspaces/{workspaceId}/repositories/{repositoryId}/code-metadata/batch",
                            workspaceId, repositoryId
                    )
                    .header(HttpHeaders.AUTHORIZATION, bearer(identity))
                    .body(Map.of("revision", revision, "filePaths", filePaths))
                    .retrieve()
                    .body(CodeMetadataBatchPayload.class);
            if (payload == null) {
                throw new McpToolException(
                        McpToolErrorCode.INTERNAL_ERROR,
                        "Code metadata batch was empty"
                );
            }
            return new CodeMetadataBatch(
                    payload.workspaceId(), payload.repositoryId(), payload.revision(),
                    payload.files() == null ? List.of() : payload.files().stream()
                            .map(file -> new CodeMetadataInfo(
                                    file.filePath(), file.language(), file.packageName(),
                                    file.moduleKey(), file.layerHint(), safe(file.imports()),
                                    safe(file.exportedSymbols()), safe(file.topLevelSymbols()),
                                    safe(file.annotations()), safe(file.routeHints()),
                                    safe(file.roleHints()), file.parseStatus(),
                                    file.errorCode()
                            )).toList()
            );
        } catch (RuntimeException exception) {
            throw map(exception, McpToolErrorCode.REPOSITORY_NOT_FOUND);
        }
    }

    private static List<String> safe(List<String> values) {
        return values == null ? List.of() : values;
    }

    @Override
    public Map<String, Object> submitDocumentChange(
            UUID workspaceId,
            Map<String, Object> request,
            McpUserIdentity identity
    ) {
        try {
            Map<String, Object> response = restClient.post()
                    .uri(
                            "/api/v1/workspaces/{workspaceId}/document-change-requests",
                            workspaceId
                    )
                    .header(HttpHeaders.AUTHORIZATION, bearer(identity))
                    .body(request)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (response == null) {
                throw new McpToolException(
                        McpToolErrorCode.INTERNAL_ERROR,
                        "Knowledge Core returned an empty review response"
                );
            }
            return response;
        } catch (RuntimeException exception) {
            if (exception instanceof RestClientResponseException response
                    && (response.getStatusCode().value() == 400
                    || response.getStatusCode().value() == 409)) {
                throw new McpToolException(
                        McpToolErrorCode.INVALID_ARGUMENT,
                        "Document change request was rejected"
                );
            }
            throw map(exception, McpToolErrorCode.WORKSPACE_NOT_FOUND);
        }
    }

    private <T> T get(
            String path,
            McpUserIdentity identity,
            Class<T> responseType,
            Object... pathVariables
    ) {
        T response = restClient.get()
                .uri(path, pathVariables)
                .header(HttpHeaders.AUTHORIZATION, bearer(identity))
                .retrieve()
                .body(responseType);
        if (response == null) {
            throw new McpToolException(McpToolErrorCode.INTERNAL_ERROR, "Knowledge Core returned an empty response");
        }
        return response;
    }

    private RuntimeException map(RuntimeException exception, McpToolErrorCode notFoundFallback) {
        if (exception instanceof McpToolException toolException) {
            return toolException;
        }
        if (exception instanceof ResourceAccessException) {
            return new McpToolException(McpToolErrorCode.CORE_UNAVAILABLE, "Knowledge Core is unavailable");
        }
        if (exception instanceof RestClientResponseException responseException) {
            HttpStatusCode status = responseException.getStatusCode();
            if (status.value() == 401 || status.value() == 403) {
                return new McpToolException(McpToolErrorCode.PERMISSION_DENIED, "Workspace access was denied");
            }
            if (status.value() == 404) {
                String code = extractErrorCode(responseException.getResponseBodyAsString());
                if ("GIT_REPOSITORY_NOT_FOUND".equals(code)) {
                    return new McpToolException(McpToolErrorCode.REPOSITORY_NOT_FOUND, "Repository was not found");
                }
                if ("GIT_REPOSITORY_FILE_NOT_FOUND".equals(code)) {
                    return new McpToolException(McpToolErrorCode.FILE_NOT_FOUND, "Repository file was not found");
                }
                if ("DOCUMENT_NOT_FOUND".equals(code)) {
                    return new McpToolException(McpToolErrorCode.DOCUMENT_NOT_FOUND, "Document was not found");
                }
                return new McpToolException(notFoundFallback, "Requested DevCollab context was not found");
            }
            if (status.is5xxServerError()) {
                return new McpToolException(McpToolErrorCode.CORE_UNAVAILABLE, "Knowledge Core is unavailable");
            }
        }
        return new McpToolException(McpToolErrorCode.INTERNAL_ERROR, "Knowledge Core request failed");
    }

    private String extractErrorCode(String body) {
        int marker = body == null ? -1 : body.indexOf("\"code\"");
        if (marker < 0) {
            return "";
        }
        int separator = body.indexOf(':', marker);
        int start = body.indexOf('"', separator + 1);
        int end = start < 0 ? -1 : body.indexOf('"', start + 1);
        return start >= 0 && end > start ? body.substring(start + 1, end) : "";
    }

    private String bearer(McpUserIdentity identity) {
        return "Bearer " + identity.accessToken();
    }

    private record WorkspacePayload(UUID id, String name, String currentUserRole) {
    }

    private record RepositoryPayload(
            UUID id,
            String name,
            String provider,
            String remoteUrl,
            String defaultBranch,
            String syncStatus,
            String lastSyncedCommit
    ) {
    }

    private record SourcePayload(
            UUID repositoryId,
            String commitSha,
            String path,
            long sizeBytes,
            String language,
            boolean readable,
            String content
    ) {
    }

    private record DocumentStructurePayload(
            UUID documentId,
            UUID workspaceId,
            String title,
            String documentType,
            String reviewStatus,
            java.time.Instant updatedAt,
            List<BlockInfoPayload> blocks,
            boolean truncated,
            int omittedBlockCount,
            int omittedCharacterCount
    ) {
    }

    private record BlockInfoPayload(
            UUID blockId,
            String blockType,
            int sortOrder,
            long version,
            String plainText,
            String content,
            boolean contentTruncated
    ) {
    }

    private record BindingQueryResultPayload(
            UUID workspaceId,
            UUID repositoryId,
            String filePath,
            boolean fileHasBindings,
            List<BindingInfoPayload> bindings,
            boolean truncated,
            int omittedBindingCount
    ) {
    }

    private record BindingInfoPayload(
            UUID bindingId,
            String pathPattern,
            UUID documentId,
            String documentTitle,
            UUID blockId,
            String revision,
            String anchorKind,
            String symbolKey,
            Integer startLine,
            Integer endLine,
            String bindingRole,
            Integer bindingOrdinal
    ) {
    }

    private record DocumentCandidateResultPayload(
            UUID workspaceId,
            UUID repositoryId,
            String filePath,
            String query,
            List<DocumentCandidatePayload> candidates,
            boolean truncated,
            int omittedCandidateCount
    ) {
    }

    private record RepositoryFilePagePayload(
            UUID workspaceId,
            UUID repositoryId,
            String revision,
            String pathPrefix,
            boolean recursive,
            List<RepositoryFilePayload> files,
            String nextCursor,
            boolean hasMore
    ) {
    }

    private record RepositoryFilePayload(
            String filePath,
            String fileName,
            String extension,
            long sizeBytes,
            String language,
            boolean readable,
            boolean isDirectory
    ) {
    }

    private record RepositoryChangePagePayload(
            UUID workspaceId,
            UUID repositoryId,
            UUID changeId,
            String changeType,
            String commitSha,
            List<RepositoryChangedFilePayload> files,
            String nextCursor,
            boolean hasMore
    ) {
    }

    private record RepositoryChangedFilePayload(
            String status,
            String filePath,
            String oldPath,
            boolean binaryFile
    ) {
    }

    private record BindingBatchPayload(
            UUID workspaceId,
            UUID repositoryId,
            List<FileBindingGroupPayload> files
    ) {
    }

    private record FileBindingGroupPayload(
            String filePath,
            List<BatchBindingPayload> bindings
    ) {
    }

    private record BatchBindingPayload(
            UUID bindingId,
            UUID repositoryId,
            UUID documentId,
            String documentTitle,
            UUID blockId,
            String pathPattern,
            String revision,
            String anchorKind,
            String symbolKey,
            Integer startLine,
            Integer endLine,
            String bindingRole,
            Integer bindingOrdinal
    ) {
    }

    private record CodeMetadataBatchPayload(
            UUID workspaceId,
            UUID repositoryId,
            String revision,
            List<CodeMetadataPayload> files
    ) {
    }

    private record CodeMetadataPayload(
            String filePath,
            String language,
            String packageName,
            String moduleKey,
            String layerHint,
            List<String> imports,
            List<String> exportedSymbols,
            List<String> topLevelSymbols,
            List<String> annotations,
            List<String> routeHints,
            List<String> roleHints,
            String parseStatus,
            String errorCode
    ) {
    }

    private record DocumentCandidatePayload(
            UUID documentId,
            String title,
            int score,
            List<DocumentCandidateMatchReasonPayload> matchReasons,
            List<UUID> matchedBlockIds,
            int existingBindingCount
    ) {
    }

    private record DocumentCandidateMatchReasonPayload(
            String code,
            int weight,
            String matchedTerm,
            List<UUID> matchedBlockIds
    ) {
    }
}
