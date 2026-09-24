package com.filesync.lab.selftest;

import com.filesync.lab.Trace;
import com.filesync.lab.link.WireChannel;
import com.filesync.lab.link.WireModel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

/**
 * Hardware-free proof that the emulated link really moves bytes at the configured serial speed: a
 * fixed payload is pushed through one outbound channel into a memory sink, and the achieved rate is
 * compared with {@link WireModel#bytesPerSecond()}.
 */
public final class LinkSelfTest {

    private static final int MIN_PAYLOAD_BYTES = 10_000;
    private static final int MAX_PAYLOAD_BYTES = 200_000;
    private static final double TARGET_WIRE_SECONDS = 2.0;

    /** Runs the measurement; returns a process exit code (0 = within tolerance). */
    public static int measure(WireModel model, Trace trace) {
        // Size the payload for roughly two seconds of wire time so slow links stay quick.
        int payloadBytes = (int) Math.min(
                MAX_PAYLOAD_BYTES,
                Math.max(MIN_PAYLOAD_BYTES, model.bytesPerSecond() * TARGET_WIRE_SECONDS));
        MemorySink sink = new MemorySink();
        WireChannel channel = WireChannel.outbound(Trace.Dir.PEER_TO_APP, trace, model, java.util.List.of(), 1, sink);

        byte[] payload = new byte[payloadBytes];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) ('a' + (i % 26));
        }

        long started = System.nanoTime();
        try {
            channel.writeStream().write(payload);
        } catch (IOException e) {
            trace.log(Trace.Dir.WIRE, "selftest write failed: " + e.getMessage());
            return 1;
        }
        // Watch the wire until it goes quiet. The measurement window closes at the last byte
        // that actually arrived, so the trailing quiet wait does not dilute the rate.
        long lastArrivalNanos = started;
        long quietMs = 0;
        int lastSize = -1;
        long maxWaitMs = (long) (payloadBytes / model.bytesPerSecond() * 1000) + 15_000;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxWaitMs);
        while (System.nanoTime() < deadline) {
            sleep(50);
            int size = sink.size();
            if (size > lastSize) {
                lastSize = size;
                lastArrivalNanos = System.nanoTime();
                quietMs = 0;
            } else {
                quietMs += 50;
                if (quietMs >= 500) {
                    break;
                }
            }
        }
        int delivered = sink.size();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(lastArrivalNanos - started);
        channel.close();

        double expected = model.bytesPerSecond();
        double achieved = delivered * 1000.0 / Math.max(1, elapsedMs);
        double lossRatio = 1.0 - (double) delivered / payloadBytes;
        boolean ok = delivered > 0
                && achieved >= expected * 0.85
                && achieved <= expected * 1.15
                && (model.lossPercent() <= 0 ? delivered == payloadBytes : lossRatio <= model.lossPercent() / 100.0 + 0.02);
        trace.log(
                Trace.Dir.WIRE,
                String.format(
                        "selftest: %d/%d bytes delivered in %d ms -> %.0f B/s (model: %.0f B/s, loss %.2f%%, %s)",
                        delivered,
                        payloadBytes,
                        elapsedMs,
                        achieved,
                        expected,
                        lossRatio * 100,
                        ok ? "PASS" : "FAIL"));
        trace.log(Trace.Dir.WIRE, "selftest stats: " + channel.stats().summary());
        return ok ? 0 : 1;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The "COM port" the self test writes into. */
    private static final class MemorySink extends OutputStream {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        @Override
        public synchronized void write(int b) {
            buffer.write(b);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            buffer.write(b, off, len);
        }

        synchronized int size() {
            return buffer.size();
        }
    }
}
