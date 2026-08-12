package com.devcollab.knowledgecore.common.outbox.application;

/**
 * Shared outbox event type names.
 *
 * <p>Legacy document events are kept for timeline/search compatibility.
 * Architecture-level semantic events are added beside them so downstream
 * consumers can evolve without breaking the current UI.
 */
public final class OutboxEventTypes {

    private OutboxEventTypes() {
    }

    public static final String DOCUMENT_OPERATION_APPLIED =
            "DOCUMENT_OPERATION_APPLIED";
    public static final String DOCUMENT_VERSION_PUBLISHED =
            "DOCUMENT_VERSION_PUBLISHED";
    public static final String DOCUMENT_DELETED = "DOCUMENT_DELETED";

    public static final String CACHE_INVALIDATED = "CACHE_INVALIDATED";
    public static final String SNAPSHOT_REQUESTED = "SNAPSHOT_REQUESTED";

    public static final String REVIEW_REQUESTED = "REVIEW_REQUESTED";
    public static final String REVIEW_COMPLETED = "REVIEW_COMPLETED";
    public static final String REVIEW_FAILED = "REVIEW_FAILED";

    public static final String GIT_CHANGE_SYNCED = "GIT_CHANGE_SYNCED";
    public static final String GIT_REPOSITORY_SYNC_REQUESTED =
            "GIT_REPOSITORY_SYNC_REQUESTED";
    public static final String GIT_REPOSITORY_DELETE_REQUESTED =
            "GIT_REPOSITORY_DELETE_REQUESTED";
}
