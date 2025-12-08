package com.filesync.sync;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe in-process event bus used by the sync core.
 * Listeners are invoked on the calling thread; UI code should marshal to the EDT when needed.
 */
public class SimpleSyncEventBus implements SyncEventBus {

    private final List<SyncEventListener> listeners = new CopyOnWriteArrayList<>();

    @Override
    public void register(SyncEventListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public void unregister(SyncEventListener listener) {
        listeners.remove(listener);
    }

    @Override
    public void post(SyncEvent event) {
        if (event == null) {
            return;
        }
        for (SyncEventListener listener : listeners) {
            listener.onEvent(event);
        }
    }

    public void clear() {
        listeners.clear();
    }
}

