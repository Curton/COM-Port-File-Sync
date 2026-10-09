package com.filesync.ui;

import com.filesync.sync.SyncEvent;
import com.filesync.sync.SyncEventType;
import javax.swing.SwingUtilities;

/** Adapts sync events to UI updates via controllers and shared UI state. */
public class SyncEventBridge {
    private final SyncController syncController;
    private final LogController logController;
    private final SharedTextController sharedTextController;
    private final FolderController folderController;

    public SyncEventBridge(
            SyncController syncController,
            LogController logController,
            SharedTextController sharedTextController,
            FolderController folderController) {
        this.syncController = syncController;
        this.logController = logController;
        this.sharedTextController = sharedTextController;
        this.folderController = folderController;
    }

    /**
     * Marshals every event to the event dispatch thread before dispatch: events are published from
     * worker threads (listener loop, sync and drop workers, heartbeat ticks), so this single wrap
     * owns EDT marshaling for the whole bridge and the controller handlers it routes to are
     * EDT-by-contract. LogController.log keeps its own invokeLater because it also serves callers
     * outside this bridge from worker threads; the resulting extra EDT hop is harmless.
     */
    public void handleSyncEvent(SyncEvent event) {
        if (event == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> dispatchSyncEvent(event));
    }

    private void dispatchSyncEvent(SyncEvent event) {
        SyncEventType type = event.getType();
        switch (type) {
            case SYNC_STARTED -> syncController.onSyncStarted();
            case SYNC_COMPLETE -> syncController.onSyncComplete();
            case SYNC_CANCELLED -> syncController.onSyncCancelled();
            case TRANSFER_COMPLETE -> syncController.onTransferComplete();
            case FILE_PROGRESS -> {
                SyncEvent.FileProgressEvent fileProgress = (SyncEvent.FileProgressEvent) event;
                syncController.onFileProgress(
                        fileProgress.getCurrentFile(),
                        fileProgress.getTotalFiles(),
                        fileProgress.getFileName());
            }
            case MANIFEST_PROGRESS -> {
                SyncEvent.ManifestProgressEvent manifestProgress =
                        (SyncEvent.ManifestProgressEvent) event;
                syncController.onManifestProgress(
                        manifestProgress.getProcessed(),
                        manifestProgress.getTotal(),
                        manifestProgress.getFileName());
            }
            case TRANSFER_PROGRESS -> {
                SyncEvent.TransferProgressEvent transferProgress =
                        (SyncEvent.TransferProgressEvent) event;
                syncController.onTransferProgress(
                        transferProgress.getCurrentBlock(),
                        transferProgress.getTotalBlocks(),
                        transferProgress.getBytesTransferred(),
                        transferProgress.getSpeedBytesPerSec());
            }
            case SYNC_CONTROL_REFRESH -> syncController.updateSyncButtonState();
            case DIRECTION_CHANGED -> {
                SyncEvent.DirectionEvent directionEvent = (SyncEvent.DirectionEvent) event;
                syncController.applyDirection(directionEvent.isSender());
                logController.log(
                        "[DEBUG] Direction changed by remote, this device is now: "
                                + (directionEvent.isSender() ? "Sender" : "Receiver"));
            }
            case CONNECTION_STATUS -> {
                SyncEvent.ConnectionEvent connectionEvent = (SyncEvent.ConnectionEvent) event;
                syncController.onConnectionStatusChanged(connectionEvent.isConnected());
            }
            case LOG -> {
                SyncEvent.LogEvent logEvent = (SyncEvent.LogEvent) event;
                syncController.onLog(logEvent.getMessage());
            }
            case ERROR -> {
                SyncEvent.ErrorEvent errorEvent = (SyncEvent.ErrorEvent) event;
                syncController.onError(errorEvent.getMessage());
            }
            case SHARED_TEXT_RECEIVED -> {
                SyncEvent.SharedTextReceivedEvent sharedTextEvent =
                        (SyncEvent.SharedTextReceivedEvent) event;
                sharedTextController.onSharedTextReceived(
                        sharedTextEvent.getText(), sharedTextEvent.isAutoCopyToClipboard());
            }
            case DROP_FILE_RECEIVED -> {
                SyncEvent.DropFileReceivedEvent dropFileEvent =
                        (SyncEvent.DropFileReceivedEvent) event;
                logController.log(
                        (dropFileEvent.isUnpackedArchive()
                                        ? "Received dropped folder: "
                                        : "Received dropped file: ")
                                + dropFileEvent.getFileName()
                                + " -> "
                                + dropFileEvent.getFilePath());
            }
            case PENDING_FILE_WRITE -> {
                SyncEvent.PendingWriteEvent pendingWriteEvent = (SyncEvent.PendingWriteEvent) event;
                syncController.onPendingWrites(pendingWriteEvent.getPendingPaths());
            }
            case REMOTE_FOLDER_CHANGED -> {
                SyncEvent.RemoteFolderChangedEvent remoteFolderEvent =
                        (SyncEvent.RemoteFolderChangedEvent) event;
                folderController.onRemoteFolderChanged(remoteFolderEvent.getFolderPath());
            }
            default -> {}
        }
    }
}
