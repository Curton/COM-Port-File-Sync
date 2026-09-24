package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.filesync.delta.BlockSignature;
import com.filesync.delta.FileSignatures;
import com.filesync.delta.SignatureSet;
import com.filesync.protocol.SyncProtocol;
import com.filesync.protocol.TransferCancelledException;
import com.filesync.serial.XModemTransfer;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real-loop tests for the delta-sync protocol entry points on {@link SyncProtocol} (sendFileDelta,
 * sendDeltaSignatures, requestDeltaSignatures) using the scripted serial port. {@code
 * receiveFileDelta} is covered by {@link DeltaProtocolTest}; these cover the sender-side send paths
 * and the signature round-trip that the coordinator-level tests only mock.
 */
class DeltaSyncProtocolTest {

    private static final byte[] XMODEM_SEND_HANDSHAKE =
            new byte[] {
                XModemTransfer.C,
                XModemTransfer.ACK,
                XModemTransfer.ACK,
                XModemTransfer.ACK,
                XModemTransfer.ACK
            };

    private SignatureSet sampleSet() {
        return new SignatureSet(
                List.of(
                        new FileSignatures(
                                "a.bin",
                                64,
                                1,
                                64,
                                List.of(
                                        new BlockSignature(
                                                0,
                                                123,
                                                new byte[BlockSignature.STRONG_HASH_LENGTH])))));
    }

    @Test
    void sendFileDelta_announcesFrameAndTransfers() throws IOException {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        serial.feedLine("[[SYNC:ACK]]");
        serial.feedBytes(XMODEM_SEND_HANDSHAKE);
        SyncProtocol protocol = new SyncProtocol(serial);

        byte[] delta = {1, 2, 3, 4, 5};
        boolean compressed = protocol.sendFileDelta("a.bin", delta, 999L, 100L, "abc", null);

        // The frame must carry path, length, compressed-flag, timestamp, sourceSize, sourceMd5 and
        // the manifest md5 (empty here: no manifest at hand).
        assertTrue(
                serial.getWrittenLines().stream().anyMatch(l -> l.contains("FILE_DELTA:a.bin")),
                "must announce FILE_DELTA with the path");
        assertTrue(
                serial.getWrittenLines().stream().anyMatch(l -> l.endsWith(":999:100:abc:]]")),
                "frame must end with lastModified:sourceSize:sourceMd5:manifestMd5");
        assertFalse(protocol.isXmodemInProgress(), "xmodem flag must be reset after send");
        // For this small payload, compressIfBeneficial leaves it uncompressed.
        assertFalse(compressed);
    }

    @Test
    void sendDeltaSignatures_announcesFrameAndTransfers() throws IOException {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        serial.feedLine("[[SYNC:ACK]]");
        serial.feedBytes(XMODEM_SEND_HANDSHAKE);
        SyncProtocol protocol = new SyncProtocol(serial);

        protocol.sendDeltaSignatures(SignatureSet.empty());

        assertTrue(
                serial.getWrittenLines().stream()
                        .anyMatch(l -> l.startsWith("[[SYNC:DELTA_SIG_DATA:")),
                "must announce DELTA_SIG_DATA with the payload length");
        assertFalse(protocol.isXmodemInProgress());
    }

    @Test
    void requestDeltaSignatures_roundTripsCompressedSet() throws IOException {
        SignatureSet set = sampleSet();
        byte[] compressed = CompressionUtil.compress(set.toBytes());
        // Keep the payload within a single 128-byte XMODEM block so buildSohFrame can deliver it.
        assertTrue(compressed.length <= 128, "compressed set should fit one block");

        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        serial.feedLine("[[SYNC:DELTA_SIG_DATA:" + compressed.length + "]]");
        serial.feedBytes(ScriptedSerialPortManager.buildSohFrame(compressed));
        SyncProtocol protocol = new SyncProtocol(serial);

        SignatureSet received = protocol.requestDeltaSignatures(List.of("a.bin"));

        assertEquals(1, received.size());
        assertEquals("a.bin", received.get("a.bin").getPath());
        assertTrue(
                serial.getWrittenLines().contains("[[SYNC:DELTA_SIG_REQ:a.bin]]"),
                "must send the request with the candidate path");
        assertTrue(
                serial.getWrittenLines().stream().anyMatch(l -> l.equals("[[SYNC:ACK]]")),
                "must ACK the data announcement");
        assertFalse(protocol.isXmodemInProgress());
    }

