package com.filesync.ui;

import com.filesync.sync.ConflictInfo;

final class SyncPreviewRow {
    private final SyncPreviewOperationType operationType;
    private final String path;
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
        this.operationType = operationType;
        this.path = path;
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
