package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.filesync.delta.BlockSignature;
import com.filesync.delta.FileSignatures;
import com.filesync.delta.SignatureSet;
import com.filesync.protocol.SyncProtocol;
import com.filesync.serial.XModemTransfer;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Real-loop tests for the delta-sync protocol entry points on {@link SyncProtocol} (sendFileDelta,
 * sendDeltaSignatures, requestDeltaSignatures) using the scripted serial port. {@code
 * receiveFileDelta} is covered by {@link DeltaProtocolTest}; these cover the sender-side send paths
 * and the signature round-trip that the coordinator-level tests only mock.
 */
class DeltaSyncProtocolTest {

    private static final byte[] XMODEM_SEND_HANDSHAKE =
            new byte[] {XModemTransfer.C, XModemTransfer.ACK, XModemTransfer.ACK, XModemTransfer.ACK,
                    XModemTransfer.ACK};

    private SignatureSet sampleSet() {
        return new SignatureSet(List.of(
                new FileSignatures("a.bin", 64, 1, 64, List.of(new BlockSignature(0, 123, new byte[16])))));
    }

    @Test
    void sendFileDelta_announcesFrameAndTransfers() throws IOException {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        serial.feedLine("[[SYNC:ACK]]");
        serial.feedBytes(XMODEM_SEND_HANDSHAKE);
        SyncProtocol protocol = new SyncProtocol(serial);

        byte[] delta = {1, 2, 3, 4, 5};
        boolean compressed = protocol.sendFileDelta("a.bin", delta, 999L, 100L, "abc");

        // The frame must carry path, length, compressed-flag, timestamp, sourceSize, sourceMd5.
        assertTrue(
                serial.getWrittenLines().stream().anyMatch(l -> l.contains("FILE_DELTA:a.bin")),
                "must announce FILE_DELTA with the path");
        assertTrue(
                serial.getWrittenLines().stream().anyMatch(l -> l.endsWith(":999:100:abc]]")),
                "frame must end with lastModified:sourceSize:sourceMd5");
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
                serial.getWrittenLines().stream().anyMatch(l -> l.startsWith("[[SYNC:DELTA_SIG_DATA:")),
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
    void sendFileDelta_lastModifiedZeroAndXmodemFailureRetriesAndThrows() throws IOException {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        serial.feedLine("[[SYNC:ACK]]"); // ACK for the first attempt's waitForCommand
        // Peer rejects the block with CAN -> xmodem.send returns false (not throw).
        serial.feedBytes(
                new byte[] {XModemTransfer.C, XModemTransfer.CAN, XModemTransfer.CAN, XModemTransfer.CAN});
        SyncProtocol protocol = new SyncProtocol(serial);
        protocol.setTimeout(150); // fast timeout so retries 2/3 waitForCommand fail quickly

        // lastModified=0 exercises the System.currentTimeMillis() fallback; the rejected send
        // drives the retry loop and the final error throw.
        IOException thrown =
                assertThrows(
                        IOException.class,
                        () -> protocol.sendFileDelta("a.bin", new byte[] {1, 2, 3}, 0L, 100, "abc"));
        assertTrue(thrown.getMessage().contains("Failed to send file delta"));
        assertFalse(protocol.isXmodemInProgress());
    }

    @Test
    void sendDeltaSignatures_xmodemFailureThrows() throws IOException {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        serial.feedLine("[[SYNC:ACK]]");
        serial.feedBytes(
                new byte[] {XModemTransfer.C, XModemTransfer.CAN, XModemTransfer.CAN, XModemTransfer.CAN});
        SyncProtocol protocol = new SyncProtocol(serial);

        IOException thrown =
                assertThrows(
                        IOException.class,
                        () -> protocol.sendDeltaSignatures(SignatureSet.empty()));
        assertTrue(thrown.getMessage().contains("Failed to send delta signatures"));
        assertFalse(protocol.isXmodemInProgress());
    }

    @Test
    void requestDeltaSignatures_receiveFailureThrows() throws IOException {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        // Announce the data, then feed CAN so xmodem.receive aborts and returns null immediately.
        serial.feedLine("[[SYNC:DELTA_SIG_DATA:10]]");
        serial.feedBytes(
                new byte[] {XModemTransfer.CAN, XModemTransfer.CAN, XModemTransfer.CAN, XModemTransfer.CAN});
        SyncProtocol protocol = new SyncProtocol(serial);

        IOException thrown =
                assertThrows(
                        IOException.class,
                        () -> protocol.requestDeltaSignatures(List.of("a.bin")));
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
    void sendFileDelta_allAttemptsRejectedByPeer_hasNoLastFailure() throws IOException {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        // ACK + CAN rejection for each of the 3 attempts: xmodem.send returns false every time
        // (no IOException), so lastFailure stays null and the final error uses the XMODEM detail.
        for (int a = 0; a < 3; a++) {
            serial.feedLine("[[SYNC:ACK]]");
            serial.feedBytes(
                    new byte[] {XModemTransfer.C, XModemTransfer.CAN, XModemTransfer.CAN,
                            XModemTransfer.CAN});
        }
        SyncProtocol protocol = new SyncProtocol(serial);
        protocol.setTimeout(150);

        IOException thrown =
                assertThrows(
                        IOException.class,
                        () -> protocol.sendFileDelta("a.bin", new byte[] {1, 2, 3}, 0L, 100, "abc"));
        assertTrue(thrown.getMessage().contains("Failed to send file delta"));
        assertFalse(protocol.isXmodemInProgress());
    }
}
