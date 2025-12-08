package com.filesync.sync;

import com.filesync.protocol.SyncProtocol;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Handles shared text send/receive with back-pressure during transfers.
 */
public class SharedTextService {

    private final SyncProtocol protocol;
    private final SyncEventBus eventBus;
    private final BooleanSupplier runningSupplier;
    private final BooleanSupplier connectionAliveSupplier;
    private final BooleanSupplier syncingSupplier;
    private final BooleanSupplier transferBusySupplier;
    private final AtomicReference<String> pendingSharedText = new AtomicReference<>();

    public SharedTextService(SyncProtocol protocol,
                             SyncEventBus eventBus,
                             BooleanSupplier runningSupplier,
                             BooleanSupplier connectionAliveSupplier,
                             BooleanSupplier syncingSupplier,
                             BooleanSupplier transferBusySupplier) {
        this.protocol = protocol;
        this.eventBus = eventBus;
        this.runningSupplier = runningSupplier;
        this.connectionAliveSupplier = connectionAliveSupplier;
        this.syncingSupplier = syncingSupplier;
        this.transferBusySupplier = transferBusySupplier;
    }

    public void queueSharedText(String text) {
        pendingSharedText.set(text);
        flushIfIdle();
    }

    public void flushIfIdle() {
        String textToSend = pendingSharedText.get();
        if (textToSend == null) {
            return;
        }
        if (!runningSupplier.getAsBoolean() || !connectionAliveSupplier.getAsBoolean()) {
            eventBus.post(new SyncEvent.ErrorEvent("Cannot send shared text - not connected"));
            return;
        }
        if (syncingSupplier.getAsBoolean() || transferBusySupplier.getAsBoolean()) {
            return;
        }
        try {
            protocol.sendSharedText(textToSend);
            pendingSharedText.compareAndSet(textToSend, null);
        } catch (IOException e) {
            eventBus.post(new SyncEvent.ErrorEvent("Failed to send shared text: " + e.getMessage()));
        }
    }

    public void handleIncomingSharedText(String encodedPayload) {
        String text = protocol.decodeSharedText(encodedPayload);
        eventBus.post(new SyncEvent.SharedTextReceivedEvent(text));
    }

    public void onSyncIdle() {
        flushIfIdle();
    }
}

