package com.filesync.sync;

/**
 * Enumerates the types of sync events emitted by the core.
 */
public enum SyncEventType {
    CONNECTION_STATUS,
    DIRECTION_CHANGED,
    SYNC_STARTED,
    SYNC_COMPLETE,
    TRANSFER_COMPLETE,
    FILE_PROGRESS,
    TRANSFER_PROGRESS,
    LOG,
    ERROR,
    SHARED_TEXT_RECEIVED
}

