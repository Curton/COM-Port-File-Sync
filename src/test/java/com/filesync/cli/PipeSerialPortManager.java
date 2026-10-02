package com.filesync.cli;

import com.filesync.serial.SerialPortManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * A {@link SerialPortManager} wired to two crossed {@link Conduit}s: a null-modem cable inside one
 * JVM for CLI end-to-end tests. Every byte-level method the protocol stack uses is overridden, so
 * the base class's private jSerialComm streams are never touched. Reads keep the real manager's
 * semantics (line reads bounded by timeout, exact reads with a sliding idle deadline, -1 on read
 * timeout, EOF after {@link #close()}) so the stack cannot tell it apart from hardware.
 */
final class PipeSerialPortManager extends SerialPortManager {

    /** Matches the read timeout the real manager configures on the port. */
    private static final long READ_TIMEOUT_MS = 5000;

    private static final long POLL_INTERVAL_MS = 1;

    private final Conduit inbound;
    private final Conduit outbound;
    private volatile boolean open = true;
    private volatile String portName;

    /** {@code inbound} carries peer-to-us bytes; {@code outbound} carries us-to-peer bytes. */
    PipeSerialPortManager(Conduit inbound, Conduit outbound) {
        this.inbound = inbound;
        this.outbound = outbound;
    }

    @Override
    public boolean open(String portName) {
        this.portName = portName;
        open = true;
        return true;
    }

    @Override
    public void close() {
        open = false;
        // Model a cable pull: both directions see EOF so the peer unwinds quickly instead of
        // waiting for heartbeat timeouts.
        inbound.close();
        outbound.close();
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public String getPortName() {
        return portName;
    }

    @Override
    public int available() {
        return inbound.available();
    }

    @Override
    public int read() throws IOException {
        return inbound.read(READ_TIMEOUT_MS);
    }

    @Override
    public int read(byte[] buffer) throws IOException {
        int first = inbound.read(READ_TIMEOUT_MS);
        if (first < 0) {
            return -1;
        }
        buffer[0] = (byte) first;
        int bytesRead = 1;
        while (bytesRead < buffer.length && inbound.available() > 0) {
            int b = inbound.read(READ_TIMEOUT_MS);
            if (b < 0) {
                break;
            }
            buffer[bytesRead++] = (byte) b;
        }
        return bytesRead;
    }

    @Override
    public String readLine(int timeoutMs) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        long startTime = System.currentTimeMillis();
        while (true) {
            long remaining = timeoutMs - (System.currentTimeMillis() - startTime);
            if (remaining <= 0) {
                throw new IOException("Read timeout");
            }
            int b = inbound.read(remaining);
            if (b == -1) {
                sleepQuietly();
                continue;
            }
            if (b == '\n') {
                return line.toString(StandardCharsets.UTF_8.name());
            }
            if (b != '\r') {
                line.write(b);
            }
        }
    }

    @Override
    public byte[] readExact(int length, int timeoutMs) throws IOException {
        byte[] buffer = new byte[length];
        int bytesRead = 0;
        // Sliding/inactivity deadline, like the real manager: reset on progress so a slow-but-
        // moving transfer survives, only complete silence aborts.
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (bytesRead < length) {
            if (System.currentTimeMillis() > deadline) {
                throw new IOException(
                        "Read timeout: expected " + length + " bytes, got " + bytesRead);
            }
            int available = inbound.available();
            if (available > 0) {
                int toRead = Math.min(available, length - bytesRead);
                for (int i = 0; i < toRead; i++) {
                    buffer[bytesRead++] = (byte) inbound.read(READ_TIMEOUT_MS);
                }
                deadline = System.currentTimeMillis() + timeoutMs;
            } else {
                sleepQuietly();
            }
        }
        return buffer;
    }

    @Override
    public void write(int b) {
        outbound.offer(b);
    }

    @Override
    public void write(byte[] data) {
        outbound.offer(data, 0, data.length);
    }

    @Override
    public void clearInputBuffer() {
        while (inbound.available() > 0) {
            try {
                if (inbound.read(10) < 0) {
                    break;
                }
            } catch (IOException e) {
                break;
            }
        }
    }

    private static void sleepQuietly() throws IOException {
        try {
            Thread.sleep(POLL_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Read interrupted");
        }
    }
}
