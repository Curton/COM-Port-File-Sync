package com.filesync.lab.e2e;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.filesync.lab.Trace;
import com.filesync.lab.link.WireModel;
import com.filesync.lab.port.DuplexLink;
import com.filesync.serial.SerialPortManager;
import com.filesync.serial.XModemTransfer;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Regression pin for the XMODEM handshake drain pause. The drain exists to absorb a receiver 'C'
 * straggler - a 'C' written but not yet delivered when the sender's handshake completed - so it
 * cannot be mistaken for a block response. 8670fca shrank the pause to a 20ms quiet window; the
 * fixed pause is back at 50ms, and the mid-band case below is what distinguishes the two: a
 * straggler landing inside the pause is swept away, one landing after it costs exactly one
 * duplicate-block re-send, which the real receiver ACKs without saving. Session survival is not the
 * observable - both windows deliver intact data - the packet count is.
 *
 * <p>The pause is adaptive per connection: every connection's first session waits it out, later
 * sessions skip it after one clean session, and the first straggler a fast session observes re-arms
 * it (77d3739's unconditional skip of a pre-first-ACK 'C' is what broke 1.6.53 in the field - there
 * the 'C' may be a live handshake retry whose re-send response is the only thing that recovers a
 * lost first block inside the receiver's handshake window).
 *
 * <p>The link runs at 19200 baud so the first 4096-byte block occupies the wire for ~2.1s: every
 * straggler tested here lands deep inside a live block-ACK wait instead of racing the session's
 * short tail, which makes the two outcomes below deterministic rather than timing-dependent.
 */
class DrainStragglerCTest {

    /** 5000 bytes cross as one 4096 block plus eight 128-byte blocks; EOT is not a data packet. */
    private static final int BASELINE_DATA_PACKETS = 9;

    private static final int PAYLOAD_SIZE = 5_000;
    private static final int BAUD = 19_200;

    @Test
    @Timeout(90)
    void drainPauseAbsorbsAStragglerThatLandsMidPause() throws Exception {
        // ~35ms: past the 20ms quiet window, well inside the 50ms pause. The straggler must be
        // swept and the first block must be written exactly once.
        Result result = runWithStraggler(35);
        assertTrue(result.sent, "the session must complete: " + result);
        assertArrayEquals(result.payload, result.received, "the payload must arrive intact");
        assertEquals(
                BASELINE_DATA_PACKETS,
                result.dataPackets,
                "a straggler inside the drain pause must not reach the block ACK position");
    }

    @Test
    @Timeout(90)
    void aStragglerBeyondThePauseCostsExactlyOneToleratedResend() throws Exception {
        // ~140ms: far past the pause and into a live block ACK wait. The re-send is the duplicate
        // block the receiver ACKs without saving - bounded, never the session.
        Result result = runWithStraggler(140);
        assertTrue(result.sent, "the session must complete: " + result);
        assertArrayEquals(result.payload, result.received, "the payload must arrive intact");
        assertEquals(
                BASELINE_DATA_PACKETS + 1,
                result.dataPackets,
                "a straggler past the pause costs exactly one duplicate-block re-send");
    }

    @Test
    @Timeout(150)
    void adaptiveFastPathCostsOneResendThenReArmsTheConservativePause() throws Exception {
        // One connection, three sessions. A is the connection's first (conservative, clean) and
        // arms the fast path; B pays the fast path's one-straggler price - the 35ms straggler now
        // lands past the absent pause, at block 1's ACK position, and costs exactly one
        // duplicate-block re-send while downgrading the connection; C runs conservative again and
        // absorbs the same straggler in the pause. The fast path's exposure is thus bounded to a
        // single straggler per connection.
        Trace trace = new Trace(false, (File) null);
        WireModel model = new WireModel().baud(BAUD).latencyMillis(1).jitterMillis(1);
        DuplexLink link = new DuplexLink(trace, model);
        TapPortManager senderPort = new TapPortManager(link.sideA(), -1);
        TapPortManager receiverPort = new TapPortManager(link.sideB(), -1);
        try {
            XModemTransfer sender = new XModemTransfer(senderPort);
            XModemTransfer receiver = new XModemTransfer(receiverPort);
            List<String> notices = new CopyOnWriteArrayList<>();
            sender.setProgressListener(
                    new XModemTransfer.TransferProgressListener() {
                        @Override
                        public void onProgress(
                                int currentBlock,
                                int totalBlocks,
                                long bytesTransferred,
                                double speedBytesPerSec) {}

                        @Override
                        public void onError(String message) {}

                        @Override
                        public void onNotice(String message) {
                            notices.add(message);
                        }
                    });

            Session a = runSession(sender, receiver, senderPort, 11);
            assertTrue(a.sent, "session A must complete: " + a);
            assertArrayEquals(a.payload, a.received, "session A's payload must arrive intact");
            assertEquals(
                    BASELINE_DATA_PACKETS,
                    a.dataPackets,
                    "the connection's first session runs the conservative pause");

            receiverPort.setStragglerAfterMillis(35);
            Session b = runSession(sender, receiver, senderPort, 22);
            assertTrue(b.sent, "session B must complete: " + b);
            assertArrayEquals(b.payload, b.received, "session B's payload must arrive intact");
            assertEquals(
                    BASELINE_DATA_PACKETS + 1,
                    b.dataPackets,
                    "the fast session's straggler must cost exactly one duplicate-block re-send");

            Session c = runSession(sender, receiver, senderPort, 33);
            assertTrue(c.sent, "session C must complete: " + c);
            assertArrayEquals(c.payload, c.received, "session C's payload must arrive intact");
            assertEquals(
                    BASELINE_DATA_PACKETS,
                    c.dataPackets,
                    "the downgraded session must absorb the straggler in the pause");

            assertEquals(1, notices.size(), "exactly session B's downgrade may be reported");
            assertTrue(
                    notices.get(0).contains("re-armed the conservative"),
                    "the notice must report the downgrade: " + notices);
        } finally {
            receiverPort.shutdown();
            senderPort.shutdown();
            link.close();
        }
    }

    /** Runs one send/receive session over the live link and reports its outcome. */
    private static Session runSession(
            XModemTransfer sender, XModemTransfer receiver, TapPortManager senderPort, int seed)
            throws Exception {
        byte[] payload = new byte[PAYLOAD_SIZE];
        new Random(seed).nextBytes(payload);
        int packetsBefore = senderPort.dataPackets;

        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        Throwable[] failure = new Throwable[1];
        Thread reader =
                new Thread(
                        () -> {
                            try {
                                sink.write(receiver.receive(payload.length));
                            } catch (Throwable e) {
                                failure[0] = e;
                            }
                        });
        reader.start();
        boolean sent = sender.send(payload);
        reader.join(60_000);
        if (failure[0] != null) {
            throw new AssertionError("receiver failed", failure[0]);
        }
        return new Session(
                payload, sent, sink.toByteArray(), senderPort.dataPackets - packetsBefore);
    }

    private record Session(byte[] payload, boolean sent, byte[] received, int dataPackets) {
        @Override
        public String toString() {
            return "sent="
                    + sent
                    + " received="
                    + received.length
                    + "/"
                    + payload.length
                    + " dataPackets="
                    + dataPackets;
        }
    }

    private static Result runWithStraggler(int stragglerAfterMillis) throws Exception {
        Trace trace = new Trace(false, (File) null);
        WireModel model = new WireModel().baud(BAUD).latencyMillis(1).jitterMillis(1);
        DuplexLink link = new DuplexLink(trace, model);
        TapPortManager senderPort = new TapPortManager(link.sideA(), -1);
        TapPortManager receiverPort = new TapPortManager(link.sideB(), stragglerAfterMillis);
        try {
            XModemTransfer sender = new XModemTransfer(senderPort);
            XModemTransfer receiver = new XModemTransfer(receiverPort);
            byte[] payload = new byte[PAYLOAD_SIZE];
            new Random(7).nextBytes(payload);

            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            Throwable[] failure = new Throwable[1];
            Thread reader =
                    new Thread(
                            () -> {
                                try {
                                    sink.write(receiver.receive(payload.length));
                                } catch (Throwable e) {
                                    failure[0] = e;
                                }
                            });
            reader.start();
            boolean sent = sender.send(payload);
            reader.join(60_000);
            if (failure[0] != null) {
                throw new AssertionError("receiver failed", failure[0]);
            }
            return new Result(payload, sent, sink.toByteArray(), senderPort.dataPackets);
        } finally {
            receiverPort.shutdown();
            senderPort.shutdown();
            link.close();
        }
    }

    private record Result(byte[] payload, boolean sent, byte[] received, int dataPackets) {
        @Override
        public String toString() {
            return "sent=" + sent + " received=" + received.length + "/" + payload.length;
        }
    }

    /**
     * Delegating port manager: with {@code stragglerAfterMillis >= 0} it schedules a duplicate 'C'
     * write that far after every real 'C' the attached side emits, simulating a straggler the
     * receiver's poll cycle already sent when the sender's handshake completed. The sender end
     * passes -1 and only counts data-packet writes. Epoch and open state delegate so the production
     * session fencing keeps working through the wrapper.
     *
     * <p>The injected write waits on an absolute {@code nanoTime} deadline captured when the real
     * 'C' was written, spinning rather than sleeping: a {@code Thread.sleep} on Windows overshoots
     * by the timer granularity and the thread-pool start latency is unbounded, either of which
     * would smear the straggler's landing point by tens of milliseconds.
     */
    private static final class TapPortManager extends SerialPortManager {
        private final SerialPortManager delegate;
        private volatile long stragglerAfterNanos;
        private final ExecutorService injector = Executors.newSingleThreadExecutor();
        private volatile int dataPackets;

        private TapPortManager(SerialPortManager delegate, int stragglerAfterMillis) {
            super(9600, 8, 1, 0);
            this.delegate = delegate;
            this.stragglerAfterNanos =
                    stragglerAfterMillis < 0 ? -1 : stragglerAfterMillis * 1_000_000L;
        }

        /** Enables or disables the stray-'C' injection for the sessions still to come. */
        void setStragglerAfterMillis(int stragglerAfterMillis) {
            this.stragglerAfterNanos =
                    stragglerAfterMillis < 0 ? -1 : stragglerAfterMillis * 1_000_000L;
        }

        private void shutdown() {
            injector.shutdownNow();
        }

        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        @Override
        public long currentEpoch() {
            return delegate.currentEpoch();
        }

        @Override
        public int available() throws IOException {
            return delegate.available();
        }

        @Override
        public int read() throws IOException {
            return delegate.read();
        }

        @Override
        public byte[] readExact(int length, int timeoutMs) throws IOException {
            return delegate.readExact(length, timeoutMs);
        }

        @Override
        public void clearInputBuffer() throws IOException {
            delegate.clearInputBuffer();
        }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
            maybeScheduleStray(b);
        }

        @Override
        public void write(byte[] data) throws IOException {
            delegate.write(data);
            if (data.length > 4) {
                dataPackets++;
            }
            for (byte x : data) {
                maybeScheduleStray(x);
            }
        }

        private void maybeScheduleStray(int b) {
            if (stragglerAfterNanos < 0 || b != XModemTransfer.C) {
                return;
            }
            long at = System.nanoTime() + stragglerAfterNanos;
            injector.submit(
                    () -> {
                        while (System.nanoTime() < at && !Thread.currentThread().isInterrupted()) {
                            Thread.onSpinWait();
                        }
                        try {
                            delegate.write(new byte[] {XModemTransfer.C});
                        } catch (IOException ignored) {
                            // The session is gone; the stray 'C' no longer matters.
                        }
                    });
        }
    }
}
