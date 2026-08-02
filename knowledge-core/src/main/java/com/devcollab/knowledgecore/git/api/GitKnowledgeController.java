package com.devcollab.knowledgecore.git.api;

import com.devcollab.knowledgecore.git.application.BlockBindingFileContext;
import com.devcollab.knowledgecore.git.application.CreateCodeBindingCommand;
import com.devcollab.knowledgecore.git.application.GitKnowledgeApplicationService;
import com.devcollab.knowledgecore.git.application.exception.InvalidCodeBindingException;
import com.devcollab.knowledgecore.git.application.GitMarkdownImportService;
import com.devcollab.knowledgecore.git.application.IngestGitChangeCommand;
import com.devcollab.knowledgecore.git.application.RegisterGitRepositoryCommand;
import com.devcollab.knowledgecore.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.UUID;

@RestController
public class GitKnowledgeController {

    private final GitKnowledgeApplicationService service;
    private final GitMarkdownImportService markdownImportService;

    public GitKnowledgeController(
            GitKnowledgeApplicationService service,
            GitMarkdownImportService markdownImportService
    ) {
        this.service = service;
        this.markdownImportService = markdownImportService;
    }

    @PostMapping(
            "/api/v1/workspaces/{workspaceId}/git/repositories/{repositoryId}/documents/import"
    )
    public GitMarkdownImportResponse importMarkdownDocuments(
            @PathVariable UUID workspaceId,
            @PathVariable UUID repositoryId,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return GitMarkdownImportResponse.from(
                markdownImportService.importReadyMarkdown(
                        workspaceId, repositoryId, currentUser.userId()
                )
        );
    }

    @PostMapping("/api/v1/workspaces/{workspaceId}/git/repositories")
    @ResponseStatus(HttpStatus.CREATED)
    public GitRepositoryResponse registerRepository(
            @PathVariable UUID workspaceId,
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody RegisterGitRepositoryRequest request
    ) {
        return GitRepositoryResponse.from(service.registerRepository(
                workspaceId, currentUser.userId(),
                new RegisterGitRepositoryCommand(
                        request.name(), request.provider(), request.remoteUrl(),
                        request.defaultBranch()
                )
        ));
    }

    @GetMapping("/api/v1/workspaces/{workspaceId}/git/repositories")
    public List<GitRepositoryResponse> listRepositories(
            @PathVariable UUID workspaceId,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return service.listRepositories(workspaceId, currentUser.userId())
                .stream().map(GitRepositoryResponse::from).toList();
    }

    @PostMapping(
            "/api/v1/workspaces/{workspaceId}/git/repositories/{repositoryId}/sync"
    )
    @ResponseStatus(HttpStatus.ACCEPTED)
    public GitRepositoryResponse syncRepository(
            @PathVariable UUID workspaceId,
            @PathVariable UUID repositoryId,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return GitRepositoryResponse.from(service.requestSync(
                workspaceId, repositoryId, currentUser.userId()
        ));
    }

    @DeleteMapping(
            "/api/v1/workspaces/{workspaceId}/git/repositories/{repositoryId}"
    )
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRepository(
            @PathVariable UUID workspaceId,
            @PathVariable UUID repositoryId,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        service.deleteRepository(workspaceId, repositoryId, currentUser.userId());
    }

    @GetMapping(
            "/api/v1/workspaces/{workspaceId}/git/repositories/{repositoryId}/files"
    )
    public List<GitRepositoryFileResponse> listRepositoryFiles(
            @PathVariable UUID workspaceId,
            @PathVariable UUID repositoryId,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return service.listFiles(workspaceId, repositoryId, currentUser.userId())
                .stream().map(GitRepositoryFileResponse::from).toList();
    }

