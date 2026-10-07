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
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Regression pin for the XMODEM handshake straggler handling. A receiver 'C' straggler is a 'C'
 * written but not yet delivered when the sender's handshake completed, so it can surface in the
 * middle of the transfer and — if mistaken for a block response — re-send a block the receiver
 * already has. Two defenses cover it: the short drain pause sweeps stragglers already in flight,
 * and the response read skips any 'C' that outlasts the pause, because 'C' has no meaning once
 * blocks are flowing. Together they make the outcome independent of the straggler's arrival
 * offset: both cases below — just past the pause and far past it — deliver intact data with the
 * baseline packet count and no duplicate block.
 *
 * <p>The link runs at 19200 baud so the first 4096-byte block occupies the wire for ~2.1s: every
 * straggler tested here lands deep inside a live block-ACK wait instead of racing the session's
 * short tail, which makes the outcomes below deterministic rather than timing-dependent.
 */
class DrainStragglerCTest {

    /** 5000 bytes cross as one 4096 block plus eight 128-byte blocks; EOT is not a data packet. */
    private static final int BASELINE_DATA_PACKETS = 9;
    private static final int PAYLOAD_SIZE = 5_000;
    private static final int BAUD = 19_200;

    @Test
    @Timeout(90)
    void aStragglerJustPastTheDrainPauseIsSkippedAtTheBlockAckPosition() throws Exception {
        // ~35ms: past the short drain pause, early enough that the straggler is buffered long
        // before the first block's ACK is due. It must be skipped there, not re-sent: the block
        // is written exactly once.
        Result result = runWithStraggler(35);
        assertTrue(result.sent, "the session must complete: " + result);
        assertArrayEquals(result.payload, result.received, "the payload must arrive intact");
        assertEquals(
                BASELINE_DATA_PACKETS,
                result.dataPackets,
                "a straggler past the pause must not re-send the block");
    }

    @Test
    @Timeout(90)
    void aStragglerFarPastTheDrainPauseIsAlsoSkippedWithoutAnyResend() throws Exception {
        // ~140ms: far past the pause, landing mid-transfer with the ACK still ~2s away. The skip
        // is offset-independent, so this costs no more than the 35ms case — and a re-sent block
        // would be an observable regression.
        Result result = runWithStraggler(140);
        assertTrue(result.sent, "the session must complete: " + result);
        assertArrayEquals(result.payload, result.received, "the payload must arrive intact");
        assertEquals(
                BASELINE_DATA_PACKETS,
                result.dataPackets,
                "a late straggler must be skipped regardless of its offset");
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
     * passes -1 and only counts data-packet writes. Epoch and open state delegate so the
     * production session fencing keeps working through the wrapper.
     *
     * <p>The injected write waits on an absolute {@code nanoTime} deadline captured when the real
     * 'C' was written, spinning rather than sleeping: a {@code Thread.sleep} on Windows overshoots
     * by the timer granularity and the thread-pool start latency is unbounded, either of which
     * would smear the straggler's landing point by tens of milliseconds.
     */
    private static final class TapPortManager extends SerialPortManager {
        private final SerialPortManager delegate;
        private final long stragglerAfterNanos;
        private final ExecutorService injector = Executors.newSingleThreadExecutor();
        private volatile int dataPackets;

        private TapPortManager(SerialPortManager delegate, int stragglerAfterMillis) {
            super(9600, 8, 1, 0);
            this.delegate = delegate;
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
