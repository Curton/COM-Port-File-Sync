package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.filesync.config.SettingsManager;
import com.filesync.serial.XModemTransfer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * Tests for the remote-log fetch flow in {@link FileSyncManager}: the sender-side {@code
 * fetchRemoteLogText} exchange (guards, inline LOG_DATA, XMODEM LOG_XFER, cancel/error/heartbeat/
 * unknown-message handling, timeout, IO failure, interrupt) and the responder-side {@code
 * handleLogRequest} routing (no provider, inline base64, large-log XMODEM announcement).
 *
 * <p>Drives the manager through the shared {@link ScriptedSerialPortManager} exactly like the file
 * content fetch tests: establish a live connection via HEARTBEAT, then answer the {@code LOG_REQ}
 * from a feeder thread once the request has been written (at which point {@code
 * senderBlockingProtocolExchange} is already set and the listen loop cannot steal the response).
 */
class RemoteLogFetchTest {

    private static final long SHORT_TIMEOUT_MS = 200;

    @Test
    void fetchRemoteLogText_returnsNull_whenNotSender() throws Exception {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        FileSyncManager fsm = new FileSyncManager(serial, new SettingsManager(true));
        try {
            fsm.startListening("TEST");
            serial.feedLine("[[SYNC:HEARTBEAT]]");
            waitUntil(fsm::isConnectionAlive, Duration.ofSeconds(5));

            fsm.setIsSender(false);

            assertNull(fsm.fetchRemoteLogText(SHORT_TIMEOUT_MS), "Non-sender must not fetch");
        } finally {
            stopQuietly(fsm);
        }
    }

    @Test
    void fetchRemoteLogText_returnsNull_whenNotConnected() {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        FileSyncManager fsm = new FileSyncManager(serial, new SettingsManager(true));
        // No startListening / no heartbeat: connectionAlive stays false.
        assertNull(fsm.fetchRemoteLogText(SHORT_TIMEOUT_MS), "Disconnected sender must not fetch");
        stopQuietly(fsm);
    }

    @Test
    void fetchRemoteLogText_requestsMarkerBeforeLog() throws Exception {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        FileSyncManager fsm = new FileSyncManager(serial, new SettingsManager(true));
        try {
            startConnected(fsm, serial);
            String encoded =
                    Base64.getEncoder()
                            .encodeToString("with marker".getBytes(StandardCharsets.UTF_8));
            Thread feeder = feederAfterLogReq(serial, "[[SYNC:LOG_DATA:" + encoded + "]]");
            feeder.start();

            String result = fsm.fetchRemoteLogText();
            feeder.join(5_000);

            assertFalse(feeder.isAlive(), "Feeder thread should have completed");
            assertEquals("with marker", result, "The log must still be fetched after the marker");
            List<String> written = serial.getWrittenLines();
            assertTrue(
                    written.contains("[[SYNC:LOG_MARKER_REQ]]"),
                    "Fetch must first ask the peer to log a TIME-SYNC marker");
            assertTrue(
                    written.indexOf("[[SYNC:LOG_MARKER_REQ]]")
                            < written.indexOf("[[SYNC:LOG_REQ]]"),
                    "The marker request must be sent before the LOG_REQ");
        } finally {
            stopQuietly(fsm);
        }
    }

