package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.filesync.config.SettingsManager;
import com.filesync.protocol.SyncProtocol;
import com.filesync.serial.SerialPortManager;
import com.filesync.serial.XModemTransfer;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link FileSyncManager} connection-lifecycle and file-content routing behavior that is
 * otherwise unreachable by the service-level tests (which bypass the manager and exercise the
 * underlying services directly).
 *
 * <p>These tests drive the manager through a {@link ScriptedSerialPortManager} that feeds scripted
 * protocol frames into the listen loop and captures outbound writes. They cover:
 *
 * <ul>
 *   <li>The remote-initiated disconnect policy (a DISCONNECT message must tear down without
 *       scheduling an automatic reconnect) — regression guard for the remote-disconnect fix.
 *   <li>The contrasting behavior that an ordinary communication loss (read failure) DOES schedule a
 *       reconnect.
 *   <li>Manual disconnect sends a notification and tears down without reconnecting.
 *   <li>Incoming {@code CMD_FILE_CONTENT_REQ} for a small file is answered with base64 content.
 *   <li>Incoming {@code CMD_FILE_CONTENT_REQ} for a large file (&gt; 64 KB) is answered with a
 *       {@code FILE_CONTENT_XFER} announcement carrying the exact size as its sole parameter.
 *   <li>Outgoing {@code fetchRemoteFileContent} decodes a base64 content response (small-file
 *       path).
 *   <li>Outgoing {@code fetchRemoteFileContent} parses the size from parameter index 0 of a {@code
 *       FILE_CONTENT_XFER} announcement and receives the content via XMODEM — regression test for
 *       the parameter-index off-by-one that broke large-file conflict resolution.
 * </ul>
 */
class FileSyncManagerTest {

    @TempDir Path tempDir;

    @Test
    void remoteInitiatedDisconnect_tearsDownWithoutAutoReconnect() throws Exception {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        FileSyncManager fsm = new FileSyncManager(serial, new SettingsManager(true));
        List<SyncEvent> events = new CopyOnWriteArrayList<>();
        fsm.getEventBus().register(events::add);
        try {
            fsm.startListening("TEST");

            // Establish a live connection so a subsequent loss can trigger the lost callback.
            serial.feedLine("[[SYNC:HEARTBEAT]]");
            waitUntil(fsm::isConnectionAlive, Duration.ofSeconds(5));

            // Remote side intentionally closes the link.
            serial.feedLine("[[SYNC:DISCONNECT]]");

            // markLost posts the reason log after onConnectionLost runs (which tears down).
            waitUntil(
                    () ->
                            events.stream()
                                    .anyMatch(
                                            e ->
                                                    e instanceof SyncEvent.LogEvent le
                                                            && le.getMessage()
                                                                    .contains(
                                                                            "Connection closed by remote")),
                    Duration.ofSeconds(10));

            assertTrue(
                    events.stream()
                            .anyMatch(
                                    e ->
                                            e instanceof SyncEvent.ConnectionEvent ce
                                                    && !ce.isConnected()),
                    "A disconnected ConnectionEvent should be posted");
            assertFalse(fsm.isRunning(), "Listen loop should be stopped after remote disconnect");
            assertFalse(
                    fsm.isReconnectInProgress(),
                    "Remote-initiated disconnect must NOT schedule an auto-reconnect");
            assertFalse(
                    events.stream()
                            .anyMatch(
                                    e ->
                                            e instanceof SyncEvent.LogEvent le
                                                    && le.getMessage()
                                                            .toLowerCase()
                                                            .contains("reconnect")),
                    "No reconnect log should be emitted for a remote-initiated disconnect");
        } finally {
            stopQuietly(fsm);
        }
    }

    @Test
    void communicationLoss_schedulesAutoReconnect() throws Exception {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        FileSyncManager fsm = new FileSyncManager(serial, new SettingsManager(true));
        List<SyncEvent> events = new CopyOnWriteArrayList<>();
        fsm.getEventBus().register(events::add);
        try {
            fsm.startListening("TEST");

            serial.feedLine("[[SYNC:HEARTBEAT]]");
            waitUntil(fsm::isConnectionAlive, Duration.ofSeconds(5));

            // Simulate a read failure (not a graceful disconnect): the listen loop catches the
            // IOException and reports a communication failure, which must schedule a reconnect.
            serial.causeReadLineFailure();

            waitUntil(
                    () ->
                            events.stream()
                                    .anyMatch(
                                            e ->
                                                    e instanceof SyncEvent.LogEvent le
                                                            && le.getMessage()
                                                                    .contains(
                                                                            "Will try to reconnect")),
                    Duration.ofSeconds(10));

            assertTrue(
                    fsm.isReconnectInProgress(),
                    "An ordinary communication loss should schedule an auto-reconnect");
        } finally {
            // Cancel the pending reconnect task so it cannot fire after the test.
            stopQuietly(fsm);
        }
    }

