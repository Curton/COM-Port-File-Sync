package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SyncEventTest {

    @Test
    void eventTypeEnumHasNoUnexpectedValues() {
        // Guards against a new SyncEventType constant being added without an event test here.
        assertEquals(16, SyncEventType.values().length);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void connectionEventStoresConnectedState(boolean connected) {
        SyncEvent.ConnectionEvent event = new SyncEvent.ConnectionEvent(connected);
        assertEquals(connected, event.isConnected());
        assertEquals(SyncEventType.CONNECTION_STATUS, event.getType());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void directionEventStoresSenderState(boolean sender) {
        SyncEvent.DirectionEvent event = new SyncEvent.DirectionEvent(sender);
        assertEquals(sender, event.isSender());
        assertEquals(SyncEventType.DIRECTION_CHANGED, event.getType());
    }

    @Test
    void markerEventsHaveCorrectTypes() {
        assertEquals(SyncEventType.SYNC_STARTED, new SyncEvent.SyncStartedEvent().getType());
        assertEquals(SyncEventType.SYNC_COMPLETE, new SyncEvent.SyncCompleteEvent().getType());
        assertEquals(SyncEventType.SYNC_CANCELLED, new SyncEvent.SyncCancelledEvent().getType());
        assertEquals(
                SyncEventType.TRANSFER_COMPLETE, new SyncEvent.TransferCompleteEvent().getType());
        assertEquals(
                SyncEventType.SYNC_CONTROL_REFRESH,
                new SyncEvent.SyncControlRefreshEvent().getType());
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
    void logAndErrorEventsStoreMessage() {
        SyncEvent.LogEvent logEvent = new SyncEvent.LogEvent("Test log message");
        assertEquals("Test log message", logEvent.getMessage());
        assertEquals(SyncEventType.LOG, logEvent.getType());

        SyncEvent.ErrorEvent errorEvent = new SyncEvent.ErrorEvent("Test error message");
        assertEquals("Test error message", errorEvent.getMessage());
        assertEquals(SyncEventType.ERROR, errorEvent.getType());
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
