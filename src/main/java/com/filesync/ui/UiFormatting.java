package com.filesync.ui;

final class UiFormatting {
    private UiFormatting() {}

    static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else if (bytes < 1024L * 1024L * 1024L) {
            return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
        }
        return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    static String formatSpeed(double bytesPerSec) {
        if (bytesPerSec < 1024) {
            return String.format("%.0f B/s", bytesPerSec);
        } else if (bytesPerSec < 1024 * 1024) {
            return String.format("%.1f KB/s", bytesPerSec / 1024);
        } else {
            return String.format("%.2f MB/s", bytesPerSec / (1024 * 1024));
        }
    }
}
