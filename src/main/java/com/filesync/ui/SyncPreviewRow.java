package com.filesync.ui;

import com.filesync.sync.ConflictInfo;

final class SyncPreviewRow {
    private final SyncPreviewOperationType operationType;
    private final String path;
    private final String sizeText;
    private final long sizeBytes;
    private final ConflictInfo conflict;

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

    String getTypeLabel() {
        return switch (operationType) {
            case CONFLICT -> {
                if (conflict != null && conflict.isResolved()) {
                    yield "Conflict [" + conflictShortLabel(conflict.getResolution()) + "]";
                }
                yield "CONFLICT";
            }
            case TRANSFER_FILE -> "Transfer File";
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
