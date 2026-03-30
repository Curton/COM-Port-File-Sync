package com.filesync.sync;

/** Observer interface for receiving sync events. */
@FunctionalInterface
public interface SyncEventListener {
    void onEvent(SyncEvent event);
}