    @GetMapping(
            "/api/v1/workspaces/{workspaceId}/repositories/{repositoryId}/repository-files"
    )
    public RepositoryFilePageResponse listRepositoryFilePage(
            @PathVariable UUID workspaceId,
            @PathVariable UUID repositoryId,
            @RequestParam(required = false) String pathPrefix,
            @RequestParam(defaultValue = "true") boolean recursive,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "200") int limit,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return RepositoryFilePageResponse.from(service.listFilePage(
                workspaceId, repositoryId, currentUser.userId(), pathPrefix,
                recursive, cursor, limit
        ));
    }

    @GetMapping(
            "/api/v1/workspaces/{workspaceId}/repositories/{repositoryId}/repository-changes"
    )
    public RepositoryChangePageResponse listRepositoryChangePage(
            @PathVariable UUID workspaceId,
            @PathVariable UUID repositoryId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "200") int limit,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return RepositoryChangePageResponse.from(service.listLatestChangeFilePage(
                workspaceId, repositoryId, currentUser.userId(), cursor, limit
        ));
    }

    @PostMapping(
            "/api/v1/workspaces/{workspaceId}/repositories/{repositoryId}/code-bindings/batch"
    )
    public CodeBindingBatchQueryResponse queryBindingsBatch(
            @PathVariable UUID workspaceId,
            @PathVariable UUID repositoryId,
            @Valid @RequestBody CodeBindingBatchQueryRequest request,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return CodeBindingBatchQueryResponse.from(service.queryBindingsBatch(
                workspaceId,
                repositoryId,
                currentUser.userId(),
                request.filePaths(),
                request.revision(),
                request.includeLegacyOrDefault(),
                request.maxBindings()
        ));
    }

    @PostMapping(
            "/api/v1/workspaces/{workspaceId}/repositories/{repositoryId}/code-metadata/batch"
    )
    public CodeMetadataBatchResponse inspectCodeMetadata(
            @PathVariable UUID workspaceId,
            @PathVariable UUID repositoryId,
            @Valid @RequestBody CodeMetadataBatchRequest request,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return CodeMetadataBatchResponse.from(service.inspectCodeMetadata(
                workspaceId, repositoryId, currentUser.userId(),
                request.revision(), request.filePaths()
        ));
    }

    @GetMapping(
            "/api/v1/workspaces/{workspaceId}/git/repositories/{repositoryId}/source"
    )
    public GitRepositorySourceResponse getRepositorySource(
            @PathVariable UUID workspaceId,
            @PathVariable UUID repositoryId,
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestParam String path
    ) {
        return GitRepositorySourceResponse.from(service.getSource(
                workspaceId, repositoryId, currentUser.userId(), path
        ));
    }

    @GetMapping(
            "/api/v1/workspaces/{workspaceId}/git/repositories/{repositoryId}/code-graph"
    )
    public CodeGraphResponse getCodeGraph(
            @PathVariable UUID workspaceId,
            @PathVariable UUID repositoryId,
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestParam(required = false) String filePath
    ) {
        return CodeGraphResponse.from(service.getCodeGraph(
                workspaceId, repositoryId, currentUser.userId(), filePath
        ));
    }

    @PostMapping(
            "/api/v1/workspaces/{workspaceId}/git/repositories/{repositoryId}/changes"
    )
    public ResponseEntity<GitChangeResponse> ingestChange(
            @PathVariable UUID workspaceId,
            @PathVariable UUID repositoryId,
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody IngestGitChangeRequest request
    ) {
        var details = service.ingestChange(
                workspaceId, repositoryId, currentUser.userId(),
                toCommand(request)
        );
        GitChangeResponse response = GitChangeResponse.from(details);
        return details.duplicate()
                ? ResponseEntity.ok(response)
                : ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping(
            "/api/v1/workspaces/{workspaceId}/git/repositories/{repositoryId}/changes"
    )
    public List<GitChangeResponse> listChanges(
            @PathVariable UUID workspaceId,
            @PathVariable UUID repositoryId,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return service.listChanges(workspaceId, repositoryId, currentUser.userId())
                .stream().map(GitChangeResponse::from).toList();
    }

    @GetMapping(
            "/api/v1/workspaces/{workspaceId}/git/changes/{changeId}/affected-documents"
    )
    public List<AffectedCodeDocumentResponse> affectedDocuments(
            @PathVariable UUID workspaceId,
            @PathVariable UUID changeId,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return service.findAffectedDocuments(
                        workspaceId, changeId, currentUser.userId()
                ).stream().map(AffectedCodeDocumentResponse::from).toList();
    }

    @PostMapping("/api/v1/documents/{documentId}/code-bindings")
    @ResponseStatus(HttpStatus.CREATED)
    public CodeDocumentBindingResponse createBinding(
            @PathVariable UUID documentId,
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody CreateCodeBindingRequest request
    ) {
        return CodeDocumentBindingResponse.from(service.createBinding(
                documentId, currentUser.userId(),
                new CreateCodeBindingCommand(
                        request.repositoryId(),
                        request.blockId(),
                        request.pathPattern(),
                        request.revision(),
                        request.anchorKind(),
                        request.symbolKey(),
                        request.startLine(),
                        request.endLine(),
                        request.bindingRole() == null
                                ? com.devcollab.knowledgecore.git.domain.BindingRole.PRIMARY
                                : request.bindingRole(),
                        request.bindingOrdinal() == null ? 1 : request.bindingOrdinal()
                )
        ));
    }

    @GetMapping("/api/v1/documents/{documentId}/code-bindings")
    public List<CodeDocumentBindingResponse> listBindings(
            @PathVariable UUID documentId,
            @RequestParam(required = false) String revision,
            @RequestParam(defaultValue = "true") boolean includeLegacy,
            @RequestParam(required = false) UUID blockId,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return service.listBindings(
                        documentId,
                        currentUser.userId(),
                        revision,
                        includeLegacy,
                        blockId
                )
                .stream().map(CodeDocumentBindingResponse::from).toList();
    }

    @GetMapping("/api/v1/documents/{documentId}/code-bindings/context")
    public List<CodeBindingContextItemResponse> listBindingsContext(
            @PathVariable UUID documentId,
            @RequestParam(required = false) String revision,
            @RequestParam(defaultValue = "true") boolean includeLegacy,
            @RequestParam(required = false) UUID blockId,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return service.listDocumentBindingsContext(
                        documentId,
                        currentUser.userId(),
                        revision,
                        includeLegacy,
                        blockId
                )
                .stream().map(CodeBindingContextItemResponse::from).toList();
    }

    @GetMapping("/api/v1/documents/{documentId}/code-bindings/block-file-context")
    public BlockBindingFileContextResponse resolveBlockFileContext(
            @PathVariable UUID documentId,
            @RequestParam UUID blockId,
            @RequestParam(required = false) String revision,
            @RequestParam(defaultValue = "true") boolean includeLegacy,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        BlockBindingFileContext ctx = service.resolveBlockFileContext(
                documentId,
                currentUser.userId(),
                revision,
                includeLegacy,
                blockId
        );
        if (ctx == null) {
            throw new InvalidCodeBindingException(
                    "该 Block 暂无正式代码 Binding");
        }
        return BlockBindingFileContextResponse.from(ctx);
    }

    @GetMapping("/api/v1/workspaces/{workspaceId}/repositories/{repositoryId}/code-bindings")
    public CodeBindingQueryResponse queryBindings(
            @PathVariable UUID workspaceId,
            @PathVariable UUID repositoryId,
            @RequestParam String filePath,
            @RequestParam(required = false) String revision,
            @RequestParam(defaultValue = "true") boolean includeLegacy,
            @RequestParam(required = false) Integer maxBindings,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return CodeBindingQueryResponse.from(service.queryBindings(
                workspaceId,
                repositoryId,
                currentUser.userId(),
                filePath,
                revision,
                includeLegacy,
                maxBindings
        ));
    }

    @DeleteMapping("/api/v1/code-bindings/{bindingId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBinding(
            @PathVariable UUID bindingId,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        service.deleteBinding(bindingId, currentUser.userId());
    }

    private IngestGitChangeCommand toCommand(IngestGitChangeRequest request) {
        return new IngestGitChangeCommand(
                request.changeType(), request.externalId(), request.title(),
                request.commitSha(), request.baseRef(), request.headRef(),
                request.authorName(), request.authorEmail(), request.authoredAt(),
                request.committerName(), request.committerEmail(),
                request.parentCommitSha(), request.webUrl(), request.occurredAt(),
                request.files().stream().map(file -> new IngestGitChangeCommand.FileDiff(
                        file.path(), file.oldPath(), file.changeType(),
                        file.additions(), file.deletions(), file.binaryFile(),
                        file.patchExcerpt()
                )).toList()
        );
    }
}
