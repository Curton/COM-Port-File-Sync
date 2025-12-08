package com.filesync.sync;

/**
 * Observer interface for receiving sync events.
 */
public interface SyncEventListener {
    void onEvent(SyncEvent event);
}

