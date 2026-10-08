package com.filesync.ui;

import com.filesync.sync.ConflictInfo;

final class SyncPreviewRow {
    private final SyncPreviewOperationType operationType;
    private final String path;

    /**
     * For a rename: the path the file is moving from. Null for every other operation. Only a
     * display concern — selection, filtering and execution all key on {@link #path} (the rename's
     * new path).
     */
    private final String altPath;

    private final String sizeText;
    private final long sizeBytes;
    private final ConflictInfo conflict;

    /**
     * The peer's version of this file, fetched on demand when the user opens the change preview.
     * Null means "not fetched yet"; use {@link #isBaseFetched()} to tell "not fetched" apart from
     * "fetched but the peer has nothing", which is what a brand-new file looks like.
     */
    private byte[] baseContent;

    private boolean baseFetched;

    /**
     * True while this row's change preview is being opened: the peer's version is still being
     * fetched, or the modal preview dialog is still open. A repeat click on the row's Pre button
     * must not start a second fetch or open a second window, so it is refused while this holds.
     */
    private boolean previewInProgress;

    /**
     * Completion percent of the fetch transferring the peer's version, as reported by the transfer;
     * -1 until it reports anything. An inline (base64) fetch carries no intermediate progress
     * events, so -1 also means "the percent is unknown", never zero.
     */
    private volatile int previewProgressPercent = -1;

    SyncPreviewRow(
            SyncPreviewOperationType operationType, String path, String sizeText, long sizeBytes) {
        this(operationType, path, sizeText, sizeBytes, null);
    }

    SyncPreviewRow(
            SyncPreviewOperationType operationType,
            String path,
            String sizeText,
            long sizeBytes,
            ConflictInfo conflict) {
        this(operationType, path, null, sizeText, sizeBytes, conflict);
    }

    SyncPreviewRow(
            SyncPreviewOperationType operationType,
            String path,
            String altPath,
            String sizeText,
            long sizeBytes,
            ConflictInfo conflict) {
        this.operationType = operationType;
        this.path = path;
        this.altPath = altPath;
        this.sizeText = sizeText;
        this.sizeBytes = sizeBytes;
        this.conflict = conflict;
    }

    SyncPreviewOperationType getOperationType() {
        return operationType;
    }

    String getPath() {
        return path;
    }

    /** The path a rename moves away from, or null for every other operation. */
    String getAltPath() {
        return altPath;
    }

    /**
     * What the Path column shows: the plain path, or "old → new" for a rename so the move reads at
     * a glance without a second column.
     */
    String getDisplayPath() {
        return altPath != null && !altPath.isEmpty() ? altPath + " → " + path : path;
    }

    String getSizeText() {
        return sizeText;
    }

    long getSizeBytes() {
        return sizeBytes;
    }

    ConflictInfo getConflict() {
        return conflict;
    }

    /** The peer's version of the file, or null when unavailable/not yet fetched. */
    byte[] getBaseContent() {
        return baseContent;
    }

    void setBaseContent(byte[] baseContent) {
        this.baseContent = baseContent;
    }

    /** True once a fetch attempt has completed, successful or not. */
    boolean isBaseFetched() {
        return baseFetched;
    }

    void setBaseFetched(boolean baseFetched) {
        this.baseFetched = baseFetched;
    }

    /** True while this row's change preview is being fetched or shown. */
    boolean isPreviewInProgress() {
        return previewInProgress;
    }

    void setPreviewInProgress(boolean previewInProgress) {
        this.previewInProgress = previewInProgress;
    }

    /** Transfer percent so far while the preview is being fetched, or -1 when unknown. */
    int getPreviewProgressPercent() {
        return previewProgressPercent;
    }

    void setPreviewProgressPercent(int previewProgressPercent) {
        this.previewProgressPercent = previewProgressPercent;
    }

    /**
     * True when this operation has a previous version worth comparing against. New files and
     * deletions have none by definition, so no remote fetch should be attempted for them.
     */
    boolean hasBaseVersion() {
        return operationType == SyncPreviewOperationType.MODIFIED
                || operationType == SyncPreviewOperationType.APPEND
                || operationType == SyncPreviewOperationType.CONFLICT
                || operationType == SyncPreviewOperationType.TRANSFER_FILE;
    }

    String getTypeLabel() {
        return switch (operationType) {
            case CONFLICT -> {
                if (conflict != null && conflict.isResolved()) {
                    yield "Conflict [" + conflictShortLabel(conflict.getResolution()) + "]";
                }
                yield "CONFLICT";
            }
            case TRANSFER_FILE -> "Transfer File";
            case NEW -> "New";
            case MODIFIED -> "Modified";
            case APPEND -> "Append";
            case CREATE_DIR -> "Create Dir";
            case DELETE_FILE -> "Delete File";
            case DELETE_DIR -> "Delete Dir";
            case RENAME -> "Rename";
            default -> "Unknown";
        };
    }

    private static String conflictShortLabel(ConflictInfo.Resolution res) {
        return switch (res) {
            case KEEP_LOCAL -> "Keep Local";
            case KEEP_REMOTE -> "Keep Remote";
            case MERGE -> "Merged";
            case SKIP -> "Skip";
            default -> "?";
        };
    }
}
