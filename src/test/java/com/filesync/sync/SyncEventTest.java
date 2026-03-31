package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SyncEventTest {

    @Test
    void connectionEventStoresConnectedState() {
        SyncEvent.ConnectionEvent event = new SyncEvent.ConnectionEvent(true);
        assertTrue(event.isConnected());
        assertEquals(SyncEventType.CONNECTION_STATUS, event.getType());
    }

    @Test
    void connectionEventStoresDisconnectedState() {
        SyncEvent.ConnectionEvent event = new SyncEvent.ConnectionEvent(false);
        assertFalse(event.isConnected());
        assertEquals(SyncEventType.CONNECTION_STATUS, event.getType());
    }

    @Test
    void directionEventStoresSenderState() {
        SyncEvent.DirectionEvent event = new SyncEvent.DirectionEvent(true);
        assertTrue(event.isSender());
        assertEquals(SyncEventType.DIRECTION_CHANGED, event.getType());
    }

    @Test
    void directionEventStoresReceiverState() {
        SyncEvent.DirectionEvent event = new SyncEvent.DirectionEvent(false);
        assertFalse(event.isSender());
        assertEquals(SyncEventType.DIRECTION_CHANGED, event.getType());
    }

    @Test
    void syncStartedEventHasCorrectType() {
        SyncEvent.SyncStartedEvent event = new SyncEvent.SyncStartedEvent();
        assertEquals(SyncEventType.SYNC_STARTED, event.getType());
    }

    @Test
    void syncCompleteEventHasCorrectType() {
        SyncEvent.SyncCompleteEvent event = new SyncEvent.SyncCompleteEvent();
        assertEquals(SyncEventType.SYNC_COMPLETE, event.getType());
    }

    @Test
    void syncCancelledEventHasCorrectType() {
        SyncEvent.SyncCancelledEvent event = new SyncEvent.SyncCancelledEvent();
        assertEquals(SyncEventType.SYNC_CANCELLED, event.getType());
    }

    @Test
    void transferCompleteEventHasCorrectType() {
        SyncEvent.TransferCompleteEvent event = new SyncEvent.TransferCompleteEvent();
        assertEquals(SyncEventType.TRANSFER_COMPLETE, event.getType());
    }

    @Test
    void fileProgressEventStoresValues() {
        SyncEvent.FileProgressEvent event = new SyncEvent.FileProgressEvent(1, 10, "test.txt");
        assertEquals(1, event.getCurrentFile());
        assertEquals(10, event.getTotalFiles());
        assertEquals("test.txt", event.getFileName());
        assertEquals(SyncEventType.FILE_PROGRESS, event.getType());
    }

    @Test
    void transferProgressEventStoresValues() {
        SyncEvent.TransferProgressEvent event =
                new SyncEvent.TransferProgressEvent(5, 100, 1024L, 512.5);
        assertEquals(5, event.getCurrentBlock());
        assertEquals(100, event.getTotalBlocks());
        assertEquals(1024L, event.getBytesTransferred());
        assertEquals(512.5, event.getSpeedBytesPerSec());
        assertEquals(SyncEventType.TRANSFER_PROGRESS, event.getType());
    }

    @Test
    void syncControlRefreshEventHasCorrectType() {
        SyncEvent.SyncControlRefreshEvent event = new SyncEvent.SyncControlRefreshEvent();
        assertEquals(SyncEventType.SYNC_CONTROL_REFRESH, event.getType());
    }

    @Test
    void logEventStoresMessage() {
        SyncEvent.LogEvent event = new SyncEvent.LogEvent("Test log message");
        assertEquals("Test log message", event.getMessage());
        assertEquals(SyncEventType.LOG, event.getType());
    }

    @Test
    void errorEventStoresMessage() {
        SyncEvent.ErrorEvent event = new SyncEvent.ErrorEvent("Test error message");
        assertEquals("Test error message", event.getMessage());
        assertEquals(SyncEventType.ERROR, event.getType());
    }

    @Test
    void sharedTextReceivedEventStoresText() {
        SyncEvent.SharedTextReceivedEvent event =
                new SyncEvent.SharedTextReceivedEvent("Hello World");
        assertEquals("Hello World", event.getText());
        assertEquals(SyncEventType.SHARED_TEXT_RECEIVED, event.getType());
    }

    @Test
    void dropFileReceivedEventStoresValues() {
        SyncEvent.DropFileReceivedEvent event =
                new SyncEvent.DropFileReceivedEvent("file.txt", "/path/to/file.txt");
        assertEquals("file.txt", event.getFileName());
        assertEquals("/path/to/file.txt", event.getFilePath());
        assertEquals(SyncEventType.DROP_FILE_RECEIVED, event.getType());
    }

    @Test
    void remoteFolderChangedEventStoresFolderPath() {
        SyncEvent.RemoteFolderChangedEvent event =
                new SyncEvent.RemoteFolderChangedEvent("/remote/folder");
        assertEquals("/remote/folder", event.getFolderPath());
        assertEquals(SyncEventType.REMOTE_FOLDER_CHANGED, event.getType());
    }
}
