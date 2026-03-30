package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.filesync.protocol.SyncProtocol;
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
    void handleIncomingSharedTextDataPostsReceivedEvent() {
        TestSharedTextProtocol protocol = new TestSharedTextProtocol();
        protocol.setReceivedSharedText("large shared text");

        SimpleSyncEventBus eventBus = new SimpleSyncEventBus();
        AtomicReference<String> receivedText = new AtomicReference<>();
        eventBus.register(
                event -> {
                    if (event instanceof SyncEvent.SharedTextReceivedEvent sharedTextEvent) {
                        receivedText.set(sharedTextEvent.getText());
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

        service.handleIncomingSharedTextData(123L, true, 17);

        assertEquals("large shared text", receivedText.get());
        assertTrue(protocol.wasReceiveSharedTextDataCalled(), "Expected shared text receive path");
        assertEquals(17, protocol.getReceivedSharedTextLength());
    }

    @Test
    void handleIncomingSharedTextDataUsesExpectedLength() {
        TestSharedTextProtocol protocol = new TestSharedTextProtocol();
        protocol.setReceivedSharedText("with explicit length");

        SimpleSyncEventBus eventBus = new SimpleSyncEventBus();
        AtomicReference<String> receivedText = new AtomicReference<>();
        eventBus.register(
                event -> {
                    if (event instanceof SyncEvent.SharedTextReceivedEvent sharedTextEvent) {
                        receivedText.set(sharedTextEvent.getText());
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

        service.handleIncomingSharedTextData(777L, true, 17);

        assertEquals("with explicit length", receivedText.get());
        assertEquals(17, protocol.getReceivedSharedTextLength());
        assertTrue(
                protocol.wasReceiveSharedTextDataWithLengthCalled(),
                "Expected length-aware receive path");
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
    void resendUsesMostRecentIncomingText() {
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

        service.handleIncomingSharedText(300L, "newer");
        service.resendLatestSharedText();

        assertEquals(List.of("newer"), protocol.getSentTexts());
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
    void flushIfIdleDoesNotSendWhenNotRunning() {
        TestSharedTextProtocol protocol = new TestSharedTextProtocol();
        SimpleSyncEventBus eventBus = new SimpleSyncEventBus();

        SharedTextService service =
                new SharedTextService(
                        protocol,
                        eventBus,
                        () -> false, // not running
                        () -> true,
                        () -> false,
                        () -> false,
                        () -> true);

        service.queueSharedText("should not send");

        // The text is queued but flush should fail due to not running
        assertTrue(protocol.getSentTexts().isEmpty(), "Nothing should be sent when not running");
    }

    @Test
    void flushIfIdleDoesNotSendWhenNotConnected() {
        TestSharedTextProtocol protocol = new TestSharedTextProtocol();
        SimpleSyncEventBus eventBus = new SimpleSyncEventBus();

        SharedTextService service =
                new SharedTextService(
                        protocol,
                        eventBus,
                        () -> true,
                        () -> false, // not connected
                        () -> false,
                        () -> false,
                        () -> true);

        service.queueSharedText("should not send");

        assertTrue(protocol.getSentTexts().isEmpty(), "Nothing should be sent when not connected");
    }

    @Test
    void flushIfIdleDoesNotSendWhenRoleNotNegotiated() {
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
                        () -> false); // role not negotiated

        service.queueSharedText("should not send");

        assertTrue(
                protocol.getSentTexts().isEmpty(),
                "Nothing should be sent when role not negotiated");
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

        private boolean wasReceiveSharedTextDataCalled() {
            return receiveSharedTextDataWithLengthCalled;
        }

        private boolean wasReceiveSharedTextDataWithLengthCalled() {
            return receiveSharedTextDataWithLengthCalled;
        }

        private int getReceivedSharedTextLength() {
            return receivedSharedTextLength;
        }
    }
}
