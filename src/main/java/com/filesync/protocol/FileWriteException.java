package com.filesync.protocol;

import java.io.IOException;

/**
 * Signals that a received file could not be written to disk (e.g. the target file is locked by
 * another program) even though the transfer itself succeeded. Carries the payload so the receiver
 * can queue the file for a later retry instead of dropping it.
 */
public class FileWriteException extends IOException {

    private final String relativePath;
    private final byte[] data;
    private final long lastModified;

    public FileWriteException(
            String relativePath, byte[] data, long lastModified, String message, Throwable cause) {
        super(message, cause);
        this.relativePath = relativePath;
        this.data = data;
        this.lastModified = lastModified;
    }

    public String getRelativePath() {
        return relativePath;
    }

    public byte[] getData() {
        return data;
    }

    public long getLastModified() {
        return lastModified;
    }
}
