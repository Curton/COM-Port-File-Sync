package com.filesync.sync;

/**
 * Marker interface for sync events.
 * Concrete event types are provided as nested classes for convenience.
 */
public interface SyncEvent {
    SyncEventType getType();

    final class ConnectionEvent implements SyncEvent {
        private final boolean connected;

        public ConnectionEvent(boolean connected) {
            this.connected = connected;
        }

        public boolean isConnected() {
            return connected;
        }

        @Override
        public SyncEventType getType() {
            return SyncEventType.CONNECTION_STATUS;
        }
    }

    final class DirectionEvent implements SyncEvent {
        private final boolean sender;

        public DirectionEvent(boolean sender) {
            this.sender = sender;
        }

        public boolean isSender() {
            return sender;
        }

        @Override
        public SyncEventType getType() {
            return SyncEventType.DIRECTION_CHANGED;
        }
    }

    final class SyncStartedEvent implements SyncEvent {
        @Override
        public SyncEventType getType() {
            return SyncEventType.SYNC_STARTED;
        }
    }

    final class SyncCompleteEvent implements SyncEvent {
        @Override
        public SyncEventType getType() {
            return SyncEventType.SYNC_COMPLETE;
        }
    }

    final class TransferCompleteEvent implements SyncEvent {
        @Override
        public SyncEventType getType() {
            return SyncEventType.TRANSFER_COMPLETE;
        }
    }

    final class FileProgressEvent implements SyncEvent {
        private final int currentFile;
        private final int totalFiles;
        private final String fileName;

        public FileProgressEvent(int currentFile, int totalFiles, String fileName) {
            this.currentFile = currentFile;
            this.totalFiles = totalFiles;
            this.fileName = fileName;
        }

        public int getCurrentFile() {
            return currentFile;
        }

        public int getTotalFiles() {
            return totalFiles;
        }

        public String getFileName() {
            return fileName;
        }

        @Override
        public SyncEventType getType() {
            return SyncEventType.FILE_PROGRESS;
        }
    }

    final class TransferProgressEvent implements SyncEvent {
        private final int currentBlock;
        private final int totalBlocks;
        private final long bytesTransferred;
        private final double speedBytesPerSec;

        public TransferProgressEvent(int currentBlock, int totalBlocks, long bytesTransferred, double speedBytesPerSec) {
            this.currentBlock = currentBlock;
            this.totalBlocks = totalBlocks;
            this.bytesTransferred = bytesTransferred;
            this.speedBytesPerSec = speedBytesPerSec;
        }

        public int getCurrentBlock() {
            return currentBlock;
        }

        public int getTotalBlocks() {
            return totalBlocks;
        }

        public long getBytesTransferred() {
            return bytesTransferred;
        }

        public double getSpeedBytesPerSec() {
            return speedBytesPerSec;
        }

        @Override
        public SyncEventType getType() {
            return SyncEventType.TRANSFER_PROGRESS;
        }
    }

    final class LogEvent implements SyncEvent {
        private final String message;

        public LogEvent(String message) {
            this.message = message;
        }

        public String getMessage() {
            return message;
        }

        @Override
        public SyncEventType getType() {
            return SyncEventType.LOG;
        }
    }

    final class ErrorEvent implements SyncEvent {
        private final String message;

        public ErrorEvent(String message) {
            this.message = message;
        }

        public String getMessage() {
            return message;
        }

        @Override
        public SyncEventType getType() {
            return SyncEventType.ERROR;
        }
    }

    final class SharedTextReceivedEvent implements SyncEvent {
        private final String text;

        public SharedTextReceivedEvent(String text) {
            this.text = text;
        }

        public String getText() {
            return text;
        }

        @Override
        public SyncEventType getType() {
            return SyncEventType.SHARED_TEXT_RECEIVED;
        }
    }
}

