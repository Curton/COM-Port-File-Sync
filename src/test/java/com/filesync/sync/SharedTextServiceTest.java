package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.filesync.protocol.SyncProtocol;
import com.filesync.protocol.SyncProtocol.PendingText;
import com.filesync.protocol.TransferCancelledException;
import com.filesync.serial.SerialPortManager;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class SharedTextServiceTest {

    @Test
    void queueSharedTextSendsLatestPendingValueAfterBusyTransferCompletes() {
        TestSharedTextProtocol protocol = new TestSharedTextProtocol();
        SimpleSyncEventBus eventBus = new SimpleSyncEventBus();
        List<String> errors = new ArrayList<>();
        eventBus.register(
                event -> {
                    if (event instanceof SyncEvent.ErrorEvent errorEvent) {
                        errors.add(errorEvent.getMessage());
                    }
                });

        SharedTextService service =
                new SharedTextService(
                        protocol,
                        eventBus,
                        () -> true,
                        () -> true,
                        () -> false,
                        protocol::isSending,
                        () -> true);

        protocol.setBeforeSendHook(
                text -> {
                    if ("first".equals(text)) {
                        service.queueSharedText("second");
                    }
                });

        service.queueSharedText("first");

        assertEquals(List.of("first", "second"), protocol.getSentTexts());
        assertTrue(errors.isEmpty(), "No shared text send errors expected");
    }

    @Test
    void handleIncomingSharedTextDataUsesExpectedLength() {
        TestSharedTextProtocol protocol = new TestSharedTextProtocol();
        protocol.setReceivedSharedText("with explicit length");

        SimpleSyncEventBus eventBus = new SimpleSyncEventBus();
        AtomicReference<String> receivedText = new AtomicReference<>();
        List<SyncEvent> events = new ArrayList<>();
        eventBus.register(
                event -> {
                    if (event instanceof SyncEvent.SharedTextReceivedEvent sharedTextEvent) {
                        receivedText.set(sharedTextEvent.getText());
                    }
                });
        eventBus.register(events::add);

        SharedTextService service =
                new SharedTextService(
                        protocol,
                        eventBus,
                        () -> true,
                        () -> true,
                        () -> false,
                        () -> false,
                        () -> true);

        service.handleIncomingSharedTextData(777L, true, 17);

        assertEquals("with explicit length", receivedText.get());
        assertEquals(17, protocol.getReceivedSharedTextLength());
        assertTrue(
                protocol.wasReceiveSharedTextDataWithLengthCalled(),
                "Expected length-aware receive path");
        assertTrue(
                events.stream().anyMatch(e -> e instanceof SyncEvent.SyncControlRefreshEvent),
                "A SyncControlRefreshEvent must follow the XMODEM shared-text receive, or the"
                        + " sync controls stay disabled after the transfer");
    }

    @Test
    void handleIncomingSharedTextReportsMalformedPayload() {
        TestSharedTextProtocol protocol = new TestSharedTextProtocol();
        protocol.setDecodeFailure(new IllegalArgumentException("invalid payload"));

        SimpleSyncEventBus eventBus = new SimpleSyncEventBus();
        List<String> errors = new ArrayList<>();
        eventBus.register(
                event -> {
                    if (event instanceof SyncEvent.ErrorEvent errorEvent) {
                        errors.add(errorEvent.getMessage());
                    }
                });

        SharedTextService service =
                new SharedTextService(
                        protocol,
                        eventBus,
                        () -> true,
                        () -> true,
                        () -> false,
                        () -> false,
                        () -> true);

        service.handleIncomingSharedText("bad-payload");

        assertTrue(
                errors.stream()
                        .anyMatch(message -> message.contains("Failed to decode shared text")),
                "Expected malformed shared text to be reported as an error");
    }

    @Test
    void handleIncomingSharedTextIgnoresOlderTimestamps() {
        TestSharedTextProtocol protocol = new TestSharedTextProtocol();
        List<String> receivedText = new ArrayList<>();

        SimpleSyncEventBus eventBus = new SimpleSyncEventBus();
        eventBus.register(
                event -> {
                    if (event instanceof SyncEvent.SharedTextReceivedEvent sharedTextEvent) {
                        receivedText.add(sharedTextEvent.getText());
                    }
                });

        SharedTextService service =
                new SharedTextService(
                        protocol,
                        eventBus,
                        () -> true,
                        () -> true,
                        () -> false,
                        () -> false,
                        () -> true);

        service.handleIncomingSharedText(200L, "newer");
        service.handleIncomingSharedText(100L, "older");

        assertEquals(List.of("newer"), receivedText);
    }

    @Test
    void clearPendingSharedTextClearsAllPendingState() {
        TestSharedTextProtocol protocol = new TestSharedTextProtocol();
        SimpleSyncEventBus eventBus = new SimpleSyncEventBus();

        SharedTextService service =
                new SharedTextService(
                        protocol,
                        eventBus,
                        () -> true,
                        () -> true,
                        () -> false,
                        () -> false,
                        () -> true);

        // Queue some text first (this will send immediately due to all true suppliers)
        service.queueSharedText("some text");
        assertEquals(1, protocol.getSentTexts().size(), "Text should be sent during queue");

        // Clear pending state
        service.clearPendingSharedText();

        // After clearing, resend should do nothing since latestSharedText is also cleared
        // But we need to stop the service to prevent sending
        service.clearPendingSharedText(); // Call again to ensure cleared state
        protocol.getSentTexts().clear(); // Clear sent texts

        // Now send new text - it should work since we cleared before
        service.queueSharedText("new text");
        assertEquals(1, protocol.getSentTexts().size(), "New text should be sent after clear");
        assertEquals("new text", protocol.getSentTexts().get(0));
    }

    @Test
    void handleIncomingSharedTextDataReportsIOExceptionAsError() {
        TestSharedTextProtocol protocol = new TestSharedTextProtocol();
        protocol.setReceiveFailure(new IOException("connection lost"));

        SimpleSyncEventBus eventBus = new SimpleSyncEventBus();
        List<String> errors = new ArrayList<>();
        eventBus.register(
                event -> {
                    if (event instanceof SyncEvent.ErrorEvent errorEvent) {
                        errors.add(errorEvent.getMessage());
                    }
                });

        SharedTextService service =
                new SharedTextService(
                        protocol,
                        eventBus,
                        () -> true,
                        () -> true,
                        () -> false,
                        () -> false,
                        () -> true);

        service.handleIncomingSharedTextData(123L, false, 0);

        assertTrue(
                errors.stream()
                        .anyMatch(message -> message.contains("Failed to receive shared text")),
                "Expected receive error to be posted");
    }

    @Test
    void handleIncomingSharedTextDataPostsSyncControlRefreshWhenPeerCancels() {
        TestSharedTextProtocol protocol = new TestSharedTextProtocol();
        protocol.setReceiveFailure(
                new TransferCancelledException("Shared text transfer cancelled by sender"));

        SimpleSyncEventBus eventBus = new SimpleSyncEventBus();
        List<SyncEvent> events = new ArrayList<>();
        eventBus.register(events::add);

        SharedTextService service =
                new SharedTextService(
                        protocol,
                        eventBus,
                        () -> true,
                        () -> true,
                        () -> false,
                        () -> false,
                        () -> true);

        service.handleIncomingSharedTextData(123L, false, 0);

        assertTrue(
                events.stream().anyMatch(e -> e instanceof SyncEvent.SyncControlRefreshEvent),
                "A peer-cancelled XMODEM shared-text receive must still post a"
                        + " SyncControlRefreshEvent, or the sync controls stay disabled");
    }

    @Test
    void queueSharedTextPostsSyncControlRefreshAfterSend() {
        TestSharedTextProtocol protocol = new TestSharedTextProtocol();
        SimpleSyncEventBus eventBus = new SimpleSyncEventBus();
        List<SyncEvent> events = new ArrayList<>();
        List<String> logs = new ArrayList<>();
        eventBus.register(events::add);
        eventBus.register(
                event -> {
                    if (event instanceof SyncEvent.LogEvent logEvent) {
                        logs.add(logEvent.getMessage());
                    }
                });

        SharedTextService service =
                new SharedTextService(
                        protocol,
                        eventBus,
                        () -> true,
                        () -> true,
                        () -> false,
                        () -> false,
                        () -> true);

        service.queueSharedText("hello");

        assertEquals(List.of("hello"), protocol.getSentTexts());
        assertTrue(
                logs.contains("Shared text sent"),
                "Successful delivery should be logged, got: " + logs);
        assertTrue(
                events.stream().anyMatch(e -> e instanceof SyncEvent.SyncControlRefreshEvent),
                "A SyncControlRefreshEvent must follow the shared-text send, or the sync"
                        + " controls stay disabled after an XMODEM-sized payload");
    }

    @Test
    void flushIfIdleDoesNotSendWhenConnectionIsNotReady() {
        TestSharedTextProtocol notRunningProtocol = new TestSharedTextProtocol();
        SharedTextService notRunning =
                new SharedTextService(
                        notRunningProtocol,
                        new SimpleSyncEventBus(),
                        () -> false, // not running
                        () -> true,
                        () -> false,
                        () -> false,
                        () -> true);
        TestSharedTextProtocol notConnectedProtocol = new TestSharedTextProtocol();
        SharedTextService notConnected =
                new SharedTextService(
                        notConnectedProtocol,
                        new SimpleSyncEventBus(),
                        () -> true,
                        () -> false, // not connected
                        () -> false,
                        () -> false,
                        () -> true);
        TestSharedTextProtocol notNegotiatedProtocol = new TestSharedTextProtocol();
        SharedTextService notNegotiated =
                new SharedTextService(
                        notNegotiatedProtocol,
                        new SimpleSyncEventBus(),
                        () -> true,
                        () -> true,
                        () -> false,
                        () -> false,
                        () -> false); // role not negotiated

        notRunning.queueSharedText("should not send");
        notConnected.queueSharedText("should not send");
        notNegotiated.queueSharedText("should not send");

        assertTrue(
                notRunningProtocol.getSentTexts().isEmpty(),
                "Nothing should be sent when not running");
        assertTrue(
                notConnectedProtocol.getSentTexts().isEmpty(),
                "Nothing should be sent when not connected");
        assertTrue(
                notNegotiatedProtocol.getSentTexts().isEmpty(),
                "Nothing should be sent when role not negotiated");
    }

    @Test
    void flushIfIdleLogsDeferralReasonWhenHeldBack() {
        // Only which BooleanSupplier flips varies: role not negotiated, syncing, or transfer busy.
        TestSharedTextProtocol notNegotiatedProtocol = new TestSharedTextProtocol();
        SimpleSyncEventBus notNegotiatedBus = new SimpleSyncEventBus();
        List<String> notNegotiatedLogs = new ArrayList<>();
        notNegotiatedBus.register(
                event -> {
                    if (event instanceof SyncEvent.LogEvent logEvent) {
                        notNegotiatedLogs.add(logEvent.getMessage());
                    }
                });
        SharedTextService roleNotNegotiated =
                new SharedTextService(
                        notNegotiatedProtocol,
                        notNegotiatedBus,
                        () -> true,
                        () -> true,
                        () -> false,
                        () -> false,
                        () -> false); // role not negotiated

        TestSharedTextProtocol syncingProtocol = new TestSharedTextProtocol();
        SimpleSyncEventBus syncingBus = new SimpleSyncEventBus();
        List<String> syncingLogs = new ArrayList<>();
        syncingBus.register(
                event -> {
                    if (event instanceof SyncEvent.LogEvent logEvent) {
                        syncingLogs.add(logEvent.getMessage());
                    }
                });
        SharedTextService syncing =
                new SharedTextService(
                        syncingProtocol,
                        syncingBus,
                        () -> true,
                        () -> true,
                        () -> true, // syncing
                        () -> false,
                        () -> true);

        TestSharedTextProtocol transferBusyProtocol = new TestSharedTextProtocol();
        SimpleSyncEventBus transferBusyBus = new SimpleSyncEventBus();
        List<String> transferBusyLogs = new ArrayList<>();
        transferBusyBus.register(
                event -> {
                    if (event instanceof SyncEvent.LogEvent logEvent) {
                        transferBusyLogs.add(logEvent.getMessage());
                    }
                });
        SharedTextService transferBusy =
                new SharedTextService(
                        transferBusyProtocol,
                        transferBusyBus,
                        () -> true,
                        () -> true,
                        () -> false,
                        () -> true, // transfer busy
                        () -> true);

        roleNotNegotiated.queueSharedText("held back");
        syncing.queueSharedText("held back");
        transferBusy.queueSharedText("held back");

        assertTrue(notNegotiatedProtocol.getSentTexts().isEmpty());
        assertTrue(
                notNegotiatedLogs.stream()
                        .anyMatch(message -> message.contains("Shared text queued")),
                "Deferral should be logged with a reason, got: " + notNegotiatedLogs);
        assertTrue(syncingProtocol.getSentTexts().isEmpty());
        assertTrue(
                syncingLogs.stream().anyMatch(message -> message.contains("Shared text queued")),
                "Deferral should be logged with a reason, got: " + syncingLogs);
        assertTrue(transferBusyProtocol.getSentTexts().isEmpty());
        assertTrue(
                transferBusyLogs.stream()
                        .anyMatch(message -> message.contains("Shared text queued")),
                "Deferral should be logged with a reason, got: " + transferBusyLogs);
    }

    @Test
    void handleIncomingSharedTextLogsReceived() {
        TestSharedTextProtocol protocol = new TestSharedTextProtocol();
        SimpleSyncEventBus eventBus = new SimpleSyncEventBus();
        List<String> logs = new ArrayList<>();
        eventBus.register(
                event -> {
                    if (event instanceof SyncEvent.LogEvent logEvent) {
                        logs.add(logEvent.getMessage());
                    }
                });

        SharedTextService service =
                new SharedTextService(
                        protocol,
                        eventBus,
                        () -> true,
                        () -> true,
                        () -> false,
                        () -> false,
                        () -> true);

        service.handleIncomingSharedText(100L, "some text");

        assertTrue(
                logs.contains("Shared text received"),
                "Accepted text should be logged, got: " + logs);
    }

    @Test
    void clearPendingSharedTextResetsAcceptedTimestamp() {
        TestSharedTextProtocol protocol = new TestSharedTextProtocol();
        SimpleSyncEventBus eventBus = new SimpleSyncEventBus();
        List<String> receivedText = new ArrayList<>();
        eventBus.register(
                event -> {
                    if (event instanceof SyncEvent.SharedTextReceivedEvent sharedTextEvent) {
                        receivedText.add(sharedTextEvent.getText());
                    }
                });

        SharedTextService service =
                new SharedTextService(
                        protocol,
                        eventBus,
                        () -> true,
                        () -> true,
                        () -> false,
                        () -> false,
                        () -> true);

        service.handleIncomingSharedText(300L, "newer");
        // Simulate session teardown (stopListening) between connections.
        service.clearPendingSharedText();
        // After reconnect, a payload with an older timestamp (e.g. clock skew) must not be
        // silently rejected anymore.
        service.handleIncomingSharedText(100L, "older but fresh");

        assertEquals(List.of("newer", "older but fresh"), receivedText);
    }

    // ========== between-blocks interleave source ==========

    @Test
    void peekReturnsPendingTextWhenConnectionIsReady() {
        SharedTextService service =
                new SharedTextService(
                        new TestSharedTextProtocol(),
                        new SimpleSyncEventBus(),
                        () -> true,
                        () -> true,
                        () -> false,
                        () -> true, // transfer busy: queueSharedText must keep the text pending
                        () -> true);

        service.queueSharedText("interleaved");

        PendingText pending = service.peek();
        assertNotNull(pending, "a queued text must be visible to the interleave hook");
        assertEquals("interleaved", pending.text());
        assertTrue(pending.timestamp() > 0);
    }

    @Test
    void peekReturnsNullWhenConnectionIsNotReady() {
        SharedTextService notRunning =
                new SharedTextService(
                        new TestSharedTextProtocol(),
                        new SimpleSyncEventBus(),
                        () -> false,
                        () -> true,
                        () -> false,
                        () -> true,
                        () -> true);
        SharedTextService notConnected =
                new SharedTextService(
                        new TestSharedTextProtocol(),
                        new SimpleSyncEventBus(),
                        () -> true,
                        () -> false,
                        () -> false,
                        () -> true,
                        () -> true);
        SharedTextService notNegotiated =
                new SharedTextService(
                        new TestSharedTextProtocol(),
                        new SimpleSyncEventBus(),
                        () -> true,
                        () -> true,
                        () -> false,
                        () -> true,
                        () -> false);

        notRunning.queueSharedText("text");
        notConnected.queueSharedText("text");
        notNegotiated.queueSharedText("text");

        assertNull(notRunning.peek(), "no interleave while the service is not running");
        assertNull(notConnected.peek(), "no interleave while disconnected");
        assertNull(notNegotiated.peek(), "no interleave before role negotiation");
    }

    @Test
    void clearIfCurrentClearsExactlyThePeekedTextAndLogs() {
        SimpleSyncEventBus eventBus = new SimpleSyncEventBus();
        List<String> logs = new ArrayList<>();
        eventBus.register(
                event -> {
                    if (event instanceof SyncEvent.LogEvent logEvent) {
                        logs.add(logEvent.getMessage());
                    }
                });
        SharedTextService service =
                new SharedTextService(
                        new TestSharedTextProtocol(),
                        eventBus,
                        () -> true,
                        () -> true,
                        () -> false,
                        () -> true, // transfer busy: the text must stay pending until cleared
                        () -> true);

        service.queueSharedText("in flight");
        PendingText pending = service.peek();

        assertTrue(service.clearIfCurrent(pending), "the peeked text must be clearable");
        assertNull(service.peek(), "the pending slot is empty after the clear");
        assertTrue(
                logs.contains("Shared text sent (interleaved during transfer)"),
                "the interleave send should be logged, got: " + logs);

        assertFalse(
                service.clearIfCurrent(pending), "clearing a stale reference must report false");
    }

    @Test
    void clearIfCurrentDoesNotDropAQueuedNewerText() {
        SharedTextService service =
                new SharedTextService(
                        new TestSharedTextProtocol(),
                        new SimpleSyncEventBus(),
                        () -> true,
                        () -> true,
                        () -> false,
                        () -> true, // transfer busy: nothing flushes outside the hook
                        () -> true);

        service.queueSharedText("older");
        PendingText inFlight = service.peek();
        service.queueSharedText("newer"); // replaced the slot while "older" was on the wire

        assertFalse(
                service.clearIfCurrent(inFlight),
                "the CAS must not clear a text that was replaced before the clear");
        PendingText remaining = service.peek();
        assertNotNull(remaining, "the newer text must stay queued for the next flush point");
        assertEquals("newer", remaining.text());
    }

    @Test
    void clearIfCurrentRejectsAForeignPendingText() {
        SharedTextService service =
                new SharedTextService(
                        new TestSharedTextProtocol(),
                        new SimpleSyncEventBus(),
                        () -> true,
                        () -> true,
                        () -> false,
                        () -> true, // transfer busy: nothing flushes outside the hook
                        () -> true);

        service.queueSharedText("kept");

        PendingText foreign =
                new PendingText() {
                    @Override
                    public long timestamp() {
                        return 1L;
                    }

                    @Override
                    public String text() {
                        return "kept";
                    }
                };

        assertFalse(
                service.clearIfCurrent(foreign),
                "a foreign PendingText can never be the instance this service handed out");
        assertNotNull(service.peek(), "the pending text must survive the rejected clear");
    }

    private static final class TestSharedTextProtocol extends SyncProtocol {
        private final AtomicBoolean sending = new AtomicBoolean(false);
        private final List<String> sentTexts = new ArrayList<>();
        private Consumer<String> beforeSendHook;
        private String receivedSharedText = "";
        private IllegalArgumentException decodeFailure;
        private IOException receiveFailure;
        private boolean receiveSharedTextDataWithLengthCalled;
        private int receivedSharedTextLength = -1;

        private TestSharedTextProtocol() {
            super(new SerialPortManager());
        }

        @Override
        public void sendSharedText(long timestamp, String text) throws IOException {
            sending.set(true);
            try {
                sentTexts.add(text);
                if (beforeSendHook != null) {
                    beforeSendHook.accept(text);
                }
            } finally {
                sending.set(false);
            }
        }

        @Override
        public void sendSharedText(String text) throws IOException {
            sendSharedText(System.currentTimeMillis(), text);
        }

        @Override
        public String receiveSharedTextData(boolean wasCompressed, int expectedDataLength)
                throws IOException {
            if (receiveFailure != null) {
                throw receiveFailure;
            }
            receiveSharedTextDataWithLengthCalled = true;
            receivedSharedTextLength = expectedDataLength;
            return receivedSharedText;
        }

        @Override
        public String decodeSharedText(String encodedPayload) {
            if (decodeFailure != null) {
                throw decodeFailure;
            }
            return encodedPayload;
        }

        private boolean isSending() {
            return sending.get();
        }

        private List<String> getSentTexts() {
            return sentTexts;
        }

        private void setBeforeSendHook(Consumer<String> beforeSendHook) {
            this.beforeSendHook = beforeSendHook;
        }

        private void setReceivedSharedText(String receivedSharedText) {
            this.receivedSharedText = receivedSharedText;
        }

        private void setDecodeFailure(IllegalArgumentException decodeFailure) {
            this.decodeFailure = decodeFailure;
        }

        private void setReceiveFailure(IOException receiveFailure) {
            this.receiveFailure = receiveFailure;
        }

        private boolean wasReceiveSharedTextDataWithLengthCalled() {
            return receiveSharedTextDataWithLengthCalled;
        }

        private int getReceivedSharedTextLength() {
            return receivedSharedTextLength;
        }
    }
}
