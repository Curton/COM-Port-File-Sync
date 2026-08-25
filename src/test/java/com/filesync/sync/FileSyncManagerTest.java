package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.filesync.config.SettingsManager;
import com.filesync.protocol.BatchTransferSession;
import com.filesync.protocol.SyncProtocol;
import com.filesync.serial.XModemTransfer;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
                                serial.feedBytes(ScriptedSerialPortManager.buildSohFrame(expected));
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

    @Test
    void incomingFileContentRequest_largeFile_xmodemSend_postsSyncControlRefresh()
            throws Exception {
        File folder = tempDir.resolve("large-content-refresh").toFile();
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

            // Scripted peer: request the large file, ACK the FILE_CONTENT_XFER announcement, then
            // play the XMODEM receiver role — 'C' handshake plus a generous surplus of ACKs so
            // every 4K/128-byte block (and the final EOT) is acknowledged.
            String encodedPath = SyncProtocol.encodePathForProtocol("big.bin");
            serial.feedLine("[[SYNC:FILE_CONTENT_REQ:" + encodedPath + "]]");
            serial.feedLine("[[SYNC:ACK]]");
            byte[] peerBytes = new byte[1 + 60];
            peerBytes[0] = XModemTransfer.C;
            Arrays.fill(peerBytes, 1, peerBytes.length, XModemTransfer.ACK);
            serial.feedBytes(peerBytes);

            // The refresh event is the regression guard: XMODEM progress events disable the sync
            // controls while the transfer is in flight, and the refresh must restore them once it
            // completes (fix for buttons staying gray after cancelling conflict resolution).
            waitUntil(
                    () ->
                            events.stream()
                                    .anyMatch(e -> e instanceof SyncEvent.SyncControlRefreshEvent),
                    Duration.ofSeconds(10));

            assertTrue(
                    serial.getWrittenLines()
                            .contains("[[SYNC:FILE_CONTENT_XFER:" + bigContent.length + "]]"),
                    "Large-file response must announce FILE_CONTENT_XFER with the exact size as"
                            + " the sole first parameter");
            assertFalse(
                    fsm.isTransferBusy(),
                    "isTransferBusy must be false after the XMODEM content transfer completes");
        } finally {
            stopQuietly(fsm);
        }
    }

    @Test
    void fetchRemoteFileContent_xmodemXferResponse_postsSyncControlRefresh() throws Exception {
        File folder = tempDir.resolve("root-xfer-refresh").toFile();
        folder.mkdirs();

        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        FileSyncManager fsm = new FileSyncManager(serial, new SettingsManager(true));
        fsm.setSyncFolder(folder);
        List<SyncEvent> events = new CopyOnWriteArrayList<>();
        fsm.getEventBus().register(events::add);
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
                                serial.feedBytes(ScriptedSerialPortManager.buildSohFrame(expected));
                            },
                            "fsm-test-feeder-xfer-refresh");
            feeder.start();

            byte[] result = fsm.fetchRemoteFileContent("big.bin");
            feeder.join(5_000);

            assertFalse(feeder.isAlive(), "Feeder thread should have completed");
            assertTrue(result != null, "fetchRemoteFileContent should return XMODEM content");
            assertTrue(
                    Arrays.equals(result, expected),
                    "Bytes received via XMODEM should match the scripted payload");
            assertTrue(
                    events.stream().anyMatch(e -> e instanceof SyncEvent.SyncControlRefreshEvent),
                    "A SyncControlRefreshEvent must be posted after the XMODEM content transfer"
                            + " completes");
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

    // ========== Locked received files (pending writes) ==========

    @Test
    void batchWithLockedFile_isQueuedForUserDecision_transfersContinue_retryAndSkipAllWork()
            throws Exception {
        File syncFolder = tempDir.resolve("sync").toFile();
        syncFolder.mkdirs();

        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        FileSyncManager fsm = new FileSyncManager(serial, new SettingsManager(true));
        fsm.setSyncFolder(syncFolder);
        List<SyncEvent> events = new CopyOnWriteArrayList<>();
        fsm.getEventBus().register(events::add);
        try {
            fsm.startListening("TEST");

            // Batch 1: "a.txt" is locked (a directory blocks the write), "b.txt" is writable.
            new File(syncFolder, "a.txt").mkdirs();
            byte[] batch1 =
                    buildBatch(
                            new String[] {"a.txt", "b.txt"},
                            new String[] {"A content", "B content"});
            serial.feedLine("[[SYNC:BATCH_DATA:" + batch1.length + "]]");
            serial.feedBytes(ScriptedSerialPortManager.buildSohFrame(batch1));

            waitUntil(
                    () ->
                            events.stream()
                                    .anyMatch(
                                            e ->
                                                    e instanceof SyncEvent.LogEvent le
                                                            && le.getMessage()
                                                                    .contains(
                                                                            "waiting for user decision")),
                    Duration.ofSeconds(10));

            assertTrue(
                    events.stream()
                            .noneMatch(
                                    e ->
                                            e instanceof SyncEvent.ErrorEvent ee
                                                    && ee.getMessage()
                                                            .contains("Batch receive failed")),
                    "A locked file must not abort the batch with 'Batch receive failed'");
            SyncEvent.PendingWriteEvent pendingEvent =
                    events.stream()
                            .filter(e -> e instanceof SyncEvent.PendingWriteEvent)
                            .map(e -> (SyncEvent.PendingWriteEvent) e)
                            .findFirst()
                            .orElseThrow();
            assertTrue(
                    pendingEvent.getPendingPaths().contains("a.txt"),
                    "The locked file must be announced to the UI for a user decision");
            assertTrue(
                    new File(syncFolder, "b.txt").isFile(),
                    "Other files in the same batch must still be written");
            assertEquals("B content", Files.readString(new File(syncFolder, "b.txt").toPath()));

            // A subsequent batch must still be received and written normally (transfer not
            // stalled).
            byte[] batch2 = buildBatch(new String[] {"c.txt"}, new String[] {"C content"});
            serial.feedLine("[[SYNC:BATCH_DATA:" + batch2.length + "]]");
            serial.feedBytes(ScriptedSerialPortManager.buildSohFrame(batch2));
            waitUntil(() -> new File(syncFolder, "c.txt").isFile(), Duration.ofSeconds(10));
            assertEquals("C content", Files.readString(new File(syncFolder, "c.txt").toPath()));

            // User releases the lock (closes the program) and clicks Retry -> the file is written.
            new File(syncFolder, "a.txt").delete();
            fsm.retryPendingWrites(List.of("a.txt"));
            waitUntil(() -> new File(syncFolder, "a.txt").isFile(), Duration.ofSeconds(10));
            assertEquals("A content", Files.readString(new File(syncFolder, "a.txt").toPath()));

            // Batch 3: "d.txt" is locked again; "Skip All" clears the queue.
            new File(syncFolder, "d.txt").mkdirs();
            byte[] batch3 = buildBatch(new String[] {"d.txt"}, new String[] {"D content"});
            serial.feedLine("[[SYNC:BATCH_DATA:" + batch3.length + "]]");
            serial.feedBytes(ScriptedSerialPortManager.buildSohFrame(batch3));
            waitUntil(
                    () ->
                            events.stream()
                                    .anyMatch(
                                            e ->
                                                    e instanceof SyncEvent.PendingWriteEvent pe
                                                            && pe.getPendingPaths()
                                                                    .contains("d.txt")),
                    Duration.ofSeconds(10));

            fsm.skipAllPendingWrites();
            waitUntil(
                    () ->
                            events.stream()
                                    .anyMatch(
                                            e ->
                                                    e instanceof SyncEvent.PendingWriteEvent pe
                                                            && pe.getPendingPaths().isEmpty()),
                    Duration.ofSeconds(10));
            assertFalse(
                    new File(syncFolder, "d.txt").isFile(),
                    "A skipped file must not be written (it will be re-synced later)");
        } finally {
            stopQuietly(fsm);
        }
    }

    @Test
    void incomingDeltaSigRequest_respondsWithSignatureData() throws Exception {
        File folder = tempDir.resolve("sig").toFile();
        folder.mkdirs();
        Files.writeString(new File(folder, "doc.txt").toPath(), "hello");

        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        FileSyncManager fsm = new FileSyncManager(serial, new SettingsManager(true));
        fsm.setSyncFolder(folder);
        try {
            fsm.startListening("TEST");
            serial.feedLine("[[SYNC:HEARTBEAT]]");
            waitUntil(fsm::isConnectionAlive, Duration.ofSeconds(5));

            // Manager computes signatures and announces DELTA_SIG_DATA, then waits for an ACK and
            // the XMODEM send handshake. The ACK is fed (read by the handler), not written.
            serial.feedLine("[[SYNC:DELTA_SIG_REQ:doc.txt]]");
            waitUntil(
                    () ->
                            serial.getWrittenLines().stream()
                                    .anyMatch(l -> l.contains("DELTA_SIG_DATA")),
                    Duration.ofSeconds(5));
            serial.feedLine("[[SYNC:ACK]]");
            serial.feedBytes(
                    new byte[] {
                        XModemTransfer.C,
                        XModemTransfer.ACK,
                        XModemTransfer.ACK,
                        XModemTransfer.ACK,
                        XModemTransfer.ACK
                    });
            assertTrue(
                    serial.getWrittenLines().stream().anyMatch(l -> l.contains("DELTA_SIG_DATA")),
                    "DELTA_SIG_REQ must be routed to the signature handler");
        } finally {
            stopQuietly(fsm);
        }
    }

    @Test
    void incomingFileDelta_reconstructsAndWritesFile() throws Exception {
        File folder = tempDir.resolve("delta").toFile();
        folder.mkdirs();
        File doc = new File(folder, "doc.txt");
        Files.write(doc.toPath(), "hello".getBytes(StandardCharsets.UTF_8));

        byte[] source = "world".getBytes(StandardCharsets.UTF_8);
        // All-literal delta (no matchable blocks): header + LITERAL("world").
        byte[] delta =
                com.filesync.delta.DeltaEncoder.encode(
                        source,
                        new com.filesync.delta.FileSignatures(
                                "doc.txt", 64, 0, source.length, List.of()));
        String sourceMd5 = com.filesync.delta.HashUtil.md5Hex(source);

        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        FileSyncManager fsm = new FileSyncManager(serial, new SettingsManager(true));
        fsm.setSyncFolder(folder);
        try {
            fsm.startListening("TEST");
            serial.feedLine("[[SYNC:HEARTBEAT]]");
            waitUntil(fsm::isConnectionAlive, Duration.ofSeconds(5));

            // sendFileDelta sends the path raw (escaped, not base64), so the frame uses the raw
            // path.
            serial.feedLine(
                    "[[SYNC:FILE_DELTA:doc.txt:" + delta.length + ":false:0:5:" + sourceMd5 + "]]");
            // The receiver ACKs the announcement, then receives the delta via XMODEM.
            serial.feedBytes(ScriptedSerialPortManager.buildSohFrame(delta));

            waitUntil(
                    () -> serial.getWrittenLines().stream().anyMatch(l -> l.equals("[[SYNC:ACK]]")),
                    Duration.ofSeconds(5));
            // base was "hello"; only a routed+reconstructed delta can turn it into "world".
            assertEquals(
                    "world",
                    Files.readString(doc.toPath()),
                    "FILE_DELTA must be routed and the file reconstructed");
        } finally {
            stopQuietly(fsm);
        }
    }

    /** Build a single-frame batch from (relativePath, content) pairs, keeping the pair order. */
    private static byte[] buildBatch(String[] paths, String[] contents) throws Exception {
        List<Object[]> files = new ArrayList<>();
        for (int i = 0; i < paths.length; i++) {
            File staging = File.createTempFile("batch-src-", ".txt");
            Files.writeString(staging.toPath(), contents[i]);
            files.add(new Object[] {staging, paths[i]});
        }
        return BatchTransferSession.buildBatch(files, 65536);
    }

    private static void stopQuietly(FileSyncManager fsm) {
        try {
            fsm.disconnect(false);
        } catch (RuntimeException ignored) {
            // Best-effort teardown for test cleanup.
        }
    }
}
