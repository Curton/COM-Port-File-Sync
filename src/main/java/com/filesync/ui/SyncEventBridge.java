package com.filesync.ui;

import com.filesync.sync.SyncEvent;
import com.filesync.sync.SyncEventType;
import javax.swing.SwingUtilities;

/** Adapts sync events to UI updates via controllers and shared UI state. */
public class SyncEventBridge {
    private final SyncController syncController;
    private final LogController logController;
    private final SharedTextController sharedTextController;

    public SyncEventBridge(
            SyncController syncController,
            LogController logController,
            SharedTextController sharedTextController) {
        this.syncController = syncController;
        this.logController = logController;
        this.sharedTextController = sharedTextController;
    }

    public void handleSyncEvent(SyncEvent event) {
        if (event == null) {
            return;
        }

        SyncEventType type = event.getType();
        switch (type) {
            case SYNC_STARTED -> syncController.onSyncStarted();
            case SYNC_COMPLETE -> syncController.onSyncComplete();
            case SYNC_CANCELLED -> syncController.onSyncCancelled();
            case TRANSFER_COMPLETE -> syncController.onTransferComplete();
            case FILE_PROGRESS -> {
                SyncEvent.FileProgressEvent fileProgress = (SyncEvent.FileProgressEvent) event;
                SwingUtilities.invokeLater(
                        () ->
                                syncController.onFileProgress(
                                        fileProgress.getCurrentFile(),
                                        fileProgress.getTotalFiles(),
                                        fileProgress.getFileName()));
            }
            case TRANSFER_PROGRESS -> {
                SyncEvent.TransferProgressEvent transferProgress =
                        (SyncEvent.TransferProgressEvent) event;
                SwingUtilities.invokeLater(
                        () ->
                                syncController.onTransferProgress(
                                        transferProgress.getCurrentBlock(),
                                        transferProgress.getTotalBlocks(),
                                        transferProgress.getBytesTransferred(),
                                        transferProgress.getSpeedBytesPerSec()));
            }
            case SYNC_CONTROL_REFRESH ->
                    SwingUtilities.invokeLater(syncController::updateSyncButtonState);
            case DIRECTION_CHANGED -> {
                SyncEvent.DirectionEvent directionEvent = (SyncEvent.DirectionEvent) event;
                SwingUtilities.invokeLater(
                        () -> {
                            syncController.applyDirection(directionEvent.isSender());
                            logController.log(
                                    "[DEBUG] Direction changed by remote, this device is now: "
                                            + (directionEvent.isSender() ? "Sender" : "Receiver"));
                        });
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
                sharedTextController.onSharedTextReceived(sharedTextEvent.getText());
            }
            case DROP_FILE_RECEIVED -> {
                SyncEvent.DropFileReceivedEvent dropFileEvent =
                        (SyncEvent.DropFileReceivedEvent) event;
                SwingUtilities.invokeLater(
                        () ->
                                logController.log(
                                        "Received dropped file: "
                                                + dropFileEvent.getFileName()
                                                + " -> "
                                                + dropFileEvent.getFilePath()));
            }
            default -> {}
        }
    }
}
