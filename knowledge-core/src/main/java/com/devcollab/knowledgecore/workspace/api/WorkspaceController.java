package com.devcollab.knowledgecore.workspace.api;

import com.devcollab.knowledgecore.security.CurrentUser;
import com.devcollab.knowledgecore.security.DelegatedRestAccess;
import com.devcollab.knowledgecore.workspace.application.WorkspaceApplicationService;
import com.devcollab.knowledgecore.workspace.application.WorkspaceView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workspaces")
public class WorkspaceController {

    private final WorkspaceApplicationService workspaceService;

    public WorkspaceController(
            WorkspaceApplicationService workspaceService
    ) {
        this.workspaceService = workspaceService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WorkspaceView create(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody CreateWorkspaceRequest request
    ) {
        return workspaceService.create(
                currentUser.userId(),
                request.name()
        );
    }

    @GetMapping
    public List<WorkspaceView> list(
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return workspaceService.listForUser(currentUser.userId());
    }

    @GetMapping("/{workspaceId}")
    @DelegatedRestAccess(tools = "devcollab.workspace.get_context")
    public WorkspaceView get(
            @PathVariable UUID workspaceId,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        return workspaceService.get(workspaceId, currentUser.userId());
    }

    @PatchMapping("/{workspaceId}")
    public WorkspaceView rename(
            @PathVariable UUID workspaceId,
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody RenameWorkspaceRequest request
    ) {
        return workspaceService.rename(
                workspaceId,
                currentUser.userId(),
                request.name()
        );
    }

    @DeleteMapping("/{workspaceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable UUID workspaceId,
            @AuthenticationPrincipal CurrentUser currentUser
    ) {
        workspaceService.delete(workspaceId, currentUser.userId());
    }
}
