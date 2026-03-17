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
        switch (operationType) {
            case TRANSFER_FILE:
                return "Transfer File";
            case CREATE_DIR:
                return "Create Dir";
            case DELETE_FILE:
                return "Delete File";
            case DELETE_DIR:
                return "Delete Dir";
            default:
                return "Unknown";
        }
    }
}
