package com.filesync.sync;

import com.filesync.serial.SerialPortManager;
import com.filesync.serial.XModemTransfer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A {@link SerialPortManager} that never touches real hardware: it serves scripted protocol frames
 * from a queue for {@link #readLine(int)}, captures outbound {@link #writeLine(String)} calls, and
 * can be flipped into a mode where {@link #readLine(int)} throws to simulate a communication
 * failure. {@link #available()} reflects whether a frame is queued (or whether a failure is staged)
 * so the manager's listen-loop {@code hasData()} gating works.
 *
 * <p>A separate byte-level inbox backs {@link #read()} and {@link #readExact(int, int)} so XMODEM
 * transfers can be scripted alongside control frames; {@link #failByteReads()} flips the byte-read
 * path into a failure mode to unwind an in-progress XMODEM send quickly.
 */
class ScriptedSerialPortManager extends SerialPortManager {
    private final AtomicBoolean open = new AtomicBoolean(true);
    private volatile String portName;
    private final ConcurrentLinkedQueue<String> inbox = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Byte> byteInbox = new ConcurrentLinkedQueue<>();
    private final List<String> written = new CopyOnWriteArrayList<>();
    private volatile boolean readLineThrows;
    private volatile boolean byteReadThrows;

    void feedLine(String frame) {
        inbox.add(frame);
    }

    void feedBytes(byte[] data) {
        for (byte b : data) {
            byteInbox.add(b);
        }
    }

    void causeReadLineFailure() {
        readLineThrows = true;
    }

    void failByteReads() {
        byteReadThrows = true;
    }

    List<String> getWrittenLines() {
        return written;
    }

    @Override
    public boolean open(String portName) {
        this.portName = portName;
        open.set(true);
        return true;
    }

    @Override
    public void close() {
        open.set(false);
    }

    @Override
    public boolean isOpen() {
        return open.get();
    }

    @Override
    public String getPortName() {
        return portName;
    }

    @Override
    public int available() {
        if (readLineThrows || byteReadThrows) {
            return 1;
        }
        return (inbox.isEmpty() && byteInbox.isEmpty()) ? 0 : 1;
    }

    @Override
    public String readLine(int timeoutMs) throws IOException {
        if (readLineThrows) {
            throw new IOException("simulated communication loss");
        }
        return inbox.poll();
    }

    @Override
    public int read() throws IOException {
        if (byteReadThrows) {
            throw new IOException("simulated byte-read failure");
        }
        Byte b = byteInbox.poll();
        return b == null ? -1 : (b & 0xFF);
    }

    @Override
    public byte[] readExact(int length, int timeoutMs) throws IOException {
        byte[] data = new byte[length];
        int bytesRead = 0;
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (bytesRead < length && System.currentTimeMillis() < deadline) {
            int b = read();
            if (b < 0) {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while reading scripted bytes");
                }
                continue;
            }
            data[bytesRead++] = (byte) b;
        }
        if (bytesRead < length) {
            throw new IOException(
                    "Timed out reading " + length + " scripted bytes (got " + bytesRead + ")");
        }
        return data;
    }

    @Override
    public void write(int b) {
        // XMODEM handshake bytes ('C', ACK, NAK) are not needed by the scripted peer.
    }

    @Override
    public void write(byte[] data) {
        // Same as above: outbound XMODEM blocks are discarded by the scripted peer.
    }

    @Override
    public void writeLine(String line) {
        written.add(line);
    }

    @Override
    public void clearInputBuffer() {
        // No-op: no real input stream to drain.
    }

    /** Build a single-block XMODEM/CRC stream (SOH frame + EOT) carrying the given payload. */
    static byte[] buildSohFrame(byte[] payload) {
        byte[] block = new byte[128];
        Arrays.fill(block, (byte) 0x1A);
        System.arraycopy(payload, 0, block, 0, payload.length);

        int crc = XModemTransfer.calculateCRC16(block);
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        stream.write(XModemTransfer.SOH);
        stream.write(1);
        stream.write(254);
        stream.writeBytes(block);
        stream.write((crc >> 8) & 0xFF);
        stream.write(crc & 0xFF);
        stream.write(XModemTransfer.EOT);
        return stream.toByteArray();
    }
}
