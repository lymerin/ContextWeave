package com.devcollab.knowledgecore.common.cache;

import java.util.UUID;

/**
 * Centralized cache key definitions.
 *
 * <p>All Redis keys are managed here so that key patterns,
 * namespaces, and eviction logic stay consistent across services.
 */
public final class CacheKey {

    private CacheKey() {
    }

    /**
     * Cached workspace membership lookup.
     * <p>Used by {@code WorkspaceApplicationService#requireMembership}
     * and permission checks throughout the document module.
     */
    public static String workspaceMember(UUID workspaceId, UUID userId) {
        return "workspace:member:" + workspaceId + ":" + userId;
    }

    /**
     * Cached document tree for a workspace.
     * <p>Used by {@code GET /api/v1/workspaces/{workspaceId}/documents/tree}.
     */
    public static String documentTree(UUID workspaceId) {
        return "workspace:documents:tree:" + workspaceId;
    }

    /**
     * Cached published document version snapshot.
     * <p>Semantic prefix required: both published-document and approved-adr
     * cache the same {@code DocumentVersion} type; without the prefix the two
     * caches would overwrite each other's Redis keys.
     */
    public static String publishedDocument(UUID workspaceId, UUID documentId, UUID versionId) {
        return "published-document:" + workspaceId + ":" + documentId + ":" + versionId;
    }

    /**
     * Prefix pattern for all published versions of one document, for prefix
     * invalidation after a new version is published or the document is
     * deleted/deprecated (CURRENT/SUPERSEDED statuses must be cleared together).
     */
    public static String publishedDocumentPrefix(UUID workspaceId, UUID documentId) {
        return "published-document:" + workspaceId + ":" + documentId + ":*";
    }

    /**
     * Cached approved ADR version snapshot. Same semantics as
     * {@link #publishedDocument}, with a distinct namespace.
     */
    public static String approvedAdr(UUID workspaceId, UUID adrId, UUID versionId) {
        return "approved-adr:" + workspaceId + ":" + adrId + ":" + versionId;
    }

    /**
     * Prefix pattern for all approved ADR versions of one ADR document.
     */
    public static String approvedAdrPrefix(UUID workspaceId, UUID adrId) {
        return "approved-adr:" + workspaceId + ":" + adrId + ":*";
    }
}