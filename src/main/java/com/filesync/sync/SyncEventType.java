package com.filesync.sync;

/**
 * Enumerates the types of sync events emitted by the core.
 */
public enum SyncEventType {
    CONNECTION_STATUS,
    DIRECTION_CHANGED,
    SYNC_STARTED,
    SYNC_COMPLETE,
    SYNC_CANCELLED,
    TRANSFER_COMPLETE,
    FILE_PROGRESS,
    TRANSFER_PROGRESS,
    /** Re-evaluate Sync Control enablement (e.g. after manifest XMODEM without SYNC_COMPLETE). */
    SYNC_CONTROL_REFRESH,
    LOG,
    ERROR,
    SHARED_TEXT_RECEIVED,
    DROP_FILE_RECEIVED,
    REMOTE_FOLDER_CHANGED
}