    @Test
    void fetchRemoteLogText_markerExchange_ignoresOtherFrames() throws Exception {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        FileSyncManager fsm = new FileSyncManager(serial, new SettingsManager(true));
        try {
            startConnected(fsm, serial);
            String encoded =
                    Base64.getEncoder()
                            .encodeToString("after-heartbeat".getBytes(StandardCharsets.UTF_8));
            Thread feeder =
                    new Thread(
                            () -> {
                                waitUntil(
                                        () ->
                                                serial.getWrittenLines()
                                                        .contains("[[SYNC:LOG_MARKER_REQ]]"),
                                        Duration.ofSeconds(5));
                                // A frame that is not the marker ACK must be ignored while waiting
                                // for the ACK, then the exchange continues normally.
                                serial.feedLine("[[SYNC:HEARTBEAT]]");
                                serial.feedLine("[[SYNC:ACK]]");
                                waitUntil(
                                        () -> serial.getWrittenLines().contains("[[SYNC:LOG_REQ]]"),
                                        Duration.ofSeconds(5));
                                serial.feedLine("[[SYNC:LOG_DATA:" + encoded + "]]");
                            },
                            "fsm-log-fetch-feeder-marker-other");
            feeder.start();

            String result = fsm.fetchRemoteLogText();
            feeder.join(5_000);

            assertFalse(feeder.isAlive(), "Feeder thread should have completed");
            assertEquals(
                    "after-heartbeat",
                    result,
                    "A non-ACK frame during the marker exchange must not abort the fetch");
        } finally {
            stopQuietly(fsm);
        }
    }

    @Test
    void fetchRemoteLogText_inlineLogData_returnsDecodedText() throws Exception {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        FileSyncManager fsm = new FileSyncManager(serial, new SettingsManager(true));
        try {
            startConnected(fsm, serial);
            String remoteLog = "[10:00:00] hello remote\n[10:00:01] second line\n";
            String encoded =
                    Base64.getEncoder().encodeToString(remoteLog.getBytes(StandardCharsets.UTF_8));
            Thread feeder = feederAfterLogReq(serial, "[[SYNC:LOG_DATA:" + encoded + "]]");
            feeder.start();

            String result = fsm.fetchRemoteLogText();
            feeder.join(5_000);

            assertFalse(feeder.isAlive(), "Feeder thread should have completed");
            assertEquals(remoteLog, result, "Base64 LOG_DATA should be decoded back to text");
            assertTrue(
                    serial.getWrittenLines().contains("[[SYNC:LOG_REQ]]"),
                    "Fetch must send a LOG_REQ command");
        } finally {
            stopQuietly(fsm);
        }
    }

    @Test
    void fetchRemoteLogText_emptyLogData_returnsEmptyString() throws Exception {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        FileSyncManager fsm = new FileSyncManager(serial, new SettingsManager(true));
        try {
            startConnected(fsm, serial);
            Thread feeder = feederAfterLogReq(serial, "[[SYNC:LOG_DATA:]]");
            feeder.start();

            String result = fsm.fetchRemoteLogText();
            feeder.join(5_000);

            assertFalse(feeder.isAlive(), "Feeder thread should have completed");
            assertEquals(
                    "", result, "An empty log (no bytes) must yield an empty string, not null");
        } finally {
            stopQuietly(fsm);
        }
    }

    @Test
    void fetchRemoteLogText_logDataWithoutParam_returnsNull() throws Exception {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        FileSyncManager fsm = new FileSyncManager(serial, new SettingsManager(true));
        try {
            startConnected(fsm, serial);
            Thread feeder = feederAfterLogReq(serial, "[[SYNC:LOG_DATA]]");
            feeder.start();

            String result = fsm.fetchRemoteLogText();
            feeder.join(5_000);

            assertFalse(feeder.isAlive(), "Feeder thread should have completed");
            assertNull(result, "Missing base64 param must be treated as unavailable");
        } finally {
            stopQuietly(fsm);
        }
    }