    @Test
    void manualDisconnect_sendsNotificationAndTearsDownWithoutReconnect() throws Exception {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        FileSyncManager fsm = new FileSyncManager(serial, new SettingsManager(true));
        try {
            fsm.startListening("TEST");

            serial.feedLine("[[SYNC:HEARTBEAT]]");
            waitUntil(fsm::isConnectionAlive, Duration.ofSeconds(5));

            fsm.disconnect(true); // notifyRemote = true

            assertFalse(fsm.isRunning(), "Listen loop should be stopped after manual disconnect");
            assertFalse(
                    fsm.isReconnectInProgress(), "Manual disconnect must not schedule a reconnect");
            assertTrue(
                    serial.getWrittenLines().stream()
                            .anyMatch(line -> line.equals("[[SYNC:DISCONNECT]]")),
                    "Manual disconnect with notifyRemote should send a DISCONNECT notification");
        } finally {
            stopQuietly(fsm);
        }
    }

    @Test
    void incomingFileContentRequest_smallFile_respondsWithBase64Content() throws Exception {
        File folder = tempDir.resolve("content").toFile();
        folder.mkdirs();
        File doc = new File(folder, "doc.txt");
        Files.writeString(doc.toPath(), "hello");

        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        FileSyncManager fsm = new FileSyncManager(serial, new SettingsManager(true));
        fsm.setSyncFolder(folder);
        try {
            fsm.startListening("TEST");

            String encodedPath = SyncProtocol.encodePathForProtocol("doc.txt");
            serial.feedLine("[[SYNC:FILE_CONTENT_REQ:" + encodedPath + "]]");

            String expectedContentB64 =
                    Base64.getEncoder().encodeToString("hello".getBytes(StandardCharsets.UTF_8));
            waitUntil(
                    () ->
                            serial.getWrittenLines().stream()
                                    .anyMatch(line -> line.contains("FILE_CONTENT_DATA")),
                    Duration.ofSeconds(5));

            String dataLine =
                    serial.getWrittenLines().stream()
                            .filter(line -> line.contains("FILE_CONTENT_DATA"))
                            .findFirst()
                            .orElseThrow();
            assertTrue(
                    dataLine.contains(expectedContentB64),
                    "Response should carry the base64-encoded file content");
        } finally {
            stopQuietly(fsm);
        }
    }

    @Test
    void fetchRemoteFileContent_smallFileResponse_returnsDecodedBytes() throws Exception {
        File folder = tempDir.resolve("root").toFile();
        folder.mkdirs();

        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        FileSyncManager fsm = new FileSyncManager(serial, new SettingsManager(true));
        fsm.setSyncFolder(folder);
        try {
            fsm.startListening("TEST");

            serial.feedLine("[[SYNC:HEARTBEAT]]");
            waitUntil(fsm::isConnectionAlive, Duration.ofSeconds(5));

            byte[] expected = "remote-content".getBytes(StandardCharsets.UTF_8);
            String encodedPath = SyncProtocol.encodePathForProtocol("remote.txt");
            String encodedContent = Base64.getEncoder().encodeToString(expected);

            // fetchRemoteFileContent runs synchronously on this thread and waits for a response.
            // Feed the response from a helper thread once the request has been sent (at which point
            // senderBlockingProtocolExchange is already set, so the listen loop cannot steal it).
            Thread feeder =
                    new Thread(
                            () -> {
                                waitUntil(
                                        () ->
                                                serial.getWrittenLines().stream()
                                                        .anyMatch(
                                                                l ->
                                                                        l.contains(
                                                                                "FILE_CONTENT_REQ")),
                                        Duration.ofSeconds(5));
                                serial.feedLine(
                                        "[[SYNC:FILE_CONTENT_DATA:"
                                                + encodedPath
                                                + ":"
                                                + encodedContent
                                                + "]]");
                            },
                            "fsm-test-feeder");
            feeder.start();

            byte[] result = fsm.fetchRemoteFileContent("remote.txt");
            feeder.join(5_000);

            assertFalse(feeder.isAlive(), "Feeder thread should have completed");
            assertTrue(result != null, "fetchRemoteFileContent should return decoded content");
            assertTrue(
                    java.util.Arrays.equals(result, expected),
                    "Decoded remote content should match the scripted response");
        } finally {
            stopQuietly(fsm);
        }
    }

