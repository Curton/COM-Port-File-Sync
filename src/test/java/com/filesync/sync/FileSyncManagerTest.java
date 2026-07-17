package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.filesync.config.SettingsManager;
import com.filesync.protocol.SyncProtocol;
import com.filesync.serial.SerialPortManager;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
 *   <li>Outgoing {@code fetchRemoteFileContent} decodes a base64 content response (small-file
 *       path).
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
     */
    private static final class ScriptedSerialPortManager extends SerialPortManager {
        // The manager's listen loop idles while the port is closed, and startListening() does not
        // open the port itself (production opens it first). Default to open so the loop runs.
        private final AtomicBoolean open = new AtomicBoolean(true);
        private volatile String portName;
        private final ConcurrentLinkedQueue<String> inbox = new ConcurrentLinkedQueue<>();
        private final List<String> written = new CopyOnWriteArrayList<>();
        private volatile boolean readLineThrows;

        void feedLine(String frame) {
            inbox.add(frame);
        }

        void causeReadLineFailure() {
            readLineThrows = true;
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
            return readLineThrows ? 1 : (inbox.isEmpty() ? 0 : 1);
        }

        @Override
        public String readLine(int timeoutMs) throws IOException {
            if (readLineThrows) {
                throw new IOException("simulated communication loss");
            }
            return inbox.poll();
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