    @Test
    void fetchRemoteLogText_logXfer_receivesXmodemContent() throws Exception {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        FileSyncManager fsm = new FileSyncManager(serial, new SettingsManager(true));
        try {
            startConnected(fsm, serial);
            byte[] payload = "large remote log via xmodem".getBytes(StandardCharsets.UTF_8);
            Thread feeder =
                    new Thread(
                            () -> {
                                waitUntil(
                                        () ->
                                                serial.getWrittenLines()
                                                        .contains("[[SYNC:LOG_MARKER_REQ]]"),
                                        Duration.ofSeconds(5));
                                serial.feedLine("[[SYNC:ACK]]");
                                waitUntil(
                                        () -> serial.getWrittenLines().contains("[[SYNC:LOG_REQ]]"),
                                        Duration.ofSeconds(5));
                                serial.feedLine("[[SYNC:LOG_XFER:" + payload.length + "]]");
                                serial.feedBytes(ScriptedSerialPortManager.buildSohFrame(payload));
                            },
                            "fsm-log-fetch-feeder-xfer");
            feeder.start();

            String result = fsm.fetchRemoteLogText();
            feeder.join(5_000);

            assertFalse(feeder.isAlive(), "Feeder thread should have completed");
            assertEquals(
                    new String(payload, StandardCharsets.UTF_8),
                    result,
                    "LOG_XFER content must be received via XMODEM and decoded");
        } finally {
            stopQuietly(fsm);
        }
    }

    @Test
    void fetchRemoteLogText_logXferAbortedByRemote_returnsNull() throws Exception {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        FileSyncManager fsm = new FileSyncManager(serial, new SettingsManager(true));
        List<SyncEvent> events = new CopyOnWriteArrayList<>();
        fsm.getEventBus().register(events::add);
        try {
            startConnected(fsm, serial);
            Thread feeder =
                    new Thread(
                            () -> {
                                waitUntil(
                                        () ->
                                                serial.getWrittenLines()
                                                        .contains("[[SYNC:LOG_MARKER_REQ]]"),
                                        Duration.ofSeconds(5));
                                serial.feedLine("[[SYNC:ACK]]");
                                waitUntil(
                                        () -> serial.getWrittenLines().contains("[[SYNC:LOG_REQ]]"),
                                        Duration.ofSeconds(5));
                                serial.feedLine("[[SYNC:LOG_XFER:5]]");
                                // The peer answers the XMODEM receive with CAN, aborting the
                                // transfer instead of sending blocks.
                                serial.feedBytes(new byte[] {XModemTransfer.CAN});
                            },
                            "fsm-log-fetch-feeder-xfer-abort");
            feeder.start();

            String result = fsm.fetchRemoteLogText();
            feeder.join(5_000);

            assertFalse(feeder.isAlive(), "Feeder thread should have completed");
            assertNull(result, "An aborted XMODEM receive must yield null");
            assertTrue(
                    events.stream()
                            .anyMatch(
                                    e ->
                                            e instanceof SyncEvent.ErrorEvent ee
                                                    && ee.getMessage()
                                                            .contains("Failed to fetch remote log:")
                                                    && ee.getMessage()
                                                            .contains(
                                                                    "Transfer cancelled by sender")),
                    "The abort must be reported with the XMODEM cancellation detail");
        } finally {
            stopQuietly(fsm);
        }
    }

    @Test
    void fetchRemoteLogText_cancel_returnsNull() throws Exception {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        FileSyncManager fsm = new FileSyncManager(serial, new SettingsManager(true));
        try {
            startConnected(fsm, serial);
            Thread feeder = feederAfterLogReq(serial, "[[SYNC:CANCEL]]");
            feeder.start();

            String result = fsm.fetchRemoteLogText();
            feeder.join(5_000);

            assertFalse(feeder.isAlive(), "Feeder thread should have completed");
            assertNull(result, "Remote CANCEL must abort the fetch with null");
        } finally {
            stopQuietly(fsm);
        }
    }