    @Test
    void incomingFileContentRequest_largeFile_announcesXmodemTransferWithExactSize()
            throws Exception {
        File folder = tempDir.resolve("large-content").toFile();
        folder.mkdirs();
        // Just above XMODEM_CONTENT_THRESHOLD (64 KB) so the responder picks the XMODEM path.
        byte[] bigContent = new byte[64 * 1024 + 1];
        File big = new File(folder, "big.bin");
        Files.write(big.toPath(), bigContent);

        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        FileSyncManager fsm = new FileSyncManager(serial, new SettingsManager(true));
        fsm.setSyncFolder(folder);
        List<SyncEvent> events = new CopyOnWriteArrayList<>();
        fsm.getEventBus().register(events::add);
        try {
            fsm.startListening("TEST");

            String encodedPath = SyncProtocol.encodePathForProtocol("big.bin");
            serial.feedLine("[[SYNC:FILE_CONTENT_REQ:" + encodedPath + "]]");

            waitUntil(
                    () ->
                            serial.getWrittenLines().stream()
                                    .anyMatch(line -> line.contains("FILE_CONTENT_XFER")),
                    Duration.ofSeconds(5));

            // The requester (fetchRemoteFileContent) parses the size from parameter index 0, so
            // the announcement must carry it as the sole first parameter.
            assertTrue(
                    serial.getWrittenLines()
                            .contains("[[SYNC:FILE_CONTENT_XFER:" + bigContent.length + "]]"),
                    "Large-file response must announce FILE_CONTENT_XFER with the exact size as"
                            + " the sole first parameter");

            // Let the responder unwind: ACK the announcement, then fail the byte-level XMODEM
            // handshake read so the listen loop is not stuck in send retries during teardown.
            serial.failByteReads();
            serial.feedLine("[[SYNC:ACK]]");
            waitUntil(
                    () ->
                            events.stream()
                                    .anyMatch(
                                            e ->
                                                    e instanceof SyncEvent.LogEvent le
                                                            && le.getMessage()
                                                                    .contains(
                                                                            "Failed to send file content")),
                    Duration.ofSeconds(5));
        } finally {
            stopQuietly(fsm);
        }
    }

    @Test
    void fetchRemoteFileContent_xmodemXferResponse_parsesSizeFromFirstParamAndReceivesBytes()
            throws Exception {
        File folder = tempDir.resolve("root-xfer").toFile();
        folder.mkdirs();

        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        FileSyncManager fsm = new FileSyncManager(serial, new SettingsManager(true));
        fsm.setSyncFolder(folder);
        try {
            fsm.startListening("TEST");

            serial.feedLine("[[SYNC:HEARTBEAT]]");
            waitUntil(fsm::isConnectionAlive, Duration.ofSeconds(5));

            byte[] expected = new byte[100];
            for (int i = 0; i < expected.length; i++) {
                expected[i] = (byte) i;
            }

            // Mirror the responder's sendFileContentViaXmodem: the announcement carries the size
            // as the sole first parameter (index 0), followed by the raw XMODEM byte stream.
            Thread feeder =
                    new Thread(
                            () -> {
                                waitUntil(
                                        () ->
                                                serial.getWrittenLines().stream()
                                                        .anyMatch(
                                                                l ->
                                                                        l.contains(
                                                                                "FILE_CONTENT_REQ")),
                                        Duration.ofSeconds(5));
                                serial.feedLine(
                                        "[[SYNC:FILE_CONTENT_XFER:" + expected.length + "]]");
                                serial.feedBytes(buildSohFrame(expected));
                            },
                            "fsm-test-feeder-xfer");
            feeder.start();

            byte[] result = fsm.fetchRemoteFileContent("big.bin");
            feeder.join(5_000);

            assertFalse(feeder.isAlive(), "Feeder thread should have completed");
            assertTrue(result != null, "fetchRemoteFileContent should return XMODEM content");
            assertTrue(
                    Arrays.equals(result, expected),
                    "Bytes received via XMODEM should match the scripted payload");
        } finally {
            stopQuietly(fsm);
        }
    }

    /** Build a single-block XMODEM/CRC stream (SOH frame + EOT) carrying the given payload. */
    private static byte[] buildSohFrame(byte[] payload) {
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

    private static void waitUntil(BooleanSupplier condition, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        try {
            while (System.currentTimeMillis() < deadline) {
                if (condition.getAsBoolean()) {
                    return;
                }
                Thread.sleep(20);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        throw new AssertionError("Timed out waiting for condition");
    }

    private static void stopQuietly(FileSyncManager fsm) {
        try {
            fsm.disconnect(false);
        } catch (RuntimeException ignored) {
            // Best-effort teardown for test cleanup.
        }
    }

    /**
     * A {@link SerialPortManager} that never touches real hardware: it serves scripted protocol
     * frames from a queue for {@link #readLine(int)}, captures outbound {@link #writeLine(String)}
     * calls, and can be flipped into a mode where {@link #readLine(int)} throws to simulate a
     * communication failure. {@link #available()} reflects whether a frame is queued (or whether a
     * failure is staged) so the manager's listen-loop {@code hasData()} gating works.
     *
     * <p>A separate byte-level inbox backs {@link #read()} and {@link #readExact(int, int)} so
     * XMODEM transfers can be scripted alongside control frames; {@link #failByteReads()} flips the
     * byte-read path into a failure mode to unwind an in-progress XMODEM send quickly.
     */
    private static final class ScriptedSerialPortManager extends SerialPortManager {
        // The manager's listen loop idles while the port is closed, and startListening() does not
        // open the port itself (production opens it first). Default to open so the loop runs.
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
    }
}
