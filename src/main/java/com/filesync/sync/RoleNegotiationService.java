package com.filesync.sync;

import com.filesync.protocol.SyncProtocol;
import java.io.IOException;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** Handles sender/receiver role negotiation and propagation. */
public class RoleNegotiationService {

    /** Minimum spacing between role-negotiation retries while a session stays un-negotiated. */
    private static final long NEGOTIATION_RETRY_INTERVAL_MS = 3000;

    private final SyncProtocol protocol;
    private final SyncEventBus eventBus;
    private final AtomicBoolean isSender;
    private final AtomicBoolean roleNegotiated;
    private final AtomicLong localPriority;
    private final AtomicLong localTieBreaker;
    private final AtomicLong lastNegotiationSent = new AtomicLong(0);
    private final Random random;
    private final BooleanSupplier connectionAliveSupplier;

    /** Runs on whatever thread completes negotiation; used to flush queued shared text. */
    private volatile Runnable onNegotiated;

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

    /** Registers a callback invoked once each time this side completes role negotiation. */
    public void setOnNegotiated(Runnable onNegotiated) {
        this.onNegotiated = onNegotiated;
    }

    /** Test hook: shifts the retry rate-limit stamp so tests need not sleep for the interval. */
    void forceLastNegotiationSentForTest(long timestampMillis) {
        lastNegotiationSent.set(timestampMillis);
    }

    public boolean isSender() {
        return isSender.get();
    }

    public void setSender(boolean sender) {
        isSender.set(sender);
        roleNegotiated.set(true);
        eventBus.post(new SyncEvent.DirectionEvent(sender));
        notifyNegotiated();
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
        notifyNegotiated();
        return true;
    }

    public void resetForReconnect() {
        roleNegotiated.set(false);
        refreshPriority();
        refreshTieBreaker();
    }

    /**
     * Sends a role-negotiation request if this side is connected but not yet negotiated. The
     * request is fire-and-forget over an unreliable serial line, so it is safe (and necessary) to
     * re-send it: a peer that is already settled ignores duplicates and answers with its current
     * role instead.
     */
    public synchronized void sendRoleNegotiation() {
        if (roleNegotiated.get() || !connectionAliveSupplier.getAsBoolean()) {
            return;
        }
        // Record the attempt before sending so a failed send cannot cause a tight retry loop.
        lastNegotiationSent.set(System.currentTimeMillis());
        try {
            protocol.sendRoleNegotiate(localPriority.get(), localTieBreaker.get());
        } catch (IOException e) {
            eventBus.post(
                    new SyncEvent.ErrorEvent("Failed to send role negotiation: " + e.getMessage()));
        }
    }

    /**
     * Periodic retry hook (called from the heartbeat tick). Re-sends the negotiation request at a
     * fixed interval while the session stays connected but un-negotiated, so a lost request frame
     * or a peer that was busy during the one-shot send cannot stall negotiation forever.
     */
    public void retryNegotiationIfNeeded() {
        if (roleNegotiated.get() || !connectionAliveSupplier.getAsBoolean()) {
            return;
        }
        long now = System.currentTimeMillis();
        long last = lastNegotiationSent.get();
        if (now - last < NEGOTIATION_RETRY_INTERVAL_MS) {
            return;
        }
        if (!lastNegotiationSent.compareAndSet(last, now)) {
            return;
        }
        sendRoleNegotiation();
    }

    public void handleRoleNegotiate(long remotePriority) {
        handleRoleNegotiate(remotePriority, 0L);
    }

    public synchronized void handleRoleNegotiate(long remotePriority, long remoteTieBreaker) {
        if (roleNegotiated.get()) {
            // This side is settled, but the peer is asking again: its own request (or our reply to
            // it) was lost, or it reset while we stayed up. Re-running the priority comparison
            // could contradict the role both sides already agreed on, so propagate the settled
            // role directly instead of ignoring the peer and leaving it un-negotiated forever.
            replyWithCurrentRole();
            return;
        }

        boolean shouldBeSender = shouldBeSenderForNegotiation(remotePriority, remoteTieBreaker);

        if (isSender.get() != shouldBeSender) {
            isSender.set(shouldBeSender);
        }

        roleNegotiated.set(true);
        notifyNegotiated();

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
        boolean newIsSender = !remoteSender;
        boolean wasNegotiated = roleNegotiated.get();
        boolean roleChanged = isSender.get() != newIsSender;
        isSender.set(newIsSender);
        roleNegotiated.set(true);
        notifyNegotiated();
        // Suppress duplicate announcements: a settled peer echoes our own role back as the reply
        // to a retried negotiation request, and re-posting it would only spam the log.
        if (roleChanged || !wasNegotiated) {
            eventBus.post(new SyncEvent.DirectionEvent(newIsSender));
        }
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

    /**
     * Tells the peer this side's settled role via DIRECTION_CHANGE, so a peer that reset (or whose
     * negotiation frames were lost) can adopt the complementary role and complete its own
     * negotiation.
     */
    private void replyWithCurrentRole() {
        try {
            protocol.sendDirectionChange(isSender.get());
        } catch (IOException e) {
            eventBus.post(
                    new SyncEvent.ErrorEvent(
                            "Failed to reply to role negotiation: " + e.getMessage()));
        }
    }

    private void notifyNegotiated() {
        Runnable callback = onNegotiated;
        if (callback == null) {
            return;
        }
        try {
            callback.run();
        } catch (RuntimeException ignored) {
            // Negotiation completion must stay robust even if the callback fails.
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