    @Test
    void fetchRemoteLogText_error_postsErrorEventAndReturnsNull() throws Exception {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        FileSyncManager fsm = new FileSyncManager(serial, new SettingsManager(true));
        List<SyncEvent> events = new CopyOnWriteArrayList<>();
        fsm.getEventBus().register(events::add);
        try {
            startConnected(fsm, serial);
            Thread feeder = feederAfterLogReq(serial, "[[SYNC:ERROR:remote exploded]]");
            feeder.start();

            String result = fsm.fetchRemoteLogText();
            feeder.join(5_000);

            assertFalse(feeder.isAlive(), "Feeder thread should have completed");
            assertNull(result, "Remote ERROR must abort the fetch with null");
            assertTrue(
                    events.stream()
                            .anyMatch(
                                    e ->
                                            e instanceof SyncEvent.ErrorEvent ee
                                                    && ee.getMessage()
                                                            .contains(
                                                                    "Failed to fetch remote log:"
                                                                            + " Remote error during"
                                                                            + " log request: remote"
                                                                            + " exploded")),
                    "A fetch failure must be reported as an ErrorEvent");
        } finally {
            stopQuietly(fsm);
        }
    }

    @Test
    void fetchRemoteLogText_errorWithoutParam_reportsUnknownAndReturnsNull() throws Exception {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        FileSyncManager fsm = new FileSyncManager(serial, new SettingsManager(true));
        List<SyncEvent> events = new CopyOnWriteArrayList<>();
        fsm.getEventBus().register(events::add);
        try {
            startConnected(fsm, serial);
            Thread feeder = feederAfterLogReq(serial, "[[SYNC:ERROR]]");
            feeder.start();

            String result = fsm.fetchRemoteLogText();
            feeder.join(5_000);

            assertFalse(feeder.isAlive(), "Feeder thread should have completed");
            assertNull(result, "Remote ERROR must abort the fetch with null");
            assertTrue(
                    events.stream()
                            .anyMatch(
                                    e ->
                                            e instanceof SyncEvent.ErrorEvent ee
                                                    && ee.getMessage()
                                                            .contains(
                                                                    "Remote error during log"
                                                                            + " request:"
                                                                            + " unknown")),
                    "A parameter-less ERROR must fall back to the 'unknown' message");
        } finally {
            stopQuietly(fsm);
        }
    }

    @Test
    void fetchRemoteLogText_heartbeat_answersWithAckAndContinues() throws Exception {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        FileSyncManager fsm = new FileSyncManager(serial, new SettingsManager(true));
        try {
            startConnected(fsm, serial);
            String encoded =
                    Base64.getEncoder()
                            .encodeToString("after-heartbeat".getBytes(StandardCharsets.UTF_8));
            Thread feeder =
                    feederAfterLogReq(
                            serial, "[[SYNC:HEARTBEAT]]", "[[SYNC:LOG_DATA:" + encoded + "]]");
            feeder.start();

            String result = fsm.fetchRemoteLogText();
            feeder.join(5_000);

            assertFalse(feeder.isAlive(), "Feeder thread should have completed");
            assertEquals("after-heartbeat", result, "Fetch must survive a mid-exchange HEARTBEAT");
            assertTrue(
                    serial.getWrittenLines().contains("[[SYNC:HEARTBEAT_ACK]]"),
                    "A mid-exchange HEARTBEAT must be acknowledged");
        } finally {
            stopQuietly(fsm);
        }
    }

    @Test
    void fetchRemoteLogText_heartbeatAck_continues() throws Exception {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        FileSyncManager fsm = new FileSyncManager(serial, new SettingsManager(true));
        try {
            startConnected(fsm, serial);
            String encoded =
                    Base64.getEncoder()
                            .encodeToString("after-heartbeat-ack".getBytes(StandardCharsets.UTF_8));
            Thread feeder =
                    feederAfterLogReq(
                            serial, "[[SYNC:HEARTBEAT_ACK]]", "[[SYNC:LOG_DATA:" + encoded + "]]");
            feeder.start();

            String result = fsm.fetchRemoteLogText();
            feeder.join(5_000);

            assertFalse(feeder.isAlive(), "Feeder thread should have completed");
            assertEquals("after-heartbeat-ack", result, "HEARTBEAT_ACK must not abort the fetch");
        } finally {
            stopQuietly(fsm);
        }
    }

