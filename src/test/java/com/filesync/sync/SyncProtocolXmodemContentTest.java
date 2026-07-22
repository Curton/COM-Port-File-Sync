package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.filesync.protocol.SyncProtocol;
import com.filesync.serial.SerialPortManager;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Tests for the XMODEM content-transfer entry points on {@link SyncProtocol}: {@link
 * SyncProtocol#sendFileContentViaXmodem(byte[], int)} and {@link
 * SyncProtocol#receiveFileContentViaXmodem(int)}.
 *
 * <p>These methods wrap a real {@link com.filesync.serial.XModemTransfer} and use long handshake
 * timeouts, so a full successful transfer is integration-level. Here we cover the entry sequencing
 * and the critical invariant that the {@code xmodemInProgress} flag is always reset (even when the
 * underlying transfer throws), so the manager's listen loop is never permanently gated off.
 */
class SyncProtocolXmodemContentTest {

    @Test
    void sendFileContentViaXmodem_resetsInProgressFlagWhenXmodemFails() throws IOException {
        FailingXmodemSerialPort serial = new FailingXmodemSerialPort();
        // The ACK waited for between the FILE_CONTENT_XFER announcement and the XMODEM send.
        serial.enqueueLine("[[SYNC:ACK]]");
        SyncProtocol protocol = new SyncProtocol(serial);

        byte[] data = "payload".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        IOException thrown =
                assertThrows(
                        IOException.class,
                        () -> protocol.sendFileContentViaXmodem(data, data.length));

        // The error must propagate (from the simulated read failure during handshake), and the
        // announcement command must have been written before the failure.
        assertFalse(
                protocol.isXmodemInProgress(),
                "xmodemInProgress must be reset even when the XMODEM send fails");
        assertTrue(
                serial.getWrittenLines().stream().anyMatch(l -> l.contains("FILE_CONTENT_XFER")),
                "sendFileContentViaXmodem should announce the transfer before sending");
        assertTrue(thrown.getMessage() != null, "Failure should carry a message");
    }

    @Test
    void sendFileContentViaXmodem_announcesSizeAsSoleFirstParameter() throws IOException {
        FailingXmodemSerialPort serial = new FailingXmodemSerialPort();
        // The ACK waited for between the FILE_CONTENT_XFER announcement and the XMODEM send.
        serial.enqueueLine("[[SYNC:ACK]]");
        SyncProtocol protocol = new SyncProtocol(serial);

        // The XMODEM send itself fails on the stub; only the announcement frame matters here.
        assertThrows(
                IOException.class, () -> protocol.sendFileContentViaXmodem(new byte[8], 70000));

        // FileSyncManager.fetchRemoteFileContent parses the size from parameter index 0, so the
        // announcement must carry it as the sole first parameter. A frame like
        // "[[SYNC:FILE_CONTENT_XFER:<path>:70000]]" would break the requester.
        assertTrue(
                serial.getWrittenLines().contains("[[SYNC:FILE_CONTENT_XFER:70000]]"),
                "Announcement must carry the file size as the sole first parameter (index 0)");
    }

    @Test
    void receiveFileContentViaXmodem_resetsInProgressFlagWhenXmodemFails() throws IOException {
        FailingXmodemSerialPort serial = new FailingXmodemSerialPort();
        SyncProtocol protocol = new SyncProtocol(serial);

        IOException thrown =
                assertThrows(IOException.class, () -> protocol.receiveFileContentViaXmodem(64));

        // sendAck() is written (writeLine, overridden), then xmodem.receive attempts the handshake
        // whose first action (write 'C') fails because the stub has no real output stream.
        assertFalse(
                protocol.isXmodemInProgress(),
                "xmodemInProgress must be reset even when the XMODEM receive fails");
        assertTrue(
                serial.getWrittenLines().stream().anyMatch(l -> l.contains("ACK")),
                "receiveFileContentViaXmodem should send an ACK before receiving");
        assertTrue(thrown.getMessage() != null, "Failure should carry a message");
    }

    /**
     * A {@link SerialPortManager} stub with no real streams. {@link #readLine(int)} serves scripted
     * frames; single-byte {@link #read()} throws to fail the XMODEM handshake read; {@link
     * #write(int)} is intentionally <em>not</em> overridden so the base implementation throws
     * because no output stream exists (failing the receive-side handshake write). {@link
     * #available()} always reports data so the XMODEM read path is entered immediately.
     */
    private static final class FailingXmodemSerialPort extends SerialPortManager {
        private final AtomicBoolean open = new AtomicBoolean(true);
        private final ConcurrentLinkedQueue<String> readLineQueue = new ConcurrentLinkedQueue<>();
        private final List<String> written = new CopyOnWriteArrayList<>();

        void enqueueLine(String line) {
            readLineQueue.add(line);
        }

        List<String> getWrittenLines() {
            return written;
        }

        @Override
        public boolean isOpen() {
            return open.get();
        }

        @Override
        public void close() {
            open.set(false);
        }

        @Override
        public String getPortName() {
            return "TEST";
        }

        @Override
        public int available() {
            return 1; // Always "data available" so XMODEM read attempts a single-byte read.
        }

        @Override
        public int read() throws IOException {
            throw new IOException("simulated read failure");
        }

        @Override
        public String readLine(int timeoutMs) throws IOException {
            String s = readLineQueue.poll();
            if (s == null) {
                throw new IOException("no scripted line available");
            }
            return s;
        }

        @Override
        public void writeLine(String line) {
            written.add(line);
        }

        @Override
        public void clearInputBuffer() {
            // No-op: no real input stream to drain.
        }
    }
}
