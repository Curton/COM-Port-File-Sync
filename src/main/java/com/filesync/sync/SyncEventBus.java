package com.filesync.sync;

/**
 * Simple event bus abstraction to decouple sync core from UI threading concerns.
 */
public interface SyncEventBus {
    void register(SyncEventListener listener);

    void unregister(SyncEventListener listener);

    void post(SyncEvent event);
}

