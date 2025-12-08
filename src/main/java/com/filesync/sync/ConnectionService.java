package com.filesync.sync;

import com.filesync.protocol.SyncProtocol;
import com.filesync.serial.SerialPortManager;

import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Manages heartbeats and connection liveness using a shared executor.
 */
public class ConnectionService {

    private static final long HEARTBEAT_INTERVAL_MS = 5000L;
    private static final long HEARTBEAT_TIMEOUT_MS = 15000L;
    private static final long HEARTBEAT_CHECK_INTERVAL_MS = 1000L;
    private static final long INITIAL_HEARTBEAT_INTERVAL_MS = 2000L;

    private final SerialPortManager serialPort;
    private final SyncProtocol protocol;
    private final SyncEventBus eventBus;
    private final AtomicBoolean running;
    private final AtomicBoolean connectionAlive;
    private final AtomicLong lastHeartbeatReceived;
    private final AtomicLong lastHeartbeatSent;
    private final BooleanSupplier syncingSupplier;
    private final BooleanSupplier transferBusySupplier;
    private final Runnable onReconnect;

    private ScheduledExecutorService executor;
    private ScheduledFuture<?> heartbeatFuture;

    public ConnectionService(SerialPortManager serialPort,
                             SyncProtocol protocol,
                             SyncEventBus eventBus,
                             AtomicBoolean running,
                             AtomicBoolean connectionAlive,
                             BooleanSupplier syncingSupplier,
                             BooleanSupplier transferBusySupplier,
                             Runnable onReconnect) {
        this.serialPort = serialPort;
        this.protocol = protocol;
        this.eventBus = eventBus;
        this.running = running;
        this.connectionAlive = connectionAlive;
        this.lastHeartbeatReceived = new AtomicLong(0);
        this.lastHeartbeatSent = new AtomicLong(0);
        this.syncingSupplier = syncingSupplier;
        this.transferBusySupplier = transferBusySupplier;
        this.onReconnect = onReconnect;
    }

    public void setExecutor(ScheduledExecutorService executor) {
        this.executor = executor;
    }

    public void start() {
        cancelHeartbeat();
        resetTimestamps();
        if (executor != null) {
            heartbeatFuture = executor.scheduleAtFixedRate(
                    this::heartbeatTick,
                    0,
                    HEARTBEAT_CHECK_INTERVAL_MS,
                    TimeUnit.MILLISECONDS);
        }
    }

    public void stop() {
        cancelHeartbeat();
        connectionAlive.set(false);
    }

    public boolean waitForConnection(long timeoutMs) {
        long startTime = System.currentTimeMillis();
        sendHeartbeatQuietly();
        while (running.get() && !connectionAlive.get()) {
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                return false;
            }
            try {
                Thread.sleep(500);
                long now = System.currentTimeMillis();
                if (now - lastHeartbeatSent.get() >= INITIAL_HEARTBEAT_INTERVAL_MS) {
                    sendHeartbeatQuietly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return connectionAlive.get();
    }

    public void handleHeartbeat() throws IOException {
        protocol.sendHeartbeatAck();
        markAliveIfNeeded("Connection restored");
    }

    public void handleHeartbeatAck() {
        markAliveIfNeeded("Connection restored");
    }

    public void recordMessageActivity() {
        lastHeartbeatReceived.set(System.currentTimeMillis());
    }

    public boolean isConnectionAlive() {
        return connectionAlive.get();
    }

    private void heartbeatTick() {
        if (!running.get()) {
            return;
        }
        if (!serialPort.isOpen()) {
            return;
        }
        if (transferBusySupplier.getAsBoolean()) {
            return;
        }

        long now = System.currentTimeMillis();

        if (connectionAlive.get()
                && lastHeartbeatReceived.get() > 0
                && !syncingSupplier.getAsBoolean()
                && (now - lastHeartbeatReceived.get()) > HEARTBEAT_TIMEOUT_MS) {
            markLost("Connection lost - no heartbeat response");
        }

        if (!syncingSupplier.getAsBoolean()
                && (now - lastHeartbeatSent.get()) >= HEARTBEAT_INTERVAL_MS) {
            try {
                protocol.sendHeartbeat();
                lastHeartbeatSent.set(now);
            } catch (IOException e) {
                markLost("Connection lost - heartbeat send failed: " + e.getMessage());
            }
        }
    }

    private void markAliveIfNeeded(String reason) {
        lastHeartbeatReceived.set(System.currentTimeMillis());
        if (!connectionAlive.getAndSet(true)) {
            if (onReconnect != null) {
                onReconnect.run();
            }
            eventBus.post(new SyncEvent.ConnectionEvent(true));
            if (reason != null && !reason.isEmpty()) {
                eventBus.post(new SyncEvent.LogEvent(reason));
            }
        }
    }

    private void markLost(String reason) {
        if (connectionAlive.getAndSet(false)) {
            eventBus.post(new SyncEvent.ConnectionEvent(false));
            if (reason != null && !reason.isEmpty()) {
                eventBus.post(new SyncEvent.LogEvent(reason));
            }
        }
    }

    private void sendHeartbeatQuietly() {
        try {
            protocol.sendHeartbeat();
            lastHeartbeatSent.set(System.currentTimeMillis());
        } catch (IOException e) {
            // Ignore, will retry on next iteration
        }
    }

    private void cancelHeartbeat() {
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(true);
            heartbeatFuture = null;
        }
    }

    private void resetTimestamps() {
        lastHeartbeatReceived.set(0);
        lastHeartbeatSent.set(0);
    }
}

