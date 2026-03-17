package com.filesync.ui;

import com.filesync.sync.FileSyncManager;

/**
 * Mutable UI/runtime state shared by the MainFrame collaborators.
 */
public class MainFrameState {
    private volatile boolean isSender = true;
    private volatile boolean isConnected = false;
    private volatile boolean isPreviewInProgress = false;
    private volatile boolean suppressFolderSelectionEvents = false;
    private volatile boolean suppressSharedTextEvents = false;
    private volatile String pendingMappingRemotePath;

    public boolean isSender() {
        return isSender;
    }

    public void setSender(boolean sender) {
        this.isSender = sender;
    }

    public boolean isConnected() {
        return isConnected;
    }

    public void setConnected(boolean connected) {
        this.isConnected = connected;
    }

    public boolean isPreviewInProgress() {
        return isPreviewInProgress;
    }

    public void setPreviewInProgress(boolean previewInProgress) {
        this.isPreviewInProgress = previewInProgress;
    }

    public boolean isSuppressFolderSelectionEvents() {
        return suppressFolderSelectionEvents;
    }

    public void setSuppressFolderSelectionEvents(boolean suppressFolderSelectionEvents) {
        this.suppressFolderSelectionEvents = suppressFolderSelectionEvents;
    }

    public boolean isSuppressSharedTextEvents() {
        return suppressSharedTextEvents;
    }

    public void setSuppressSharedTextEvents(boolean suppressSharedTextEvents) {
        this.suppressSharedTextEvents = suppressSharedTextEvents;
    }

    public String getPendingMappingRemotePath() {
        return pendingMappingRemotePath;
    }

    public void setPendingMappingRemotePath(String pendingMappingRemotePath) {
        this.pendingMappingRemotePath = pendingMappingRemotePath;
    }

    public void clearPendingMappingRemotePath() {
        this.pendingMappingRemotePath = null;
    }

    public boolean canSync(FileSyncManager syncManager) {
        return isConnected
                && syncManager.getSyncFolder() != null
                && isSender
                && syncManager.isConnectionAlive();
    }

}
