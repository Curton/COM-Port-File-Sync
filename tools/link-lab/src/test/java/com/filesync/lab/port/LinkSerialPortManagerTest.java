package com.filesync.lab.port;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.filesync.lab.Trace;
import com.filesync.lab.link.TamperRule;
import com.filesync.lab.link.WireModel;
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
 * The app-facing port wrapper over the emulated wire. Everything runs against in-memory device
 * streams, so these tests need no COM ports yet still exercise the exact read/write paths the
 * protocol stack uses.
 */
class LinkSerialPortManagerTest {

    /** The "COM port" the manager talks to: pre-loaded inbound bytes, captured outbound bytes. */
    private static final class FakeDevice extends OutputStream {
        private final ByteArrayOutputStream outbound = new ByteArrayOutputStream();
        private byte[] inbound = new byte[0];
        private int cursor;

        @Override
        public synchronized void write(int b) {
            outbound.write(b);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            outbound.write(b, off, len);
        }

        synchronized void load(String data) {
            inbound = data.getBytes(StandardCharsets.ISO_8859_1);
            cursor = 0;
        }

        synchronized int outboundSize() {
            return outbound.size();
        }

        synchronized String outboundText() {
            return outbound.toString(StandardCharsets.ISO_8859_1);
        }

        InputStream inboundStream() {
            return new InputStream() {
                @Override
                public int read() {
                    byte[] one = new byte[1];
                    int n = read(one, 0, 1);
                    return n > 0 ? one[0] & 0xFF : -1;
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    synchronized (FakeDevice.this) {
                        if (cursor >= inbound.length) {
                            return 0;
                        }
                        int n = Math.min(len, inbound.length - cursor);
                        System.arraycopy(inbound, cursor, b, off, n);
                        cursor += n;
                        return n;
                    }
                }
            };
        }
    }

    private static Trace trace() {
        return new Trace(false, (File) null);
    }

    private static WireModel fastLink() {
        return new WireModel().baud(2_000_000).latencyMillis(0); // 200000 B/s
    }