    @Test
    void fetchRemoteLogText_unknownMessage_isStashedAndFetchContinues() throws Exception {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        FileSyncManager fsm = new FileSyncManager(serial, new SettingsManager(true));
        try {
            startConnected(fsm, serial);
            String encoded =
                    Base64.getEncoder()
                            .encodeToString("after-unknown".getBytes(StandardCharsets.UTF_8));
            Thread feeder =
                    feederAfterLogReq(
                            serial,
                            "[[SYNC:SHARED_TEXT:stale]]",
                            "[[SYNC:LOG_DATA:" + encoded + "]]");
            feeder.start();

            String result = fsm.fetchRemoteLogText();
            feeder.join(5_000);

            assertFalse(feeder.isAlive(), "Feeder thread should have completed");
            assertEquals("after-unknown", result, "Unknown messages must be stashed, not fatal");
        } finally {
            stopQuietly(fsm);
        }
    }

    @Test
    void fetchRemoteLogText_timesOut_returnsNull() throws Exception {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        FileSyncManager fsm = new FileSyncManager(serial, new SettingsManager(true));
        try {
            startConnected(fsm, serial);
            // No response is ever fed: the short injected timeout must expire.
            assertNull(
                    fsm.fetchRemoteLogText(SHORT_TIMEOUT_MS),
                    "A fetch with no response must time out to null");
        } finally {
            stopQuietly(fsm);
        }
    }

    @Test
    void fetchRemoteLogText_readFailure_postsErrorEventAndReturnsNull() throws Exception {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        FileSyncManager fsm = new FileSyncManager(serial, new SettingsManager(true));
        List<SyncEvent> events = new CopyOnWriteArrayList<>();
        fsm.getEventBus().register(events::add);
        try {
            startConnected(fsm, serial);
            // Stage the IO failure right after the marker request goes out, so the (paused) listen
            // loop has no window to consume it and report a communication loss instead. The marker
            // exchange then degrades to "no marker" and the LOG_REQ fetch hits the failure.
            Thread feeder =
                    new Thread(
                            () -> {
                                waitUntil(
                                        () ->
                                                serial.getWrittenLines()
                                                        .contains("[[SYNC:LOG_MARKER_REQ]]"),
                                        Duration.ofSeconds(5));
                                serial.causeReadLineFailure();
                            },
                            "fsm-log-fetch-feeder-fail");
            feeder.start();

            String result = fsm.fetchRemoteLogText();
            feeder.join(5_000);

            assertFalse(feeder.isAlive(), "Feeder thread should have completed");
            assertNull(result, "An IO failure during the exchange must yield null");
            assertTrue(
                    events.stream()
                            .anyMatch(
                                    e ->
                                            e instanceof SyncEvent.ErrorEvent ee
                                                    && ee.getMessage()
                                                            .contains(
                                                                    "Failed to fetch remote log")),
                    "The IO failure must be reported as an ErrorEvent");
        } finally {
            stopQuietly(fsm);
        }
    }

    @Test
    void fetchRemoteLogText_interrupted_returnsNull() throws Exception {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        FileSyncManager fsm = new FileSyncManager(serial, new SettingsManager(true));
        try {
            startConnected(fsm, serial);
            AtomicReference<String> result = new AtomicReference<>("not-set");
            Thread worker =
                    new Thread(
                            () -> result.set(fsm.fetchRemoteLogText(5_000)),
                            "fsm-log-fetch-worker");
            worker.start();

            waitUntil(
                    () -> serial.getWrittenLines().contains("[[SYNC:LOG_MARKER_REQ]]"),
                    Duration.ofSeconds(5));
            worker.interrupt();
            worker.join(5_000);

            assertFalse(worker.isAlive(), "Worker thread should have completed");
            assertNull(result.get(), "An interrupted fetch must yield null");
        } finally {
            stopQuietly(fsm);
        }
    }

