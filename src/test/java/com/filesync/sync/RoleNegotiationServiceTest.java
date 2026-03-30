package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.*;

import com.filesync.protocol.SyncProtocol;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RoleNegotiationServiceTest {

    private StubProtocol protocol;
    private RecordingEventBus eventBus;
    private AtomicBoolean isSender;
    private AtomicBoolean roleNegotiated;
    private AtomicBoolean connectionAlive;
    private RoleNegotiationService service;

    @BeforeEach
    void setUp() {
        protocol = new StubProtocol();
        eventBus = new RecordingEventBus();
        isSender = new AtomicBoolean(true);
        roleNegotiated = new AtomicBoolean(false);
        connectionAlive = new AtomicBoolean(true);

        service =
                new RoleNegotiationService(
                        protocol, eventBus, isSender, roleNegotiated, connectionAlive::get);
    }

    @Test
    void isSenderReturnsInitialValue() {
        assertTrue(service.isSender());
    }

    @Test
    void isRoleNegotiatedReturnsFalseInitially() {
        assertFalse(service.isRoleNegotiated());
    }

    @Test
    void setSenderUpdatesStateAndPostsEvent() {
        service.setSender(false);

        assertFalse(service.isSender());
        assertTrue(service.isRoleNegotiated());
        assertTrue(eventBus.hasDirectionEvent(false));
    }

    @Test
    void setSenderToTrueUpdatesStateAndPostsEvent() {
        isSender.set(false);
        roleNegotiated.set(false);

        service.setSender(true);

        assertTrue(service.isSender());
        assertTrue(service.isRoleNegotiated());
        assertTrue(eventBus.hasDirectionEvent(true));
    }

    @Test
    void confirmCurrentRoleIfNeededReturnsFalseWhenAlreadyNegotiated() {
        roleNegotiated.set(true);

        boolean result = service.confirmCurrentRoleIfNeeded(true);

        assertFalse(result);
    }

    @Test
    void confirmCurrentRoleIfNeededSetsRoleWhenNotNegotiated() {
        roleNegotiated.set(false);

        boolean result = service.confirmCurrentRoleIfNeeded(false);

        assertTrue(result);
        assertFalse(service.isSender());
        assertTrue(service.isRoleNegotiated());
    }

    @Test
    void confirmCurrentRoleIfNeededReturnsTrueAndSetsSender() {
        roleNegotiated.set(false);

        boolean result = service.confirmCurrentRoleIfNeeded(true);

        assertTrue(result);
        assertTrue(service.isSender());
        assertTrue(service.isRoleNegotiated());
    }

    @Test
    void resetForReconnectClearsNegotiationAndRefreshesPriority() throws IOException {
        roleNegotiated.set(true);
        isSender.set(false);
        protocol.roleNegotiateSent = false;

        service.resetForReconnect();

        assertFalse(service.isRoleNegotiated());
    }

    @Test
    void sendRoleNegotiationSendsWhenNotNegotiatedAndConnected() throws IOException {
        roleNegotiated.set(false);
        connectionAlive.set(true);

        service.sendRoleNegotiation();

        assertTrue(protocol.roleNegotiateSent);
    }

    @Test
    void sendRoleNegotiationDoesNothingWhenAlreadyNegotiated() throws IOException {
        roleNegotiated.set(true);

        service.sendRoleNegotiation();

        assertFalse(protocol.roleNegotiateSent);
    }

    @Test
    void sendRoleNegotiationDoesNothingWhenNotConnected() throws IOException {
        roleNegotiated.set(false);
        connectionAlive.set(false);

        service.sendRoleNegotiation();

        assertFalse(protocol.roleNegotiateSent);
    }

    @Test
    void handleRoleNegotiateWithSingleArgUsesZeroTieBreaker() throws IOException {
        roleNegotiated.set(false);
        protocol.roleNegotiateSent = false;

        service.handleRoleNegotiate(1000L);

        assertTrue(service.isRoleNegotiated());
        assertTrue(protocol.roleNegotiateSent);
    }

    @Test
    void handleRoleNegotiateIgnoresWhenAlreadyNegotiated() throws IOException {
        roleNegotiated.set(true);
        protocol.roleNegotiateSent = false;

        service.handleRoleNegotiate(1000L);

        assertFalse(protocol.roleNegotiateSent);
    }

    @Test
    void handleRoleNegotiateWithHigherPriorityBecomesSender() throws IOException {
        roleNegotiated.set(false);
        isSender.set(false);
        protocol.roleNegotiateSent = false;

        service.handleRoleNegotiate(0L);

        assertTrue(service.isRoleNegotiated());
        assertTrue(service.isSender());
        assertTrue(protocol.roleNegotiateSent);
        assertTrue(eventBus.hasDirectionEvent(true));
    }

    @Test
    void handleRoleNegotiateWithLowerPriorityBecomesReceiver() throws IOException {
        roleNegotiated.set(false);
        isSender.set(true);
        protocol.roleNegotiateSent = false;

        service.handleRoleNegotiate(Long.MAX_VALUE);

        assertTrue(service.isRoleNegotiated());
        assertFalse(service.isSender());
        assertTrue(protocol.roleNegotiateSent);
        assertTrue(eventBus.hasDirectionEvent(false));
    }

    @Test
    void handleRoleNegotiateWithEqualPriorityUsesTieBreaker() throws IOException {
        roleNegotiated.set(false);
        isSender.set(false);
        protocol.roleNegotiateSent = false;

        long remotePriority = 1000L;
        long remoteTieBreaker = 0L;

        service.handleRoleNegotiate(remotePriority, remoteTieBreaker);

        assertTrue(service.isRoleNegotiated());
        assertTrue(protocol.roleNegotiateSent);
    }

    @Test
    void handleDirectionChangeSetsOppositeRole() {
        isSender.set(true);
        roleNegotiated.set(false);

        service.handleDirectionChange(true);

        assertFalse(service.isSender());
        assertTrue(service.isRoleNegotiated());
        assertTrue(eventBus.hasDirectionEvent(false));
    }

    @Test
    void handleDirectionChangeFromReceiverMakesSender() {
        isSender.set(false);
        roleNegotiated.set(false);

        service.handleDirectionChange(false);

        assertTrue(service.isSender());
        assertTrue(service.isRoleNegotiated());
        assertTrue(eventBus.hasDirectionEvent(true));
    }

    @Test
    void notifyDirectionChangeSendsProtocolMessage() throws IOException {
        isSender.set(true);
        protocol.directionChangeSent = false;

        service.notifyDirectionChange();

        assertTrue(protocol.directionChangeSent);
        assertEquals(true, protocol.lastDirectionChangeValue);
    }

    @Test
    void notifyDirectionChangeSendsFalseWhenReceiver() throws IOException {
        isSender.set(false);
        protocol.directionChangeSent = false;

        service.notifyDirectionChange();

        assertTrue(protocol.directionChangeSent);
        assertEquals(false, protocol.lastDirectionChangeValue);
    }

    @Test
    void handleRoleNegotiationHandlesIOException() throws IOException {
        roleNegotiated.set(false);
        protocol.shouldThrowIOException = true;
        eventBus.clearEvents();

        service.handleRoleNegotiate(1000L);

        assertTrue(service.isRoleNegotiated());
        assertTrue(eventBus.hasErrorEvent());
    }

    @Test
    void notifyDirectionChangeHandlesIOException() throws IOException {
        isSender.set(true);
        protocol.shouldThrowIOException = true;
        eventBus.clearEvents();

        service.notifyDirectionChange();

        assertTrue(eventBus.hasErrorEvent());
    }

    @Test
    void sendRoleNegotiationHandlesIOException() throws IOException {
        roleNegotiated.set(false);
        connectionAlive.set(true);
        protocol.shouldThrowIOException = true;
        eventBus.clearEvents();

        service.sendRoleNegotiation();

        assertTrue(eventBus.hasErrorEvent());
    }

    private static class StubProtocol extends SyncProtocol {
        boolean roleNegotiateSent = false;
        boolean directionChangeSent = false;
        boolean lastDirectionChangeValue = false;
        boolean shouldThrowIOException = false;
        private long sentPriority;
        private long sentTieBreaker;

        StubProtocol() {
            super(new StubSerialPortManager());
        }

        @Override
        public void sendRoleNegotiate(long priority, long tieBreaker) throws IOException {
            if (shouldThrowIOException) {
                throw new IOException("Simulated IO error");
            }
            roleNegotiateSent = true;
            sentPriority = priority;
            sentTieBreaker = tieBreaker;
        }

        @Override
        public void sendDirectionChange(boolean isSender) throws IOException {
            if (shouldThrowIOException) {
                throw new IOException("Simulated IO error");
            }
            directionChangeSent = true;
            lastDirectionChangeValue = isSender;
        }

        long getSentPriority() {
            return sentPriority;
        }

        long getSentTieBreaker() {
            return sentTieBreaker;
        }
    }

    private static class StubSerialPortManager extends com.filesync.serial.SerialPortManager {
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
            return new ArrayList<>(events);
        }

        void clearEvents() {
            events.clear();
        }

        boolean hasDirectionEvent(boolean expectedSender) {
            return events.stream()
                    .anyMatch(
                            e ->
                                    e instanceof SyncEvent.DirectionEvent
                                            && ((SyncEvent.DirectionEvent) e).isSender()
                                                    == expectedSender);
        }

        boolean hasErrorEvent() {
            return events.stream().anyMatch(e -> e instanceof SyncEvent.ErrorEvent);
        }
    }
}