    @Test
    @Timeout(20)
    void writesReachTheDeviceAndArePaced() throws Exception {
        FakeDevice device = new FakeDevice();
        LinkSerialPortManager port =
                new LinkSerialPortManager(
                        trace(),
                        fastLink(),
                        List.of(),
                        List.of(),
                        1,
                        device.inboundStream(),
                        device,
                        "TEST");

        assertTrue(port.isOpen());
        byte[] payload = new byte[20_000];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) i;
        }
        long started = System.nanoTime();
        port.write(payload);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (device.outboundSize() < payload.length && System.nanoTime() < deadline) {
            Thread.sleep(2);
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        port.close();

        assertEquals(payload.length, device.outboundSize());
        // 20000 bytes at 200000 B/s needs 100 ms of wire time.
        assertTrue(elapsedMs >= 90, "wrote too fast: " + elapsedMs + " ms");
        assertTrue(elapsedMs < 3_000, "wrote too slowly: " + elapsedMs + " ms");
        assertFalse(port.isOpen());
    }

    @Test
    @Timeout(20)
    void writeLineAppendsNewline() throws Exception {
        FakeDevice device = new FakeDevice();
        LinkSerialPortManager port =
                new LinkSerialPortManager(
                        trace(),
                        fastLink(),
                        List.of(),
                        List.of(),
                        1,
                        device.inboundStream(),
                        device,
                        "TEST");
        port.writeLine("[[SYNC:HEARTBEAT]]");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (device.outboundSize() < 20 && System.nanoTime() < deadline) {
            Thread.sleep(2);
        }
        Thread.sleep(20);
        port.close();
        assertEquals("[[SYNC:HEARTBEAT]]\n", device.outboundText());
    }

    @Test
    @Timeout(20)
    void readLineReturnsTheFrameTheDeviceSent() throws Exception {
        FakeDevice device = new FakeDevice();
        device.load("[[SYNC:HEARTBEAT]]\n[[SYNC:ACK]]\n");
        LinkSerialPortManager port =
                new LinkSerialPortManager(
                        trace(),
                        fastLink(),
                        List.of(),
                        List.of(),
                        1,
                        device.inboundStream(),
                        device,
                        "TEST");

        assertEquals("[[SYNC:HEARTBEAT]]", port.readLine(5_000));
        assertEquals("[[SYNC:ACK]]", port.readLine(5_000));
        port.close();
    }

    @Test
    @Timeout(20)
    void readLineTimesOutWhenTheWireIsSilent() {
        FakeDevice device = new FakeDevice();
        LinkSerialPortManager port =
                new LinkSerialPortManager(
                        trace(),
                        fastLink(),
                        List.of(),
                        List.of(),
                        1,
                        device.inboundStream(),
                        device,
                        "TEST");
        IOException failure = assertThrows(IOException.class, () -> port.readLine(200));
        assertTrue(failure.getMessage().contains("Read timeout"), failure.getMessage());
    }

    @Test
    @Timeout(20)
    void readExactAssemblesAcrossPacingBoundaries() throws Exception {
        FakeDevice device = new FakeDevice();
        byte[] data = new byte[5_000];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 31);
        }
        device.load(new String(data, StandardCharsets.ISO_8859_1));
        LinkSerialPortManager port =
                new LinkSerialPortManager(
                        trace(),
                        fastLink(),
                        List.of(),
                        List.of(),
                        1,
                        device.inboundStream(),
                        device,
                        "TEST");

        long started = System.nanoTime();
        byte[] read = port.readExact(data.length, 20_000);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        port.close();

        assertArrayEquals(data, read);
        // 5000 bytes at 200000 B/s needs 25 ms of wire time.
        assertTrue(elapsedMs >= 20, "read too fast: " + elapsedMs + " ms");
        assertTrue(elapsedMs < 3_000, "read too slowly: " + elapsedMs + " ms");
    }

    @Test
    @Timeout(20)
    void droppedFrameNeverReachesTheApplication() throws Exception {
        FakeDevice device = new FakeDevice();
        device.load("[[SYNC:HEARTBEAT]]\n[[SYNC:HEARTBEAT_ACK]]\n");
        List<TamperRule> rules =
                List.of(new TamperRule("HEARTBEAT", 1, TamperRule.Action.DROP, 0, null));
        LinkSerialPortManager port =
                new LinkSerialPortManager(
                        trace(),
                        fastLink(),
                        rules,
                        List.of(),
                        1,
                        device.inboundStream(),
                        device,
                        "TEST");

        // The first frame is dropped by the emulator, so the first line the app reads is the ACK.
        assertEquals("[[SYNC:HEARTBEAT_ACK]]", port.readLine(5_000));
        port.close();
    }

    @Test
    @Timeout(20)
    void clearInputBufferSilencesTheWire() throws Exception {
        FakeDevice device = new FakeDevice();
        device.load("[[SYNC:HEARTBEAT]]\n");
        LinkSerialPortManager port =
                new LinkSerialPortManager(
                        trace(),
                        fastLink(),
                        List.of(),
                        List.of(),
                        1,
                        device.inboundStream(),
                        device,
                        "TEST");
        // The pump drains the device on its own thread, so the frame must be confirmed in the
        // app-side buffer before clearing: clearing earlier would race with the pump delivering
        // the frame afterwards.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (port.available() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(2);
        }
        assertTrue(port.available() > 0, "frame never reached the app-side buffer");
        port.clearInputBuffer();
        IOException failure = assertThrows(IOException.class, () -> port.readLine(200));
        assertTrue(failure.getMessage().contains("Read timeout"), failure.getMessage());
        port.close();
    }

    @Test
    @Timeout(20)
    void availableReflectsWhatTheWireHasDelivered() throws Exception {
        FakeDevice device = new FakeDevice();
        device.load("0123456789");
        LinkSerialPortManager port =
                new LinkSerialPortManager(
                        trace(),
                        fastLink(),
                        List.of(),
                        List.of(),
                        1,
                        device.inboundStream(),
                        device,
                        "TEST");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (port.available() < 10 && System.nanoTime() < deadline) {
            Thread.sleep(2);
        }
        int available = port.available();
        byte[] read = new byte[available];
        int n = port.read(read);
        port.close();
        assertEquals(10, available);
        assertEquals(10, n);
        assertEquals("0123456789", new String(read, 0, n, StandardCharsets.ISO_8859_1));
    }
}
