package com.devcollab.knowledgecore.git.application;

/**
 * Severity of binding drift detected by {@link DriftDetectionService}.
 *
 * <p>Maps to the frontend {@code CodeAnchor.status}:
 * <ul>
 *   <li>{@link #NONE}               → VALID</li>
 *   <li>{@link #COSMETIC}           → DRIFTED</li>
 *   <li>{@link #SIGNATURE_CHANGED}  → DRIFTED</li>
 *   <li>{@link #SYMBOL_MOVED}       → DRIFTED</li>
 *   <li>{@link #SYMBOL_REMOVED}     → BROKEN</li>
 *   <li>{@link #FILE_REMOVED}       → BROKEN</li>
 * </ul>
 */
public enum DriftLevel {
    /** Symbol signature and position unchanged — binding is still accurate. */
    NONE,

    /** Only line numbers shifted; signature unchanged. */
    COSMETIC,

    /** Function/class signature changed — the binding describes different code. */
    SIGNATURE_CHANGED,

    /** Symbol moved to a different file — binding path is stale. */
    SYMBOL_MOVED,

    /** Symbol was deleted — binding is broken. */
    SYMBOL_REMOVED,

    /** The entire bound file was deleted — binding is broken. */
    FILE_REMOVED
}
