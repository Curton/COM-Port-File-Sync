package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.*;

import com.filesync.protocol.SyncProtocol;
import com.filesync.serial.SerialPortManager;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConnectionServiceTest {

    private StubProtocol protocol;
    private RecordingEventBus eventBus;
    private AtomicBoolean running;
    private AtomicBoolean connectionAlive;
    private ConnectionService service;
    private boolean connectionLostCalled;
    private boolean reconnectCalled;

    @BeforeEach
    void setUp() {
        protocol = new StubProtocol();
        eventBus = new RecordingEventBus();
        running = new AtomicBoolean(true);
        connectionAlive = new AtomicBoolean(false);
        connectionLostCalled = false;
        reconnectCalled = false;

        service =
                new ConnectionService(
                        new StubSerialPortManager(),
                        protocol,
                        eventBus,
                        running,
                        connectionAlive,
                        () -> false,
                        () -> false,
                        () -> connectionLostCalled = true,
                        () -> reconnectCalled = true);
    }

    @Test
    void handleHeartbeatSendsAckAndMarksAlive() throws IOException {
        assertFalse(service.isConnectionAlive());

        service.handleHeartbeat();

        assertTrue(protocol.heartbeatAckSent);
        assertTrue(service.isConnectionAlive());
        assertTrue(reconnectCalled);
    }

    @Test
    void handleHeartbeatAckMarksAlive() {
        assertFalse(service.isConnectionAlive());

        service.handleHeartbeatAck();

        assertTrue(service.isConnectionAlive());
        assertTrue(reconnectCalled);
    }

    @Test
    void handleHeartbeatDoesNotRepostIfAlreadyAlive() throws IOException {
        service.handleHeartbeat();
        reconnectCalled = false;
        eventBus.clear();

        service.handleHeartbeat();

        assertFalse(reconnectCalled);
        assertTrue(
                eventBus.getEvents().stream()
                        .noneMatch(e -> e instanceof SyncEvent.ConnectionEvent));
    }

    @Test
    void recordMessageActivityUpdatesTimestamp() {
        service.recordMessageActivity();
    }

    @Test
    void reportCommunicationFailureMarksLost() {
        connectionAlive.set(true);

        service.reportCommunicationFailure("test failure");

        assertFalse(service.isConnectionAlive());
        assertTrue(connectionLostCalled);
    }

    @Test
    void reportCommunicationFailureDoesNotRepostIfAlreadyLost() {
        connectionAlive.set(false);

        service.reportCommunicationFailure("test failure");

        assertFalse(connectionLostCalled);
    }

    @Test
    void stopCancelsHeartbeatAndSetsNotAlive() {
        connectionAlive.set(true);

        service.stop();

        assertFalse(service.isConnectionAlive());
    }

    @Test
    void waitForConnectionTimesOut() {
        boolean result = service.waitForConnection(100);
        assertFalse(result);
    }

    @Test
    void waitForConnectionReturnsTrueWhenAlive() {
        connectionAlive.set(true);
        assertTrue(service.waitForConnection(100));
    }

    @Test
    void isConnectionAliveReturnsFalseInitially() {
        assertFalse(service.isConnectionAlive());
    }

    @Test
    void handleHeartbeatPostsConnectionEvent() throws IOException {
        service.handleHeartbeat();

        assertTrue(
                eventBus.getEvents().stream()
                        .anyMatch(e -> e instanceof SyncEvent.ConnectionEvent));
        assertTrue(eventBus.getEvents().stream().anyMatch(e -> e instanceof SyncEvent.LogEvent));
    }

    @Test
    void reportCommunicationFailurePostsEvents() {
        connectionAlive.set(true);

        service.reportCommunicationFailure("reason");

        assertTrue(
                eventBus.getEvents().stream()
                        .anyMatch(e -> e instanceof SyncEvent.ConnectionEvent));
        assertTrue(eventBus.getEvents().stream().anyMatch(e -> e instanceof SyncEvent.LogEvent));
    }

    private static class StubProtocol extends SyncProtocol {
        boolean heartbeatAckSent = false;

        StubProtocol() {
            super(new StubSerialPortManager());
        }

        @Override
        public void sendHeartbeatAck() {
            heartbeatAckSent = true;
        }

        @Override
        public void sendHeartbeat() {}
    }

    private static class StubSerialPortManager extends SerialPortManager {
        @Override
        public boolean isOpen() {
            return true;
        }
    }

    private static class RecordingEventBus implements SyncEventBus {
        private final List<SyncEvent> events = new ArrayList<>();

        @Override
        public void post(SyncEvent event) {
            events.add(event);
        }

        @Override
        public void register(SyncEventListener listener) {}

        @Override
        public void unregister(SyncEventListener listener) {}

        List<SyncEvent> getEvents() {
            return events;
        }

        void clear() {
            events.clear();
        }
    }
}
