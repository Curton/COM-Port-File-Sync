package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SyncEventTypeTest {

    @Test
    void enumContainsAllExpectedValues() {
        assertEquals(14, SyncEventType.values().length);
        assertEquals("CONNECTION_STATUS", SyncEventType.CONNECTION_STATUS.name());
        assertEquals("DIRECTION_CHANGED", SyncEventType.DIRECTION_CHANGED.name());
        assertEquals("SYNC_STARTED", SyncEventType.SYNC_STARTED.name());
        assertEquals("SYNC_COMPLETE", SyncEventType.SYNC_COMPLETE.name());
        assertEquals("SYNC_CANCELLED", SyncEventType.SYNC_CANCELLED.name());
        assertEquals("TRANSFER_COMPLETE", SyncEventType.TRANSFER_COMPLETE.name());
        assertEquals("FILE_PROGRESS", SyncEventType.FILE_PROGRESS.name());
        assertEquals("TRANSFER_PROGRESS", SyncEventType.TRANSFER_PROGRESS.name());
        assertEquals("SYNC_CONTROL_REFRESH", SyncEventType.SYNC_CONTROL_REFRESH.name());
        assertEquals("LOG", SyncEventType.LOG.name());
        assertEquals("ERROR", SyncEventType.ERROR.name());
        assertEquals("SHARED_TEXT_RECEIVED", SyncEventType.SHARED_TEXT_RECEIVED.name());
        assertEquals("DROP_FILE_RECEIVED", SyncEventType.DROP_FILE_RECEIVED.name());
        assertEquals("REMOTE_FOLDER_CHANGED", SyncEventType.REMOTE_FOLDER_CHANGED.name());
    }
}
