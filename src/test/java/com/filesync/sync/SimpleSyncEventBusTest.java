package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Unit tests for SimpleSyncEventBus to improve code coverage. */
class SimpleSyncEventBusTest {

    @Test
    void registerAndPostDeliversEventToListener() {
        SimpleSyncEventBus bus = new SimpleSyncEventBus();
        AtomicInteger eventCount = new AtomicInteger(0);

        bus.register(event -> eventCount.incrementAndGet());

        bus.post(new SyncEvent.SyncStartedEvent());
        assertEquals(1, eventCount.get());
    }

    @Test
    void postNullEventDoesNotDeliverToListeners() {
        SimpleSyncEventBus bus = new SimpleSyncEventBus();
        AtomicInteger eventCount = new AtomicInteger(0);

        bus.register(event -> eventCount.incrementAndGet());

        bus.post(null);

        assertEquals(0, eventCount.get(), "Null event should not be delivered");
    }

    @Test
    void multipleListenersAllReceiveEvents() {
        SimpleSyncEventBus bus = new SimpleSyncEventBus();
        AtomicInteger count1 = new AtomicInteger(0);
        AtomicInteger count2 = new AtomicInteger(0);

        bus.register(event -> count1.incrementAndGet());
        bus.register(event -> count2.incrementAndGet());

        bus.post(new SyncEvent.SyncStartedEvent());

        assertEquals(1, count1.get());
        assertEquals(1, count2.get());
    }

    @Test
    void unregisterRemovesListener() {
        SimpleSyncEventBus bus = new SimpleSyncEventBus();
        AtomicInteger eventCount = new AtomicInteger(0);
        SyncEventListener listener = event -> eventCount.incrementAndGet();

        bus.register(listener);
        bus.post(new SyncEvent.SyncStartedEvent());
        assertEquals(1, eventCount.get());

        bus.unregister(listener);
        bus.post(new SyncEvent.SyncStartedEvent());
        assertEquals(1, eventCount.get(), "After unregister, listener should not receive events");
    }

    @Test
    void unregisterNonExistentListenerDoesNotThrow() {
        SimpleSyncEventBus bus = new SimpleSyncEventBus();
        AtomicInteger eventCount = new AtomicInteger(0);

        bus.register(event -> eventCount.incrementAndGet());

        // Unregistering a listener that was never registered should not throw
        bus.unregister(event -> {});

        bus.post(new SyncEvent.SyncStartedEvent());
        assertEquals(1, eventCount.get());
    }

    @Test
    void clearRemovesAllListeners() {
        SimpleSyncEventBus bus = new SimpleSyncEventBus();
        AtomicInteger eventCount = new AtomicInteger(0);

        bus.register(event -> eventCount.incrementAndGet());
        bus.register(event -> eventCount.incrementAndGet());

        bus.post(new SyncEvent.SyncStartedEvent());
        assertEquals(2, eventCount.get());

        bus.clear();

        bus.post(new SyncEvent.SyncStartedEvent());
        assertEquals(2, eventCount.get(), "After clear, no listeners should receive events");
    }

    @Test
    void postToEmptyBusDoesNotThrow() {
        SimpleSyncEventBus bus = new SimpleSyncEventBus();

        // Posting to an empty bus should not throw
        bus.post(new SyncEvent.SyncStartedEvent());
    }

    @Test
    void registerNullListenerDoesNotThrow() {
        SimpleSyncEventBus bus = new SimpleSyncEventBus();

        // Registering null should not throw and should not cause issues when posting
        bus.register(null);

        bus.post(new SyncEvent.SyncStartedEvent());
    }

    @Test
    void differentEventTypesArePostedCorrectly() {
        SimpleSyncEventBus bus = new SimpleSyncEventBus();
        AtomicInteger syncStartedCount = new AtomicInteger(0);
        AtomicInteger syncCompleteCount = new AtomicInteger(0);

        bus.register(
                event -> {
                    if (event instanceof SyncEvent.SyncStartedEvent) {
                        syncStartedCount.incrementAndGet();
                    } else if (event instanceof SyncEvent.SyncCompleteEvent) {
                        syncCompleteCount.incrementAndGet();
                    }
                });

        bus.post(new SyncEvent.SyncStartedEvent());
        bus.post(new SyncEvent.SyncCompleteEvent());
        bus.post(new SyncEvent.SyncStartedEvent());

        assertEquals(2, syncStartedCount.get());
        assertEquals(1, syncCompleteCount.get());
    }
}
