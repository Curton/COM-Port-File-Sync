package com.filesync.sync;

import com.filesync.protocol.SyncProtocol;

import java.io.IOException;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Handles sender/receiver role negotiation and propagation.
 */
public class RoleNegotiationService {

    private final SyncProtocol protocol;
    private final SyncEventBus eventBus;
    private final AtomicBoolean isSender;
    private final AtomicBoolean roleNegotiated;
    private final AtomicLong localPriority;
    private final Random random;
    private final BooleanSupplier connectionAliveSupplier;

    public RoleNegotiationService(SyncProtocol protocol,
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
        refreshPriority();
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

    public void resetForReconnect() {
        roleNegotiated.set(false);
        refreshPriority();
    }

    public void sendRoleNegotiation() {
        if (roleNegotiated.get() || !connectionAliveSupplier.getAsBoolean()) {
            return;
        }
        try {
            protocol.sendRoleNegotiate(localPriority.get());
        } catch (IOException e) {
            eventBus.post(new SyncEvent.ErrorEvent("Failed to send role negotiation: " + e.getMessage()));
        }
    }

    public void handleRoleNegotiate(long remotePriority) {
        if (roleNegotiated.get()) {
            return;
        }

        long myPriority = localPriority.get();
        boolean shouldBeSender = myPriority > remotePriority;

        if (isSender.get() != shouldBeSender) {
            isSender.set(shouldBeSender);
        }

        roleNegotiated.set(true);

        eventBus.post(new SyncEvent.DirectionEvent(isSender.get()));
        eventBus.post(new SyncEvent.LogEvent("Role negotiated: " + (isSender.get() ? "Sender" : "Receiver")));

        try {
            protocol.sendRoleNegotiate(myPriority);
        } catch (IOException e) {
            eventBus.post(new SyncEvent.ErrorEvent("Failed to respond to role negotiation: " + e.getMessage()));
        }
    }

    public void handleDirectionChange(boolean remoteSender) {
        isSender.set(!remoteSender);
        eventBus.post(new SyncEvent.DirectionEvent(isSender.get()));
    }

    public void notifyDirectionChange() {
        try {
            protocol.sendDirectionChange(isSender.get());
        } catch (IOException e) {
            eventBus.post(new SyncEvent.ErrorEvent("Failed to notify direction change: " + e.getMessage()));
        }
    }

    private void refreshPriority() {
        localPriority.set(System.currentTimeMillis() * 1000 + random.nextInt(1000));
    }
}

