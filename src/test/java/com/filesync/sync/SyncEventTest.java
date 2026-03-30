package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for SyncEvent classes to improve code coverage. These tests cover the event classes
 * that are not directly instantiated in other test classes.
 */
class SyncEventTest {

    @Test
    void transferProgressEventStoresAllValues() {
        SyncEvent.TransferProgressEvent event =
                new SyncEvent.TransferProgressEvent(
                        50, // currentBlock
                        100, // totalBlocks
                        5000L, // bytesTransferred
                        1024.5 // speedBytesPerSec
                        );

        assertEquals(50, event.getCurrentBlock());
        assertEquals(100, event.getTotalBlocks());
        assertEquals(5000L, event.getBytesTransferred());
        assertEquals(1024.5, event.getSpeedBytesPerSec(), 0.001);
        assertEquals(SyncEventType.TRANSFER_PROGRESS, event.getType());
    }

    @Test
    void dropFileReceivedEventStoresAllValues() {
        SyncEvent.DropFileReceivedEvent event =
                new SyncEvent.DropFileReceivedEvent("test.txt", "/path/to/test.txt");

        assertEquals("test.txt", event.getFileName());
        assertEquals("/path/to/test.txt", event.getFilePath());
        assertEquals(SyncEventType.DROP_FILE_RECEIVED, event.getType());
    }

    @Test
    void remoteFolderChangedEventStoresFolderPath() {
        SyncEvent.RemoteFolderChangedEvent event =
                new SyncEvent.RemoteFolderChangedEvent("/remote/folder/path");

        assertEquals("/remote/folder/path", event.getFolderPath());
        assertEquals(SyncEventType.REMOTE_FOLDER_CHANGED, event.getType());
    }

    @Test
    void syncControlRefreshEventReturnsCorrectType() {
        SyncEvent.SyncControlRefreshEvent event = new SyncEvent.SyncControlRefreshEvent();

        assertEquals(SyncEventType.SYNC_CONTROL_REFRESH, event.getType());
    }

    @Test
    void connectionEventStoresConnectedState() {
        SyncEvent.ConnectionEvent connectedEvent = new SyncEvent.ConnectionEvent(true);
        SyncEvent.ConnectionEvent disconnectedEvent = new SyncEvent.ConnectionEvent(false);

        assertEquals(true, connectedEvent.isConnected());
        assertEquals(false, disconnectedEvent.isConnected());
        assertEquals(SyncEventType.CONNECTION_STATUS, connectedEvent.getType());
        assertSame(SyncEventType.CONNECTION_STATUS, disconnectedEvent.getType());
    }

    @Test
    void directionEventStoresSenderState() {
        SyncEvent.DirectionEvent senderEvent = new SyncEvent.DirectionEvent(true);
        SyncEvent.DirectionEvent receiverEvent = new SyncEvent.DirectionEvent(false);

        assertEquals(true, senderEvent.isSender());
        assertEquals(false, receiverEvent.isSender());
        assertEquals(SyncEventType.DIRECTION_CHANGED, senderEvent.getType());
        assertSame(SyncEventType.DIRECTION_CHANGED, receiverEvent.getType());
    }

    @Test
    void syncStartedEventReturnsCorrectType() {
        SyncEvent.SyncStartedEvent event = new SyncEvent.SyncStartedEvent();

        assertEquals(SyncEventType.SYNC_STARTED, event.getType());
    }

    @Test
    void syncCompleteEventReturnsCorrectType() {
        SyncEvent.SyncCompleteEvent event = new SyncEvent.SyncCompleteEvent();

        assertEquals(SyncEventType.SYNC_COMPLETE, event.getType());
    }

    @Test
    void syncCancelledEventReturnsCorrectType() {
        SyncEvent.SyncCancelledEvent event = new SyncEvent.SyncCancelledEvent();

        assertEquals(SyncEventType.SYNC_CANCELLED, event.getType());
    }

    @Test
    void transferCompleteEventReturnsCorrectType() {
        SyncEvent.TransferCompleteEvent event = new SyncEvent.TransferCompleteEvent();

        assertEquals(SyncEventType.TRANSFER_COMPLETE, event.getType());
    }

    @Test
    void fileProgressEventStoresAllValues() {
        SyncEvent.FileProgressEvent event =
                new SyncEvent.FileProgressEvent(
                        3, // currentFile
                        10, // totalFiles
                        "data.zip");

        assertEquals(3, event.getCurrentFile());
        assertEquals(10, event.getTotalFiles());
        assertEquals("data.zip", event.getFileName());
        assertEquals(SyncEventType.FILE_PROGRESS, event.getType());
    }

    @Test
    void logEventStoresMessage() {
        SyncEvent.LogEvent event = new SyncEvent.LogEvent("Test log message");

        assertEquals("Test log message", event.getMessage());
        assertEquals(SyncEventType.LOG, event.getType());
    }

    @Test
    void errorEventStoresMessage() {
        SyncEvent.ErrorEvent event = new SyncEvent.ErrorEvent("Error occurred");

        assertEquals("Error occurred", event.getMessage());
        assertEquals(SyncEventType.ERROR, event.getType());
    }

    @Test
    void sharedTextReceivedEventStoresText() {
        SyncEvent.SharedTextReceivedEvent event =
                new SyncEvent.SharedTextReceivedEvent("Hello world");

        assertEquals("Hello world", event.getText());
        assertEquals(SyncEventType.SHARED_TEXT_RECEIVED, event.getType());
    }
}
