package com.filesync.sync;

/**
 * Represents a conflict when the same file has been modified on both sender and receiver.
 * Contains information about both versions and allows tracking the user's resolution.
 */
public final class ConflictInfo {

    /**
     * How the user resolved the conflict.
     */
    public enum Resolution {
        /** User chose to keep the local version */
        KEEP_LOCAL,
        /** User chose to keep the remote version */
        KEEP_REMOTE,
        /** User chose to skip this file entirely */
        SKIP,
        /** User chose to merge both versions (for text files) */
        MERGE,
        /** User has not yet made a decision */
        UNRESOLVED
    }

    /**
     * Where to apply the resolution: remote only, or both local and remote.
     */
    public enum ApplyTarget {
        /** Apply changes to remote only (do not overwrite local file) */
        REMOTE_ONLY,
        /** Apply changes to both local and remote */
        BOTH
    }

    private final String path;
    private final FileChangeDetector.FileInfo localInfo;
    private final FileChangeDetector.FileInfo remoteInfo;
    private final boolean binary;
    private final byte[] localContent;
    private byte[] remoteContent;
    private String mergedContent;
    private Resolution resolution = Resolution.UNRESOLVED;
    private ApplyTarget applyTarget = ApplyTarget.BOTH;

    public ConflictInfo(String path,
                        FileChangeDetector.FileInfo localInfo,
                        FileChangeDetector.FileInfo remoteInfo,
                        boolean binary,
                        byte[] localContent) {
        this.path = path;
        this.localInfo = localInfo;
        this.remoteInfo = remoteInfo;
        this.binary = binary;
        this.localContent = localContent;
    }

    public String getPath() {
        return path;
    }

    public FileChangeDetector.FileInfo getLocalInfo() {
        return localInfo;
    }

    public FileChangeDetector.FileInfo getRemoteInfo() {
        return remoteInfo;
    }

    public boolean isBinary() {
        return binary;
    }

    public byte[] getLocalContent() {
        return localContent;
    }

    public byte[] getRemoteContent() {
        return remoteContent;
    }

    public void setRemoteContent(byte[] remoteContent) {
        this.remoteContent = remoteContent;
    }

    public String getMergedContent() {
        return mergedContent;
    }

    public void setMergedContent(String mergedContent) {
        this.mergedContent = mergedContent;
    }

    public byte[] getMergedContentAsBytes() {
        if (mergedContent == null) {
            return null;
        }
        return mergedContent.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public Resolution getResolution() {
        return resolution;
    }

    public void setResolution(Resolution resolution) {
        this.resolution = resolution;
    }

    public ApplyTarget getApplyTarget() {
        return applyTarget;
    }

    public void setApplyTarget(ApplyTarget applyTarget) {
        this.applyTarget = applyTarget;
    }

    public boolean isResolved() {
        return resolution != Resolution.UNRESOLVED;
    }

    public String getLocalContentAsString() {
        if (localContent == null) {
            return "";
        }
        return new String(localContent, java.nio.charset.StandardCharsets.UTF_8);
    }

    public String getRemoteContentAsString() {
        if (remoteContent == null) {
            return "";
        }
        return new String(remoteContent, java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return "ConflictInfo{" +
                "path='" + path + '\'' +
                ", binary=" + binary +
                ", resolution=" + resolution +
                '}';
    }
}