    @Test
    void handleLogMarkerRequest_writesMarkerToSinkAndAcks() throws Exception {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        FileSyncManager fsm = new FileSyncManager(serial, new SettingsManager(true));
        List<String> markers = new CopyOnWriteArrayList<>();
        fsm.setLogMarkerSink(markers::add);
        try {
            fsm.startListening("TEST");
            serial.feedLine("[[SYNC:LOG_MARKER_REQ]]");

            waitUntil(() -> !markers.isEmpty(), Duration.ofSeconds(5));
            waitUntil(
                    () -> serial.getWrittenLines().contains("[[SYNC:ACK]]"), Duration.ofSeconds(5));

            assertTrue(
                    markers.get(0).startsWith("TIME-SYNC"),
                    "The marker request must write a TIME-SYNC line to the sink");
            assertTrue(
                    TimeSyncMarker.parseEpochMs(markers.get(0)) != null,
                    "The sink marker must carry a parseable epoch");
        } finally {
            stopQuietly(fsm);
        }
    }

    @Test
    void handleLogMarkerRequest_withoutSink_postsMarkerEventAndAcks() throws Exception {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        FileSyncManager fsm = new FileSyncManager(serial, new SettingsManager(true));
        List<SyncEvent> events = new CopyOnWriteArrayList<>();
        fsm.getEventBus().register(events::add);
        try {
            fsm.startListening("TEST");
            serial.feedLine("[[SYNC:LOG_MARKER_REQ]]");

            waitUntil(
                    () ->
                            events.stream()
                                    .anyMatch(
                                            e ->
                                                    e instanceof SyncEvent.LogEvent le
                                                            && le.getMessage()
                                                                    .startsWith("TIME-SYNC")),
                    Duration.ofSeconds(5));
            waitUntil(
                    () -> serial.getWrittenLines().contains("[[SYNC:ACK]]"), Duration.ofSeconds(5));
        } finally {
            stopQuietly(fsm);
        }
    }

    @Test
    void handleLogRequest_withoutProvider_sendsEmptyLogData() throws Exception {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        FileSyncManager fsm = new FileSyncManager(serial, new SettingsManager(true));
        try {
            fsm.startListening("TEST");
            serial.feedLine("[[SYNC:LOG_REQ]]");

            waitUntil(
                    () -> serial.getWrittenLines().contains("[[SYNC:LOG_DATA:]]"),
                    Duration.ofSeconds(5));
        } finally {
            stopQuietly(fsm);
        }
    }

    @Test
    void handleLogRequest_providerReturnsNull_sendsEmptyLogData() throws Exception {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        FileSyncManager fsm = new FileSyncManager(serial, new SettingsManager(true));
        fsm.setLogTextProvider(() -> null);
        try {
            fsm.startListening("TEST");
            serial.feedLine("[[SYNC:LOG_REQ]]");

            waitUntil(
                    () -> serial.getWrittenLines().contains("[[SYNC:LOG_DATA:]]"),
                    Duration.ofSeconds(5));
        } finally {
            stopQuietly(fsm);
        }
    }

    @Test
    void handleLogRequest_smallLog_sendsBase64Inline() throws Exception {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        FileSyncManager fsm = new FileSyncManager(serial, new SettingsManager(true));
        fsm.setLogTextProvider(() -> "small log text");
        try {
            fsm.startListening("TEST");
            serial.feedLine("[[SYNC:LOG_REQ]]");

            String expectedB64 =
                    Base64.getEncoder()
                            .encodeToString("small log text".getBytes(StandardCharsets.UTF_8));
            waitUntil(
                    () ->
                            serial.getWrittenLines()
                                    .contains("[[SYNC:LOG_DATA:" + expectedB64 + "]]"),
                    Duration.ofSeconds(5));
        } finally {
            stopQuietly(fsm);
        }
    }

