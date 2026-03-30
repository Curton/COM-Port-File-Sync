package com.filesync.sync;

import com.filesync.protocol.SyncProtocol;
import java.io.IOException;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** Handles sender/receiver role negotiation and propagation. */
public class RoleNegotiationService {

    private final SyncProtocol protocol;
    private final SyncEventBus eventBus;
    private final AtomicBoolean isSender;
    private final AtomicBoolean roleNegotiated;
    private final AtomicLong localPriority;
    private final AtomicLong localTieBreaker;
    private final Random random;
    private final BooleanSupplier connectionAliveSupplier;

    public RoleNegotiationService(
            SyncProtocol protocol,
            SyncEventBus eventBus,
            AtomicBoolean isSender,
            AtomicBoolean roleNegotiated,
            BooleanSupplier connectionAliveSupplier) {
        this.protocol = protocol;
        this.eventBus = eventBus;
        this.isSender = isSender;
        this.roleNegotiated = roleNegotiated;
        this.connectionAliveSupplier = connectionAliveSupplier;
        this.random = new Random();
        this.localPriority = new AtomicLong();
        this.localTieBreaker = new AtomicLong();
        refreshPriority();
        refreshTieBreaker();
    }

    public boolean isSender() {
        return isSender.get();
    }

    public void setSender(boolean sender) {
        isSender.set(sender);
        roleNegotiated.set(true);
        eventBus.post(new SyncEvent.DirectionEvent(sender));
    }

    public boolean isRoleNegotiated() {
        return roleNegotiated.get();
    }

    public synchronized boolean confirmCurrentRoleIfNeeded(boolean sender) {
        if (roleNegotiated.get()) {
            return false;
        }
        isSender.set(sender);
        roleNegotiated.set(true);
        return true;
    }

    public void resetForReconnect() {
        roleNegotiated.set(false);
        refreshPriority();
        refreshTieBreaker();
    }

    public void sendRoleNegotiation() {
        if (roleNegotiated.get() || !connectionAliveSupplier.getAsBoolean()) {
            return;
        }
        try {
            protocol.sendRoleNegotiate(localPriority.get(), localTieBreaker.get());
        } catch (IOException e) {
            eventBus.post(
                    new SyncEvent.ErrorEvent("Failed to send role negotiation: " + e.getMessage()));
        }
    }

    public void handleRoleNegotiate(long remotePriority) {
        handleRoleNegotiate(remotePriority, 0L);
    }

    public void handleRoleNegotiate(long remotePriority, long remoteTieBreaker) {
        if (roleNegotiated.get()) {
            return;
        }

        boolean shouldBeSender = shouldBeSenderForNegotiation(remotePriority, remoteTieBreaker);

        if (isSender.get() != shouldBeSender) {
            isSender.set(shouldBeSender);
        }

        roleNegotiated.set(true);

        eventBus.post(new SyncEvent.DirectionEvent(isSender.get()));
        eventBus.post(
                new SyncEvent.LogEvent(
                        "Role negotiated: " + (isSender.get() ? "Sender" : "Receiver")));

        try {
            protocol.sendRoleNegotiate(localPriority.get(), localTieBreaker.get());
        } catch (IOException e) {
            eventBus.post(
                    new SyncEvent.ErrorEvent(
                            "Failed to respond to role negotiation: " + e.getMessage()));
        }
    }

    public void handleDirectionChange(boolean remoteSender) {
        isSender.set(!remoteSender);
        roleNegotiated.set(true);
        eventBus.post(new SyncEvent.DirectionEvent(isSender.get()));
    }

    public void notifyDirectionChange() {
        try {
            protocol.sendDirectionChange(isSender.get());
        } catch (IOException e) {
            eventBus.post(
                    new SyncEvent.ErrorEvent(
                            "Failed to notify direction change: " + e.getMessage()));
        }
    }

    private void refreshPriority() {
        localPriority.set(System.currentTimeMillis() * 1000 + random.nextInt(1000));
    }

    private void refreshTieBreaker() {
        long tieBreaker = random.nextLong(Long.MAX_VALUE);
        localTieBreaker.set(tieBreaker == 0L ? 1L : tieBreaker);
    }

    private boolean shouldBeSenderForNegotiation(long remotePriority, long remoteTieBreaker) {
        int priorityCompare = Long.compare(localPriority.get(), remotePriority);
        if (priorityCompare != 0) {
            return priorityCompare > 0;
        }

        int tieCompare = Long.compare(localTieBreaker.get(), remoteTieBreaker);
        if (tieCompare != 0) {
            return tieCompare > 0;
        }

        return true;
    }
}
