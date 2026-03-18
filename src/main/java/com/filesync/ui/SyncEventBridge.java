package com.filesync.ui;

import javax.swing.SwingUtilities;

import com.filesync.sync.SyncEvent;
import com.filesync.sync.SyncEventType;

/**
 * Adapts sync events to UI updates via controllers and shared UI state.
 */
public class SyncEventBridge {
    private final SyncController syncController;
    private final LogController logController;
    private final SharedTextController sharedTextController;

    public SyncEventBridge(SyncController syncController,
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
            case SYNC_STARTED:
                syncController.onSyncStarted();
                break;
            case SYNC_COMPLETE:
                syncController.onSyncComplete();
                break;
            case SYNC_CANCELLED:
                syncController.onSyncCancelled();
                break;
            case TRANSFER_COMPLETE:
                syncController.onTransferComplete();
                break;
            case FILE_PROGRESS:
                SyncEvent.FileProgressEvent fileProgress = (SyncEvent.FileProgressEvent) event;
                SwingUtilities.invokeLater(() -> syncController.onFileProgress(fileProgress.getCurrentFile(),
                        fileProgress.getTotalFiles(),
                        fileProgress.getFileName()));
                break;
            case TRANSFER_PROGRESS:
                SyncEvent.TransferProgressEvent transferProgress = (SyncEvent.TransferProgressEvent) event;
                SwingUtilities.invokeLater(() -> syncController.onTransferProgress(
                        transferProgress.getCurrentBlock(),
                        transferProgress.getTotalBlocks(),
                        transferProgress.getBytesTransferred(),
                        transferProgress.getSpeedBytesPerSec()));
                break;
            case DIRECTION_CHANGED:
                SyncEvent.DirectionEvent directionEvent = (SyncEvent.DirectionEvent) event;
                SwingUtilities.invokeLater(() -> {
                    syncController.applyDirection(directionEvent.isSender());
                    logController.log("Direction changed by remote: " + (directionEvent.isSender() ? "Sender" : "Receiver"));
                });
                break;
            case CONNECTION_STATUS:
                SyncEvent.ConnectionEvent connectionEvent = (SyncEvent.ConnectionEvent) event;
                syncController.onConnectionStatusChanged(connectionEvent.isConnected());
                break;
            case LOG:
                SyncEvent.LogEvent logEvent = (SyncEvent.LogEvent) event;
                syncController.onLog(logEvent.getMessage());
                break;
            case ERROR:
                SyncEvent.ErrorEvent errorEvent = (SyncEvent.ErrorEvent) event;
                syncController.onError(errorEvent.getMessage());
                break;
            case SHARED_TEXT_RECEIVED:
                SyncEvent.SharedTextReceivedEvent sharedTextEvent = (SyncEvent.SharedTextReceivedEvent) event;
                sharedTextController.onSharedTextReceived(sharedTextEvent.getText());
                break;
            case DROP_FILE_RECEIVED:
                SyncEvent.DropFileReceivedEvent dropFileEvent = (SyncEvent.DropFileReceivedEvent) event;
                SwingUtilities.invokeLater(() -> logController.log(
                        "Received dropped file: " + dropFileEvent.getFileName() + " -> " + dropFileEvent.getFilePath()));
                break;
            default:
                break;
        }
    }
}
