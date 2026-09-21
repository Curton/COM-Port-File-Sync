package com.filesync.ui;

import com.filesync.sync.FileSyncManager;

/** Mutable UI/runtime state shared by the MainFrame collaborators. */
public class MainFrameState {
    /**
     * Lifecycle of a user-initiated connection attempt. The serial work behind CONNECTING and
     * DISCONNECTING runs on background threads, so the phase is what keeps those attempts
     * single-flight: a second Connect/Disconnect click while one is in flight would race it (two
     * concurrent opens on one SerialPortManager, two non-reentrant teardowns).
     */
    public enum ConnectionPhase {
        IDLE,
        CONNECTING,
        CONNECTED,
        DISCONNECTING
    }

    private volatile boolean isSender = true;
    private volatile ConnectionPhase phase = ConnectionPhase.IDLE;
    private volatile boolean isPreviewInProgress = false;
    private volatile boolean suppressFolderSelectionEvents = false;
    private volatile String pendingMappingRemotePath;

    public boolean isSender() {
        return isSender;
    }

    public void setSender(boolean sender) {
        this.isSender = sender;
    }

    public ConnectionPhase getPhase() {
        return phase;
    }

    public void setPhase(ConnectionPhase phase) {
        this.phase = phase;
    }

    public boolean isConnected() {
        return phase == ConnectionPhase.CONNECTED;
    }

    /**
     * Link state as reported by the connection service (heartbeat) or by a failed attempt. A
     * teardown the user started is never overwritten here: its completion callback owns the
     * transition back to IDLE, otherwise a late link-loss event could strand the UI in the
     * "Disconnecting..." state.
     */
    public void setConnected(boolean connected) {
        if (connected) {
            if (phase == ConnectionPhase.IDLE || phase == ConnectionPhase.CONNECTING) {
                phase = ConnectionPhase.CONNECTED;
            }
        } else if (phase == ConnectionPhase.CONNECTED || phase == ConnectionPhase.CONNECTING) {
            phase = ConnectionPhase.IDLE;
        }
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
        return isConnected()
                && syncManager.getSyncFolder() != null
                && isSender
                && syncManager.isConnectionAlive();
    }
}
