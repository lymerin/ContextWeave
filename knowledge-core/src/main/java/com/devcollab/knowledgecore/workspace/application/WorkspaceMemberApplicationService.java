package com.devcollab.knowledgecore.workspace.application;

import com.devcollab.knowledgecore.auth.application.AuthenticationApplicationService;
import com.devcollab.knowledgecore.auth.domain.UserAccount;
import com.devcollab.knowledgecore.auth.infrastructure.JdbcUserRepository;
import com.devcollab.knowledgecore.common.cache.CacheKey;
import com.devcollab.knowledgecore.common.outbox.application.OutboxEventPublisher;
import com.devcollab.knowledgecore.common.outbox.application.OutboxEventTypes;
import com.devcollab.knowledgecore.workspace.application.exception.WorkspaceAccessDeniedException;
import com.devcollab.knowledgecore.workspace.application.exception.WorkspaceLastAdminException;
import com.devcollab.knowledgecore.workspace.application.exception.WorkspaceMemberAlreadyExistsException;
import com.devcollab.knowledgecore.workspace.application.exception.WorkspaceMemberNotFoundException;
import com.devcollab.knowledgecore.workspace.application.exception.WorkspaceUserNotFoundException;
import com.devcollab.knowledgecore.workspace.domain.WorkspaceMember;
import com.devcollab.knowledgecore.workspace.domain.WorkspaceRole;
import com.devcollab.knowledgecore.workspace.infrastructure.JdbcWorkspaceMemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class WorkspaceMemberApplicationService {

    private final WorkspaceApplicationService workspaceService;
    private final JdbcWorkspaceMemberRepository memberRepository;
    private final JdbcUserRepository userRepository;
    private final WorkspacePermissionPolicy permissionPolicy;
    private final WorkspaceMemberCacheService memberCache;
    private final OutboxEventPublisher outboxEventPublisher;

    public WorkspaceMemberApplicationService(
            WorkspaceApplicationService workspaceService,
            JdbcWorkspaceMemberRepository memberRepository,
            JdbcUserRepository userRepository,
            WorkspacePermissionPolicy permissionPolicy,
            WorkspaceMemberCacheService memberCache,
            OutboxEventPublisher outboxEventPublisher
    ) {
        this.workspaceService = workspaceService;
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
        this.permissionPolicy = permissionPolicy;
        this.memberCache = memberCache;
        this.outboxEventPublisher = outboxEventPublisher;
    }

    public List<WorkspaceMemberView> listMembers(
            UUID workspaceId,
            UUID currentUserId
    ) {
        workspaceService.requireMembership(workspaceId, currentUserId);

        return memberRepository.findAllByWorkspaceId(workspaceId).stream()
                .map(member -> WorkspaceMemberView.from(
                        member,
                        userRepository.findById(member.userId())
                                .orElseThrow(WorkspaceUserNotFoundException::new)
                ))
                .sorted(Comparator
                        .comparing(WorkspaceMemberView::role)
                        .thenComparing(WorkspaceMemberView::username))
                .toList();
    }

    @Transactional
    public WorkspaceMemberView invite(
            UUID workspaceId,
            UUID currentUserId,
            String username,
            WorkspaceRole role
    ) {
        requireMemberManager(workspaceId, currentUserId);
        requireSupportedAssignableRole(role);
        UserAccount targetUser = userRepository
                .findByNormalizedUsername(
                        AuthenticationApplicationService.normalizeUsername(username)
                )
                .orElseThrow(WorkspaceUserNotFoundException::new);

        if (memberRepository.existsByWorkspaceIdAndUserId(
                workspaceId,
                targetUser.id()
        )) {
            throw new WorkspaceMemberAlreadyExistsException();
        }

        WorkspaceMember member = new WorkspaceMember(
                workspaceId,
                targetUser.id(),
                role,
                Instant.now()
        );
        memberRepository.save(member);
        memberCache.evict(workspaceId, targetUser.id());
        publishMemberCacheInvalidated(
                workspaceId,
                targetUser.id(),
                currentUserId,
                "WORKSPACE_MEMBER_INVITED"
        );
        return WorkspaceMemberView.from(member, targetUser);
    }

    @Transactional
    public WorkspaceMemberView updateRole(
            UUID workspaceId,
            UUID targetUserId,
            UUID currentUserId,
            WorkspaceRole role
    ) {
        requireMemberManager(workspaceId, currentUserId);
        requireSupportedAssignableRole(role);
        WorkspaceMember target = requireMember(workspaceId, targetUserId);

        if (permissionPolicy.isAdmin(target) && role != WorkspaceRole.ADMIN) {
            ensureNotLastAdmin(workspaceId);
        }

        WorkspaceMember updated = new WorkspaceMember(
                target.workspaceId(),
                target.userId(),
                role,
                target.joinedAt()
        );
        memberRepository.save(updated);
        memberCache.evict(workspaceId, targetUserId);
        publishMemberCacheInvalidated(
                workspaceId,
                targetUserId,
                currentUserId,
                "WORKSPACE_MEMBER_ROLE_UPDATED"
        );

        UserAccount user = userRepository.findById(targetUserId)
                .orElseThrow(WorkspaceUserNotFoundException::new);
        return WorkspaceMemberView.from(updated, user);
    }

    @Transactional
    public void remove(
            UUID workspaceId,
            UUID targetUserId,
            UUID currentUserId
    ) {
        requireMemberManager(workspaceId, currentUserId);
        WorkspaceMember target = requireMember(workspaceId, targetUserId);

        if (permissionPolicy.isAdmin(target)) {
            ensureNotLastAdmin(workspaceId);
        }

        memberRepository.deleteByWorkspaceIdAndUserId(workspaceId, targetUserId);
        memberCache.evict(workspaceId, targetUserId);
        publishMemberCacheInvalidated(
                workspaceId,
                targetUserId,
                currentUserId,
                "WORKSPACE_MEMBER_REMOVED"
        );
    }

    private WorkspaceMember requireMemberManager(
            UUID workspaceId,
            UUID currentUserId
    ) {
        WorkspaceMember currentMember = workspaceService.requireMembership(
                workspaceId,
                currentUserId
        );
        if (!permissionPolicy.canManageMembers(currentMember)) {
            throw new WorkspaceAccessDeniedException();
        }
        return currentMember;
    }

    private WorkspaceMember requireMember(UUID workspaceId, UUID userId) {
        return memberRepository
                .findByWorkspaceIdAndUserId(workspaceId, userId)
                .orElseThrow(WorkspaceMemberNotFoundException::new);
    }

    private void ensureNotLastAdmin(UUID workspaceId) {
        if (memberRepository.countByWorkspaceIdAndRole(
                workspaceId,
                WorkspaceRole.ADMIN
        ) <= 1) {
            throw new WorkspaceLastAdminException();
        }
    }

    private WorkspaceRole requireSupportedAssignableRole(WorkspaceRole role) {
        if (role == null) {
            throw new IllegalArgumentException("成员角色不能为空");
        }
        return role;
    }

    private void publishMemberCacheInvalidated(
            UUID workspaceId,
            UUID targetUserId,
            UUID currentUserId,
            String reason
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("workspaceId", workspaceId);
        payload.put("userId", targetUserId);
        payload.put("cacheName", "workspace-member");
        payload.put("cacheKey", CacheKey.workspaceMember(
                workspaceId,
                targetUserId
        ));
        payload.put("reason", reason);
        payload.put("operatorUserId", currentUserId);
        payload.put("invalidatedAt", Instant.now());
        outboxEventPublisher.publish(
                "CACHE",
                workspaceId,
                OutboxEventTypes.CACHE_INVALIDATED,
                payload
        );
    }
}
