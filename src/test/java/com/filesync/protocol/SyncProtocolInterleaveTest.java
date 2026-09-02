package com.filesync.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.filesync.protocol.SyncProtocol.InterleavableTextSource;
import com.filesync.protocol.SyncProtocol.PendingText;
import com.filesync.serial.SerialPortManager;
import com.filesync.serial.XModemTransfer;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Tests the between-blocks shared-text interleave at the protocol layer: inline eligibility, the
 * exact wire frame, and the tri-state result the XMODEM block boundary hook reports.
 */
class SyncProtocolInterleaveTest {

    @Test
    void sendSharedTextInterleavedWritesFramedLineAndWaitsForAck() throws IOException {
        RecordingSerialPortManager port = new RecordingSerialPortManager();
        port.feedReads(XModemTransfer.ACK);
        SyncProtocol protocol = new SyncProtocol(port);

        assertTrue(protocol.sendSharedTextInterleaved(123L, "hello"));

        assertEquals(1, port.getByteWrites().size(), "exactly one frame on the wire");
        assertArrayEquals(
                "[[SYNC:SHARED_TEXT:123:aGVsbG8=]]\n"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                port.getByteWrites().get(0));
    }

    @Test
    void sendSharedTextInterleavedRejectsTextBeyondTheInlineBudget() throws IOException {
        RecordingSerialPortManager port = new RecordingSerialPortManager();
        port.setBaudRate(1200); // inline budget = 120 B/s * 5 s minus framing
        SyncProtocol protocol = new SyncProtocol(port);

        assertFalse(protocol.sendSharedTextInterleaved(1L, "x".repeat(700)));
        assertEquals(0, port.getByteWrites().size(), "an ineligible text must not be written");
    }

    @Test
    void flushHookReportsNothingPendingWithoutSourceOrPayload() throws IOException {
        SyncProtocol protocol = new SyncProtocol(new RecordingSerialPortManager());

        assertEquals(
                XModemTransfer.InterleaveResult.NOTHING_PENDING,
                protocol.flushPendingSharedTextBetweenBlocks());

        protocol.setInterleavableTextSource(new StubSource(null));
        assertEquals(
                XModemTransfer.InterleaveResult.NOTHING_PENDING,
                protocol.flushPendingSharedTextBetweenBlocks());
    }

    @Test
    void flushHookSendsPendingTextAndClearsExactlyIt() throws IOException {
        RecordingSerialPortManager port = new RecordingSerialPortManager();
        port.feedReads(XModemTransfer.ACK);
        SyncProtocol protocol = new SyncProtocol(port);
        AtomicReference<PendingText> pending =
                new AtomicReference<>(new StubPendingText(123L, "jump the queue"));
        protocol.setInterleavableTextSource(
                new StubSource(pending::get, expected -> pending.compareAndSet(expected, null)));

        assertEquals(
                XModemTransfer.InterleaveResult.SENT,
                protocol.flushPendingSharedTextBetweenBlocks());
        assertNull(pending.get(), "the delivered text leaves the pending slot");
        assertEquals(1, port.getByteWrites().size());
    }

    @Test
    void flushHookReportsFailedWhenFrameIsNeverAcknowledged() throws IOException {
        // The port always serves an immediate garbage read (EOF masked to 0xFF), so the three
        // resend attempts fail without any real-time waiting.
        RecordingSerialPortManager port = new GarbageResponseSerialPortManager();
        SyncProtocol protocol = new SyncProtocol(port);
        AtomicReference<PendingText> pending =
                new AtomicReference<>(new StubPendingText(7L, "held"));
        protocol.setInterleavableTextSource(
                new StubSource(pending::get, expected -> pending.compareAndSet(expected, null)));

        assertEquals(
                XModemTransfer.InterleaveResult.FAILED,
                protocol.flushPendingSharedTextBetweenBlocks());
        assertNotNull(pending.get(), "an undelivered text must stay queued");
        assertEquals(
                3,
                port.getByteWrites().size(),
                "the frame is retried per attempt before giving up");
    }

    @Test
    void flushHookSurvivesASourceThatThrowsNothingButClearsNothing() throws IOException {
        RecordingSerialPortManager port = new RecordingSerialPortManager();
        port.feedReads(XModemTransfer.ACK);
        SyncProtocol protocol = new SyncProtocol(port);
        PendingText immutable = new StubPendingText(9L, "kept");
        // A source whose clearIfCurrent always fails (e.g. the slot was replaced mid-send):
        // the hook still reports SENT because the frame was acknowledged.
        protocol.setInterleavableTextSource(new StubSource(() -> immutable, expected -> false));

        assertEquals(
                XModemTransfer.InterleaveResult.SENT,
                protocol.flushPendingSharedTextBetweenBlocks());
    }

    private static final class StubPendingText implements PendingText {
        private final long timestamp;
        private final String text;

        private StubPendingText(long timestamp, String text) {
            this.timestamp = timestamp;
            this.text = text;
        }

        @Override
        public long timestamp() {
            return timestamp;
        }

        @Override
        public String text() {
            return text;
        }
    }

    private static final class StubSource implements InterleavableTextSource {
        private final java.util.function.Supplier<PendingText> peek;
        private final java.util.function.Predicate<PendingText> clear;

        private StubSource(PendingText fixed) {
            this(() -> fixed, expected -> false);
        }

        private StubSource(
                java.util.function.Supplier<PendingText> peek,
                java.util.function.Predicate<PendingText> clear) {
            this.peek = peek;
            this.clear = clear;
        }

        @Override
        public PendingText peek() {
            return peek.get();
        }

        @Override
        public boolean clearIfCurrent(PendingText expected) {
            return clear.test(expected);
        }
    }

    /** Port manager that scripts reads from a queue and records every outbound write. */
    private static class RecordingSerialPortManager extends SerialPortManager {
        private final ArrayDeque<Integer> reads = new ArrayDeque<>();
        private final List<byte[]> byteWrites = new CopyOnWriteArrayList<>();

        void feedReads(int... bytes) {
            for (int b : bytes) {
                reads.add(b);
            }
        }

        List<byte[]> getByteWrites() {
            return byteWrites;
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public int available() {
            return reads.isEmpty() ? 0 : 1;
        }

        @Override
        public int read() {
            Integer b = reads.poll();
            return b == null ? -1 : b;
        }

        @Override
        public byte[] readExact(int length, int timeoutMs) {
            byte[] data = new byte[length];
            for (int i = 0; i < length; i++) {
                Integer b = reads.poll();
                data[i] = b == null ? 0 : (byte) b.intValue();
            }
            return data;
        }

        @Override
        public void write(int b) {
            // Control bytes (handshake 'C', EOT) are not asserted by these tests.
        }

        @Override
        public void write(byte[] data) {
            byteWrites.add(data.clone());
        }

        @Override
        public void clearInputBuffer() {
            reads.clear();
        }
    }

    /** Port manager whose reads always yield an immediate garbage byte (no timeout waiting). */
    private static final class GarbageResponseSerialPortManager extends RecordingSerialPortManager {
        @Override
        public int available() {
            return 1;
        }

        @Override
        public int read() {
            return -1;
        }
    }
}
