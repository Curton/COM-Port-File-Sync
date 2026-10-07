package com.filesync.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

import com.filesync.sync.SyncEvent;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
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
    @Mock private FolderController folderController;

    private SyncEventBridge bridge;

    @BeforeEach
    void setUp() {
        bridge =
                new SyncEventBridge(
                        syncController, logController, sharedTextController, folderController);
    }

    @Test
    void handleSyncEventWithNullDoesNothing() {
        bridge.handleSyncEvent(null);
        verifyNoInteractions(syncController, logController, sharedTextController, folderController);
    }

    /**
     * The bridge marshals every event to the EDT, so synchronous verifies would race the queued
     * dispatch; the invokeAndWait barrier drains the queue (dispatches are FIFO) first.
     */
    @Test
    void handleSyncEventRoutesEachEventTypeToItsHandler() throws Exception {
        String logMessage = "Test log message";
        String errorMessage = "Test error";
        String sharedText = "Shared text content";

        bridge.handleSyncEvent(new SyncEvent.SyncStartedEvent());
        bridge.handleSyncEvent(new SyncEvent.SyncCompleteEvent());
        bridge.handleSyncEvent(new SyncEvent.SyncCancelledEvent());
        bridge.handleSyncEvent(new SyncEvent.TransferCompleteEvent());
        bridge.handleSyncEvent(new SyncEvent.LogEvent(logMessage));
        bridge.handleSyncEvent(new SyncEvent.ErrorEvent(errorMessage));
        bridge.handleSyncEvent(new SyncEvent.SharedTextReceivedEvent(sharedText));
        bridge.handleSyncEvent(new SyncEvent.ConnectionEvent(true));
        bridge.handleSyncEvent(new SyncEvent.ConnectionEvent(false));
        bridge.handleSyncEvent(new SyncEvent.SyncControlRefreshEvent());
        bridge.handleSyncEvent(new SyncEvent.DirectionEvent(true));
        bridge.handleSyncEvent(new SyncEvent.DropFileReceivedEvent("notes.txt", "/tmp", false));
        SwingUtilities.invokeAndWait(() -> {});

        verify(syncController).onSyncStarted();
        verify(syncController).onSyncComplete();
        verify(syncController).onSyncCancelled();
        verify(syncController).onTransferComplete();
        verify(syncController).onLog(logMessage);
        verify(syncController).onError(errorMessage);
        verify(sharedTextController).onSharedTextReceived(sharedText);
        verify(syncController).onConnectionStatusChanged(true);
        verify(syncController).onConnectionStatusChanged(false);
        verify(syncController).updateSyncButtonState();
        verify(syncController).applyDirection(true);
        verify(logController).log("Received dropped file: notes.txt -> /tmp");
    }

    @Test
    void remoteFolderChangedEvent_isRoutedToFolderControllerOnEdt() throws Exception {
        String folderPath = "/sync/folder";
        bridge.handleSyncEvent(new SyncEvent.RemoteFolderChangedEvent(folderPath));
        SwingUtilities.invokeAndWait(() -> {});

        verify(folderController).onRemoteFolderChanged(folderPath);
        verifyNoInteractions(syncController);
    }

    // ========== Pending write events (locked received files) ==========

    @Test
    void pendingWriteEvent_isRoutedToControllerOnEdt() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<List<String>> received = new AtomicReference<>();
        doAnswer(
                        invocation -> {
                            received.set(invocation.getArgument(0));
                            latch.countDown();
                            return null;
                        })
                .when(syncController)
                .onPendingWrites(anyList());

        bridge.handleSyncEvent(new SyncEvent.PendingWriteEvent(List.of("a.txt", "b.txt")));

        assertTrue(
                latch.await(5, TimeUnit.SECONDS),
                "onPendingWrites must be invoked (asynchronously on the EDT)");
        assertEquals(List.of("a.txt", "b.txt"), received.get());
    }

    @Test
    void pendingWriteEvent_emptyList_isRoutedToCloseDialog() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(
                        invocation -> {
                            latch.countDown();
                            return null;
                        })
                .when(syncController)
                .onPendingWrites(anyList());

        bridge.handleSyncEvent(new SyncEvent.PendingWriteEvent(List.of()));

        assertTrue(
                latch.await(5, TimeUnit.SECONDS),
                "An empty list must still reach the controller to close the dialog");
    }
}
