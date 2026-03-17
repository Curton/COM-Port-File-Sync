package com.filesync.sync;

import com.filesync.protocol.SyncProtocol;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
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
    private final AtomicReference<SharedTextPayload> pendingSharedText = new AtomicReference<>();
    private final AtomicReference<SharedTextPayload> latestSharedText = new AtomicReference<>();
    private final AtomicLong latestAcceptedTimestamp = new AtomicLong(0);

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
        String normalizedText = normalizeText(text);
        SharedTextPayload payload = new SharedTextPayload(System.currentTimeMillis(), normalizedText);
        latestSharedText.set(payload);
        pendingSharedText.set(payload);
        flushIfIdle();
    }

    public void resendLatestSharedText() {
        SharedTextPayload latestText = latestSharedText.get();
        if (latestText == null) {
            return;
        }
        pendingSharedText.set(latestText);
        flushIfIdle();
    }

    public void flushIfIdle() {
        while (true) {
            SharedTextPayload textToSend = pendingSharedText.get();
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
                protocol.sendSharedText(textToSend.timestamp, textToSend.text);
                if (pendingSharedText.compareAndSet(textToSend, null)) {
                    return;
                }
            } catch (IOException e) {
                eventBus.post(new SyncEvent.ErrorEvent("Failed to send shared text: " + e.getMessage()));
                return;
            }
        }
    }

    public void handleIncomingSharedText(String encodedPayload) {
        handleIncomingSharedText(System.currentTimeMillis(), encodedPayload);
    }

    public void handleIncomingSharedText(long remoteTimestamp, String encodedPayload) {
        try {
            SharedTextPayload incoming = decodeSharedTextPayload(remoteTimestamp, encodedPayload);
            if (incoming == null) {
                return;
            }
            if (!isNewerThanLatest(incoming.timestamp)) {
                return;
            }
            markTimestampIfNewer(incoming.timestamp);
            latestSharedText.set(incoming);
            eventBus.post(new SyncEvent.SharedTextReceivedEvent(incoming.text));
        } catch (IllegalArgumentException e) {
            eventBus.post(new SyncEvent.ErrorEvent("Failed to decode shared text: " + e.getMessage()));
        }
    }

    public void handleIncomingSharedTextData(long remoteTimestamp, boolean wasCompressed, int expectedSize) {
        try {
            String text = protocol.receiveSharedTextData(wasCompressed, expectedSize);
            SharedTextPayload incoming = new SharedTextPayload(remoteTimestamp, normalizeText(text));
            if (!isNewerThanLatest(incoming.timestamp)) {
                return;
            }
            markTimestampIfNewer(incoming.timestamp);
            latestSharedText.set(incoming);
            eventBus.post(new SyncEvent.SharedTextReceivedEvent(incoming.text));
        } catch (IOException e) {
            eventBus.post(new SyncEvent.ErrorEvent("Failed to receive shared text: " + e.getMessage()));
        }
    }

    public void onSyncIdle() {
        flushIfIdle();
    }

    public void clearPendingSharedText() {
        pendingSharedText.set(null);
        latestSharedText.set(null);
        latestAcceptedTimestamp.set(0);
    }

    private SharedTextPayload decodeSharedTextPayload(long remoteTimestamp, String encodedPayload) {
        try {
            String text = protocol.decodeSharedText(encodedPayload);
            return new SharedTextPayload(remoteTimestamp, normalizeText(text));
        } catch (IllegalArgumentException e) {
            throw e;
        }
    }

    private boolean isNewerThanLatest(long remoteTimestamp) {
        return remoteTimestamp > latestAcceptedTimestamp.get();
    }

    private void markTimestampIfNewer(long remoteTimestamp) {
        while (true) {
            long current = latestAcceptedTimestamp.get();
            if (remoteTimestamp <= current) {
                return;
            }
            if (latestAcceptedTimestamp.compareAndSet(current, remoteTimestamp)) {
                return;
            }
        }
    }

    private String normalizeText(String text) {
        return Objects.requireNonNullElse(text, "");
    }

    private record SharedTextPayload(long timestamp, String text) {
    }
}