    @Test
    void handleLogRequest_largeLog_successfulTransfer() throws Exception {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        FileSyncManager fsm = new FileSyncManager(serial, new SettingsManager(true));
        fsm.setLogTextProvider(() -> "x".repeat(64 * 1024 + 1));
        List<SyncEvent> events = new CopyOnWriteArrayList<>();
        fsm.getEventBus().register(events::add);
        try {
            fsm.startListening("TEST");
            serial.feedLine("[[SYNC:LOG_REQ]]");
            // Scripted peer: ACK the LOG_XFER announcement, then handshake with 'C' and ACK every
            // block plus the EOT. A 64 KiB + 1 log splits into 17 blocks (16 x 4K + one SOH tail);
            // each block needs one ACK for its pre-write stale-char drain and one as the response,
            // so 1 handshake 'C' + 36 ACKs completes the whole transfer.
            serial.feedLine("[[SYNC:ACK]]");
            byte[] bytes = new byte[37];
            bytes[0] = XModemTransfer.C;
            Arrays.fill(bytes, 1, bytes.length, XModemTransfer.ACK);
            serial.feedBytes(bytes);

            waitUntil(
                    () ->
                            serial.getWrittenLines()
                                    .contains("[[SYNC:LOG_XFER:" + (64 * 1024 + 1) + "]]"),
                    Duration.ofSeconds(5));
            Thread.sleep(500);

            assertTrue(
                    events.stream()
                            .noneMatch(
                                    e ->
                                            e instanceof SyncEvent.LogEvent le
                                                    && le.getMessage()
                                                            .contains("Failed to send log")),
                    "A fully acknowledged XMODEM send must not report a failure");
        } finally {
            stopQuietly(fsm);
        }
    }

    @Test
    void handleLogRequest_largeLog_announcesXmodemThenReportsFailure() throws Exception {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        FileSyncManager fsm = new FileSyncManager(serial, new SettingsManager(true));
        fsm.setLogTextProvider(() -> "x".repeat(64 * 1024 + 1));
        List<SyncEvent> events = new CopyOnWriteArrayList<>();
        fsm.getEventBus().register(events::add);
        try {
            fsm.startListening("TEST");
            serial.feedLine("[[SYNC:LOG_REQ]]");

            // Logs above XMODEM_CONTENT_THRESHOLD are announced with the exact size, mirroring the
            // large-file content path.
            waitUntil(
                    () ->
                            serial.getWrittenLines()
                                    .contains("[[SYNC:LOG_XFER:" + (64 * 1024 + 1) + "]]"),
                    Duration.ofSeconds(5));

            // Unwind the in-flight XMODEM send: ACK the announcement, then fail the byte-level
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
                                                                            "Failed to send log")),
                    Duration.ofSeconds(5));
        } finally {
            stopQuietly(fsm);
        }
    }

    /** Connect (HEARTBEAT) and wait until the manager reports the connection alive. */
    private static void startConnected(FileSyncManager fsm, ScriptedSerialPortManager serial)
            throws Exception {
        fsm.startListening("TEST");
        serial.feedLine("[[SYNC:HEARTBEAT]]");
        waitUntil(fsm::isConnectionAlive, Duration.ofSeconds(5));
    }

    /**
     * Builds a feeder thread that waits for the LOG_REQ to be written (proving the blocking
     * exchange is active, so the listen loop cannot steal the responses), then feeds the given
     * frames in order.
     */
    private static Thread feederAfterLogReq(ScriptedSerialPortManager serial, String... frames) {
        return new Thread(
                () -> {
                    // ACK the marker request first (fetch asks the peer to log a TIME-SYNC marker
                    // before the LOG_REQ), then feed the response frames once LOG_REQ is written.
                    waitUntil(
                            () -> serial.getWrittenLines().contains("[[SYNC:LOG_MARKER_REQ]]"),
                            Duration.ofSeconds(5));
                    serial.feedLine("[[SYNC:ACK]]");
                    waitUntil(
                            () -> serial.getWrittenLines().contains("[[SYNC:LOG_REQ]]"),
                            Duration.ofSeconds(5));
                    for (String frame : frames) {
                        serial.feedLine(frame);
                    }
                },
                "fsm-log-fetch-feeder");
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
}
