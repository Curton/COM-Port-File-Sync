package com.filesync.ui;

final class SyncPreviewRow {
    private final SyncPreviewOperationType operationType;
    private final String path;
    private final String sizeText;
    private final long sizeBytes;

    SyncPreviewRow(SyncPreviewOperationType operationType, String path, String sizeText, long sizeBytes) {
        this.operationType = operationType;
        this.path = path;
        this.sizeText = sizeText;
        this.sizeBytes = sizeBytes;
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

    String getTypeLabel() {
        return switch (operationType) {
            case TRANSFER_FILE -> "Transfer File";
            case CREATE_DIR -> "Create Dir";
            case DELETE_FILE -> "Delete File";
            case DELETE_DIR -> "Delete Dir";
            default -> "Unknown";
        };
    }
}
