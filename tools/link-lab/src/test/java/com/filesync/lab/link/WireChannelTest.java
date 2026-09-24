package com.filesync.lab.link;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.filesync.lab.Trace;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * End-to-end checks of one emulated channel: bytes offered by one side arrive at the other side at
 * the wire rate, tampered frames never arrive, and everything else survives untouched. No hardware
 * is involved: the "device" end is a plain in-memory stream.
 */
class WireChannelTest {

    /** Thread-safe stand-in for a COM port stream. */
    static final class Sink extends OutputStream {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        @Override
        public synchronized void write(int b) {
            buffer.write(b);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            buffer.write(b, off, len);
        }

        synchronized byte[] bytes() {
            return buffer.toByteArray();
        }

        synchronized int size() {
            return buffer.size();
        }
    }

    private static byte[] latin1(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static String latin1(byte[] b) {
        return new String(b, StandardCharsets.ISO_8859_1);
    }

    @Test
    @Timeout(30)
    void outboundBytesArePacedOntoTheDevice() throws Exception {
        WireModel model = new WireModel().baud(2_000_000).latencyMillis(0); // 200000 B/s
        Sink device = new Sink();
        WireChannel channel = WireChannel.outbound(
                Trace.Dir.PEER_TO_APP, trace(), model, List.of(), 1, device);

        byte[] payload = new byte[20_000];
        fillPattern(payload);
        OutputStream out = channel.writeStream();
        long started = System.nanoTime();
        out.write(payload);
        out.flush();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (device.size() < payload.length && System.nanoTime() < deadline) {
            Thread.sleep(2);
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        channel.close();

        assertEquals(payload.length, device.size());
        assertArrayEquals(payload, device.bytes());
        // 20000 bytes at 200000 B/s needs 100 ms; pacing must actually delay the writer.
        assertTrue(elapsedMs >= 90, "arrived too fast: " + elapsedMs + " ms");
        assertTrue(elapsedMs < 3_000, "arrived too slowly: " + elapsedMs + " ms");
    }

    @Test
    @Timeout(30)
    void inboundBytesArePacedToTheReader() throws Exception {
        WireModel model = new WireModel().baud(2_000_000).latencyMillis(0); // 200000 B/s
        Sink device = new Sink();
        byte[] payload = new byte[20_000];
        fillPattern(payload);
        device.write(payload);
        WireChannel channel = WireChannel.inbound(
                Trace.Dir.APP_TO_PEER, trace(), model, List.of(), 1, new SinkBackedInput(device));

        InputStream in = channel.readStream();
        byte[] received = new byte[payload.length];
        long started = System.nanoTime();
        int read = 0;
        while (read < payload.length) {
            int n = in.read(received, read, payload.length - read);
            if (n < 0) {
                Thread.sleep(2);
                continue;
            }
            read += n;
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        channel.close();

        assertArrayEquals(payload, received);
        assertTrue(elapsedMs >= 90, "arrived too fast: " + elapsedMs + " ms");
        assertTrue(elapsedMs < 3_000, "arrived too slowly: " + elapsedMs + " ms");
    }

    @Test
    @Timeout(30)
    void droppedFrameNeverReachesTheReader() throws Exception {
        WireModel model = new WireModel().baud(2_000_000).latencyMillis(0);
        Sink device = new Sink();
        List<TamperRule> rules = List.of(new TamperRule("HEARTBEAT", 1, TamperRule.Action.DROP, 0, null));
        WireChannel channel = WireChannel.inbound(
                Trace.Dir.APP_TO_PEER, trace(), model, rules, 1, new SinkBackedInput(device));

        device.write(latin1("[[SYNC:HEARTBEAT]]\n"));
        device.write(latin1("[[SYNC:HEARTBEAT_ACK]]\n"));

        InputStream in = channel.readStream();
        StringBuilder seen = new StringBuilder();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (seen.length() < "[[SYNC:HEARTBEAT_ACK]]\n".length() && System.nanoTime() < deadline) {
            int b = in.read();
            if (b >= 0) {
                seen.append((char) b);
            } else {
                Thread.sleep(2);
            }
        }
        channel.close();
        assertEquals("[[SYNC:HEARTBEAT_ACK]]\n", seen.toString());
        assertEquals(1, channel.stats().framesDropped());
    }

    @Test
    @Timeout(30)
    void injectedFrameReachesTheWriter() throws Exception {
        WireModel model = new WireModel().baud(2_000_000).latencyMillis(0);
        Sink device = new Sink();
        List<TamperRule> rules = List.of(
                new TamperRule("HEARTBEAT_ACK", 1, TamperRule.Action.INJECT_AFTER, 0, "[[SYNC:DIRECTION_CHANGE:true]]"));
        WireChannel channel = WireChannel.outbound(
                Trace.Dir.PEER_TO_APP, trace(), model, rules, 1, device);

        channel.writeStream().write(latin1("[[SYNC:HEARTBEAT_ACK]]\n"));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (device.size() < "[[SYNC:HEARTBEAT_ACK]]\n[[SYNC:DIRECTION_CHANGE:true]]\n".length()
                && System.nanoTime() < deadline) {
            Thread.sleep(2);
        }
        channel.close();
        assertEquals(
                "[[SYNC:HEARTBEAT_ACK]]\n[[SYNC:DIRECTION_CHANGE:true]]\n", latin1(device.bytes()));
        assertEquals(1, channel.stats().framesInjected());
    }

    @Test
    @Timeout(30)
    void delayedFrameArrivesLate() throws Exception {
        WireModel model = new WireModel().baud(2_000_000).latencyMillis(0);
        Sink device = new Sink();
        List<TamperRule> rules = List.of(new TamperRule("FILE_DATA", 1, TamperRule.Action.DELAY, 300, null));
        WireChannel channel = WireChannel.outbound(
                Trace.Dir.PEER_TO_APP, trace(), model, rules, 1, device);
        byte[] frame = latin1("[[SYNC:FILE_DATA:a.txt:10:false:1]]\n");
        channel.writeStream().write(frame);

        Thread.sleep(100);
        int afterFastWindow = device.size();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (device.size() < frame.length && System.nanoTime() < deadline) {
            Thread.sleep(2);
        }
        Thread.sleep(50);
        channel.close();
        assertEquals(0, afterFastWindow, "frame must not arrive inside the delay window");
        assertEquals(frame.length, device.size());
    }

    @Test
    @Timeout(30)
    void byteLossSurvivesTheChannel() throws Exception {
        WireModel model = new WireModel().lossPercent(50).baud(2_000_000).latencyMillis(0);
        Sink device = new Sink();
        WireChannel channel = WireChannel.outbound(
                Trace.Dir.PEER_TO_APP, trace(), model, List.of(), 5, device);
        byte[] payload = new byte[1000];
        fillPattern(payload);
        channel.writeStream().write(payload);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (device.size() < 400 && System.nanoTime() < deadline) {
            Thread.sleep(2);
        }
        channel.close();
        assertTrue(device.size() < 1000, "lossy wire must lose bytes: " + device.size());
        long dropped = channel.stats().droppedBytes();
        assertTrue(dropped > 350 && dropped < 650, "dropped=" + dropped);
    }

    private static void fillPattern(byte[] payload) {
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) ('a' + (i % 26));
        }
    }

    private Trace trace() {
        return new Trace(false, (File) null);
    }

    /** Presents a {@link Sink} as the device input end for inbound channels. */
    private static final class SinkBackedInput extends InputStream {
        private final Sink sink;
        private int cursor;

        SinkBackedInput(Sink sink) {
            this.sink = sink;
        }

        @Override
        public int read() {
            byte[] one = new byte[1];
            try {
                int n = read(one, 0, 1);
                return n > 0 ? one[0] & 0xFF : -1;
            } catch (IOException e) {
                return -1;
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            synchronized (sink) {
                int total = sink.size();
                if (total <= cursor) {
                    return 0;
                }
                byte[] bytes = sink.bytes();
                int n = Math.min(len, total - cursor);
                System.arraycopy(bytes, cursor, b, off, n);
                cursor += n;
                return n;
            }
        }
    }
}
