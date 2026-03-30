package com.filesync.ui;

import static org.mockito.Mockito.*;

import com.filesync.sync.SyncEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SyncEventBridgeTest {

    @Mock private SyncController syncController;
    @Mock private LogController logController;
    @Mock private SharedTextController sharedTextController;

    private SyncEventBridge bridge;

    @BeforeEach
    void setUp() {
        bridge = new SyncEventBridge(syncController, logController, sharedTextController);
    }

    @Test
    void handleSyncEventWithNullDoesNothing() {
        bridge.handleSyncEvent(null);
        verifyNoInteractions(syncController, logController, sharedTextController);
    }

    @Test
    void handleSyncStartedRoutesToController() {
        SyncEvent event = new SyncEvent.SyncStartedEvent();

        bridge.handleSyncEvent(event);

        verify(syncController).onSyncStarted();
    }

    @Test
    void handleSyncCompleteRoutesToController() {
        SyncEvent event = new SyncEvent.SyncCompleteEvent();

        bridge.handleSyncEvent(event);

        verify(syncController).onSyncComplete();
    }

    @Test
    void handleSyncCancelledRoutesToController() {
        SyncEvent event = new SyncEvent.SyncCancelledEvent();

        bridge.handleSyncEvent(event);

        verify(syncController).onSyncCancelled();
    }

    @Test
    void handleTransferCompleteRoutesToController() {
        SyncEvent event = new SyncEvent.TransferCompleteEvent();

        bridge.handleSyncEvent(event);

        verify(syncController).onTransferComplete();
    }

    @Test
    void handleLogEventRoutesToController() {
        String logMessage = "Test log message";
        SyncEvent.LogEvent event = new SyncEvent.LogEvent(logMessage);

        bridge.handleSyncEvent(event);

        verify(syncController).onLog(logMessage);
    }

    @Test
    void handleErrorEventRoutesToController() {
        String errorMessage = "Test error";
        SyncEvent.ErrorEvent event = new SyncEvent.ErrorEvent(errorMessage);

        bridge.handleSyncEvent(event);

        verify(syncController).onError(errorMessage);
    }

    @Test
    void handleSharedTextReceivedRoutesToSharedTextController() {
        String text = "Shared text content";
        SyncEvent.SharedTextReceivedEvent event = new SyncEvent.SharedTextReceivedEvent(text);

        bridge.handleSyncEvent(event);

        verify(sharedTextController).onSharedTextReceived(text);
    }

    @Test
    void handleConnectionStatusRoutesToController() {
        SyncEvent.ConnectionEvent event = new SyncEvent.ConnectionEvent(true);

        bridge.handleSyncEvent(event);

        verify(syncController).onConnectionStatusChanged(true);
    }

    @Test
    void handleConnectionStatusDisconnectedRoutesToController() {
        SyncEvent.ConnectionEvent event = new SyncEvent.ConnectionEvent(false);

        bridge.handleSyncEvent(event);

        verify(syncController).onConnectionStatusChanged(false);
    }

    @Test
    void handleRemoteFolderChangedEventIsIgnored() {
        String folderPath = "/sync/folder";
        SyncEvent.RemoteFolderChangedEvent event =
                new SyncEvent.RemoteFolderChangedEvent(folderPath);

        bridge.handleSyncEvent(event);

        verifyNoInteractions(syncController);
    }
}