    // ---------- defensive / error branches ----------

    @Test
    void requestDeltaSignatures_nullOrEmptyPathsReturnEmpty() throws IOException {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        SyncProtocol protocol = new SyncProtocol(serial);
        // No round-trip is performed for null/empty path lists.
        assertEquals(0, protocol.requestDeltaSignatures(null).size());
        assertEquals(0, protocol.requestDeltaSignatures(List.of()).size());
        assertTrue(serial.getWrittenLines().stream().noneMatch(l -> l.contains("DELTA_SIG_REQ")));
    }

    @Test
    void sendFileDelta_xmodemPhaseFailure_sendsCancelAndThrowsAfterOneAttempt() throws IOException {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        serial.feedLine("[[SYNC:ACK]]"); // ACK for the first attempt's waitForCommand
        // Peer rejects the block with CAN -> xmodem.send returns false (XMODEM-phase failure).
        serial.feedBytes(
                new byte[] {
                    XModemTransfer.C, XModemTransfer.CAN, XModemTransfer.CAN, XModemTransfer.CAN
                });
        SyncProtocol protocol = new SyncProtocol(serial);
        protocol.setTimeout(150);

        // lastModified=0 exercises the System.currentTimeMillis() fallback.
        IOException thrown =
                assertThrows(
                        IOException.class,
                        () ->
                                protocol.sendFileDelta(
                                        "a.bin", new byte[] {1, 2, 3}, 0L, 100, "abc", null));
        assertTrue(
                thrown instanceof TransferCancelledException,
                "a CAN response is a deliberate peer cancel: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("cancelled by receiver"));
        // XMODEM-phase failure must not retry: exactly one FILE_DELTA frame was written.
        assertEquals(
                1,
                serial.getWrittenLines().stream()
                        .filter(l -> l.contains("FILE_DELTA:a.bin"))
                        .count(),
                "XMODEM-phase failure must not re-send the command");
        // A transfer cancel (CMD_CANCEL) must be sent to release the receiver's blocked
        // xmodem.receive(); the raw CAN byte is a no-op in the scripted port, but the CMD_CANCEL
        // frame goes through writeLine and is observable here.
        assertTrue(
                serial.getWrittenLines().stream().anyMatch(l -> l.contains("CANCEL")),
                "must send a transfer cancel on XMODEM-phase failure: " + serial.getWrittenLines());
        assertFalse(protocol.isXmodemInProgress());
    }

    @Test
    void sendDeltaSignatures_xmodemFailureThrows() throws IOException {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        serial.feedLine("[[SYNC:ACK]]");
        serial.feedBytes(
                new byte[] {
                    XModemTransfer.C, XModemTransfer.CAN, XModemTransfer.CAN, XModemTransfer.CAN
                });
        SyncProtocol protocol = new SyncProtocol(serial);

        IOException thrown =
                assertThrows(
                        IOException.class,
                        () -> protocol.sendDeltaSignatures(SignatureSet.empty()));
        assertTrue(
                thrown instanceof TransferCancelledException,
                "a CAN response is a deliberate peer cancel: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("cancelled by receiver"));
        assertFalse(protocol.isXmodemInProgress());
    }

    @Test
    void requestDeltaSignatures_senderCancelThrowsCancelled() throws IOException {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        // Announce the data, then feed CAN so xmodem.receive aborts as a deliberate cancel.
        serial.feedLine("[[SYNC:DELTA_SIG_DATA:10]]");
        serial.feedBytes(
                new byte[] {
                    XModemTransfer.CAN, XModemTransfer.CAN, XModemTransfer.CAN, XModemTransfer.CAN
                });
        SyncProtocol protocol = new SyncProtocol(serial);

        TransferCancelledException thrown =
                assertThrows(
                        TransferCancelledException.class,
                        () -> protocol.requestDeltaSignatures(List.of("a.bin")));
        assertTrue(
                thrown.getMessage().contains("cancelled by sender"),
                "cancel must be reported as such: " + thrown.getMessage());
        assertFalse(protocol.isXmodemInProgress());
    }

    @Test
    void requestDeltaSignatures_receiveFailureThrows() throws IOException {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        // Announce the data, then feed EOT with no blocks: a short receive is a genuine failure
        // (not a cancel) and must stay a plain IOException.
        serial.feedLine("[[SYNC:DELTA_SIG_DATA:10]]");
        serial.feedBytes(new byte[] {XModemTransfer.EOT});
        SyncProtocol protocol = new SyncProtocol(serial);

        IOException thrown =
                assertThrows(
                        IOException.class, () -> protocol.requestDeltaSignatures(List.of("a.bin")));
        assertFalse(
                thrown instanceof TransferCancelledException,
                "a short transfer is not a cancel: " + thrown.getMessage());
        assertTrue(
                thrown.getMessage().contains("Failed to receive delta signatures"),
                "msg: " + thrown.getMessage());
        assertFalse(protocol.isXmodemInProgress());
    }

    @Test
    void requestDeltaSignatures_emptySizeParamFallsBackToMinusOne() throws IOException {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        // DELTA_SIG_DATA with no size parameter -> expectedSize falls back to -1; the subsequent
        // XMODEM receive then fails fast (byte reads are staged to throw).
        serial.feedLine("[[SYNC:DELTA_SIG_DATA]]");
        serial.failByteReads();
        SyncProtocol protocol = new SyncProtocol(serial);
        protocol.setTimeout(150);

        assertThrows(IOException.class, () -> protocol.requestDeltaSignatures(List.of("a.bin")));
        assertFalse(protocol.isXmodemInProgress());
    }

    @Test
    void sendFileDelta_commandPhaseFailure_retriesThreeTimesWithoutCancel() throws IOException {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        // No ACK is fed, so every waitForCommand(CMD_ACK) times out -> command/ACK-phase failure,
        // which is the safe-to-retry case. The receiver never entered xmodem.receive().
        SyncProtocol protocol = new SyncProtocol(serial);
        protocol.setTimeout(150);

        IOException thrown =
                assertThrows(
                        IOException.class,
                        () ->
                                protocol.sendFileDelta(
                                        "a.bin", new byte[] {1, 2, 3}, 0L, 100, "abc", null));
        assertTrue(thrown.getMessage().contains("Failed to send file delta"));
        // Command/ACK-phase failure must retry up to maxAttempts = 3.
        assertEquals(
                3,
                serial.getWrittenLines().stream()
                        .filter(l -> l.contains("FILE_DELTA:a.bin"))
                        .count(),
                "command-phase failure must retry the command: " + serial.getWrittenLines());
        // No transfer cancel is needed because the receiver never entered xmodem.receive().
        assertTrue(
                serial.getWrittenLines().stream().noneMatch(l -> l.contains("CANCEL")),
                "must not cancel on command-phase failure: " + serial.getWrittenLines());
        assertTrue(thrown.getMessage().contains("after 3 attempt(s)"));
        assertFalse(protocol.isXmodemInProgress());
    }

    @Test
    void sendFile_xmodemPhaseFailure_sendsCancelAndThrowsAfterOneAttempt(@TempDir Path tempDir)
            throws IOException {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        serial.feedLine("[[SYNC:ACK]]");
        serial.feedBytes(
                new byte[] {
                    XModemTransfer.C, XModemTransfer.CAN, XModemTransfer.CAN, XModemTransfer.CAN
                });
        SyncProtocol protocol = new SyncProtocol(serial);
        protocol.setTimeout(150);

        IOException thrown =
                assertThrows(
                        IOException.class,
                        () ->
                                protocol.sendFile(
                                        tempDir.toFile(), "a.bin", new byte[] {1, 2, 3}, 1234L));
        assertTrue(
                thrown instanceof TransferCancelledException,
                "a CAN response is a deliberate peer cancel: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("cancelled by receiver"));
        assertEquals(
                1,
                serial.getWrittenLines().stream()
                        .filter(l -> l.contains("FILE_DATA:a.bin"))
                        .count(),
                "XMODEM-phase failure must not re-send the command");
        assertTrue(
                serial.getWrittenLines().stream().anyMatch(l -> l.contains("CANCEL")),
                "must send a transfer cancel: " + serial.getWrittenLines());
        assertFalse(protocol.isXmodemInProgress());
    }

    @Test
    void sendFile_commandPhaseFailure_retriesThreeTimesWithoutCancel(@TempDir Path tempDir)
            throws IOException {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        SyncProtocol protocol = new SyncProtocol(serial);
        protocol.setTimeout(150);

        IOException thrown =
                assertThrows(
                        IOException.class,
                        () ->
                                protocol.sendFile(
                                        tempDir.toFile(), "a.bin", new byte[] {1, 2, 3}, 1234L));
        assertTrue(thrown.getMessage().contains("Failed to send merged file"));
        assertEquals(
                3,
                serial.getWrittenLines().stream()
                        .filter(l -> l.contains("FILE_DATA:a.bin"))
                        .count(),
                "command-phase failure must retry the command: " + serial.getWrittenLines());
        assertTrue(
                serial.getWrittenLines().stream().noneMatch(l -> l.contains("CANCEL")),
                "must not cancel on command-phase failure: " + serial.getWrittenLines());
        assertTrue(thrown.getMessage().contains("after 3 attempt(s)"));
        assertFalse(protocol.isXmodemInProgress());
    }

    @Test
    void sendBatch_xmodemPhaseFailure_sendsCancelAndReturnsFalseAfterOneAttempt(
            @TempDir Path tempDir) throws IOException {
        File file = Files.write(tempDir.resolve("a.bin"), new byte[] {1, 2, 3}).toFile();
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        serial.feedLine("[[SYNC:ACK]]");
        serial.feedBytes(
                new byte[] {
                    XModemTransfer.C, XModemTransfer.CAN, XModemTransfer.CAN, XModemTransfer.CAN
                });
        SyncProtocol protocol = new SyncProtocol(serial);
        protocol.setTimeout(150);

        List<Object[]> entries = List.<Object[]>of(new Object[] {file, "a.bin"});
        boolean ok = protocol.sendBatch(entries, 32 * 1024, null, tempDir.toFile());
        assertFalse(ok, "XMODEM-phase failure must return false");
        assertEquals(
                1,
                serial.getWrittenLines().stream().filter(l -> l.contains("BATCH_DATA")).count(),
                "XMODEM-phase failure must not re-send the command");
        assertTrue(
                serial.getWrittenLines().stream().anyMatch(l -> l.contains("CANCEL")),
                "must send a transfer cancel: " + serial.getWrittenLines());
        assertFalse(protocol.isXmodemInProgress());
    }

    @Test
    void sendBatch_commandPhaseFailure_retriesThreeTimesWithoutCancelAndReturnsFalse(
            @TempDir Path tempDir) throws IOException {
        File file = Files.write(tempDir.resolve("a.bin"), new byte[] {1, 2, 3}).toFile();
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        SyncProtocol protocol = new SyncProtocol(serial);
        protocol.setTimeout(150);

        List<Object[]> entries = List.<Object[]>of(new Object[] {file, "a.bin"});
        boolean ok = protocol.sendBatch(entries, 32 * 1024, null, tempDir.toFile());
        assertFalse(ok, "command-phase failure must return false after retries");
        assertEquals(
                3,
                serial.getWrittenLines().stream().filter(l -> l.contains("BATCH_DATA")).count(),
                "command-phase failure must retry the command: " + serial.getWrittenLines());
        assertTrue(
                serial.getWrittenLines().stream().noneMatch(l -> l.contains("CANCEL")),
                "must not cancel on command-phase failure: " + serial.getWrittenLines());
        assertFalse(protocol.isXmodemInProgress());
    }

    /**
     * Regression: a receiver may have accepted the command and entered xmodem.receive() while its
     * ACK was lost. The command retry must first eject that stuck receive with a bare CAN abort —
     * otherwise the re-sent command is consumed as XMODEM payload, the receiver's raw ACK byte
     * never completes a line, and one lost ACK costs every attempt and the whole sync.
     */
    @Test
    void sendFile_commandPhaseRetryEjectsStuckReceiverWithCan(@TempDir Path tempDir)
            throws Exception {
        EventRecordingSerialPort serial = new EventRecordingSerialPort();
        SyncProtocol protocol = new SyncProtocol(serial);
        protocol.setTimeout(150);

        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Boolean> outcome = new AtomicReference<>();
        Thread worker =
                new Thread(
                        () -> {
                            try {
                                outcome.set(
                                        protocol.sendFile(
                                                tempDir.toFile(),
                                                "a.bin",
                                                new byte[] {1, 2, 3},
                                                1234L));
                            } catch (Throwable t) {
                                failure.set(t);
                            }
                        });
        worker.start();

        // Attempt 1 gets no ACK, so its handshake times out. The retried command must be
        // preceded on the wire by the CAN pair ejecting the receiver from xmodem.receive().
        waitForSerialState(
                () ->
                        serial.getWrittenLines().stream()
                                        .filter(l -> l.contains("FILE_DATA:a.bin"))
                                        .count()
                                >= 2,
                "retried FILE_DATA command");

        // Let attempt 2 complete: ACK the command, then serve the XMODEM handshake, the block
        // ACK and the EOT ACK lazily so no scripted byte is drained as stale.
        serial.feedLine("[[SYNC:ACK]]");
        serial.feedBytes(new byte[] {XModemTransfer.C});
        waitForSerialState(
                () -> serial.wireEvents().stream().anyMatch(e -> e.startsWith("bytes:")),
                "first XMODEM block write");
        serial.feedBytes(new byte[] {XModemTransfer.ACK});
        waitForSerialState(
                () -> serial.wireEvents().contains("byte:" + (XModemTransfer.EOT & 0xFF)),
                "EOT write");
        serial.feedBytes(new byte[] {XModemTransfer.ACK});

        worker.join(10_000);
        assertFalse(worker.isAlive(), "sendFile did not complete: " + failure.get());
        assertFalse(failure.get() != null, "sendFile failed: " + failure.get());
        assertFalse(outcome.get() == null, "sendFile did not return");

        List<String> events = serial.wireEvents();
        int firstCmd = indexOfFileData(events, 1);
        int secondCmd = indexOfFileData(events, 2);
        int firstCan = events.indexOf("byte:" + (XModemTransfer.CAN & 0xFF));
        assertTrue(firstCmd >= 0 && secondCmd > firstCmd, "two command attempts: " + events);
        assertTrue(
                firstCan > firstCmd && firstCan < secondCmd,
                "a bare CAN abort must precede the retried command: " + events);
        assertEquals(
                "byte:" + (XModemTransfer.CAN & 0xFF),
                events.get(firstCan + 1),
                "CAN is sent twice: " + events);
    }

    /**
     * Regression: sendTransferCancel writes two bare CAN bytes (no newline) ahead of the CANCEL
     * frame, so a newline-delimited read merges them into one line ("\u0018\u0018[[SYNC:CANCEL]]").
     * The merged frame must still be honored: before the fix it was dropped whole, and a peer
     * cancel arriving during the ACK wait went unnoticed — the sender then timed out, retried, and
     * resurrected the transfer the user had cancelled.
     */
    @Test
    void sendFile_canPrefixedCancelDuringAckWaitAbortsImmediatelyWithoutResend(
            @TempDir Path tempDir) throws Exception {
        EventRecordingSerialPort serial = new EventRecordingSerialPort();
        SyncProtocol protocol = new SyncProtocol(serial);
        protocol.setTimeout(500);

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker =
                new Thread(
                        () -> {
                            try {
                                protocol.sendFile(
                                        tempDir.toFile(), "a.bin", new byte[] {1, 2, 3}, 1234L);
                            } catch (Throwable t) {
                                failure.set(t);
                            }
                        });
        worker.start();
        waitForSerialState(
                () ->
                        serial.getWrittenLines().stream()
                                .anyMatch(l -> l.contains("FILE_DATA:a.bin")),
                "first FILE_DATA command");

        // The exact byte sequence the wire delivers when the peer cancels here.
        serial.feedLine("\u0018\u0018[[SYNC:CANCEL]]");

        worker.join(10_000);
        assertFalse(worker.isAlive(), "sendFile did not stop after the peer's cancel");
        assertTrue(
                failure.get() instanceof TransferCancelledException,
                "expected TransferCancelledException, got: " + failure.get());
        assertEquals(
                1,
                serial.getWrittenLines().stream()
                        .filter(l -> l.contains("FILE_DATA:a.bin"))
                        .count(),
                "a cancelled transfer must not be resurrected by the retry logic");
    }

    private static void waitForSerialState(BooleanSupplier condition, String description)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(condition.getAsBoolean(), "timed out waiting for " + description);
    }

    private static int indexOfFileData(List<String> events, int occurrence) {
        int seen = 0;
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).startsWith("line:") && events.get(i).contains("FILE_DATA:a.bin")) {
                if (++seen == occurrence) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** Scripted port that also records raw byte writes and block writes in one ordered log. */
    private static final class EventRecordingSerialPort extends ScriptedSerialPortManager {
        private final List<String> wireEvents = new CopyOnWriteArrayList<>();

        List<String> wireEvents() {
            return wireEvents;
        }

        @Override
        public void write(int b) {
            wireEvents.add("byte:" + (b & 0xFF));
        }

        @Override
        public void write(byte[] data) {
            wireEvents.add("bytes:" + data.length);
        }

        @Override
        public void writeLine(String line) {
            super.writeLine(line);
            wireEvents.add("line:" + line);
        }
    }
}
