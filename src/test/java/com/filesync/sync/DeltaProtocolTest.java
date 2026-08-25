package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.filesync.delta.DeltaEncoder;
import com.filesync.delta.HashUtil;
import com.filesync.delta.SignatureUtil;
import com.filesync.protocol.SyncProtocol;
import com.filesync.serial.XModemTransfer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration tests for {@link SyncProtocol#receiveFileDelta}: a scripted single-block XMODEM
 * payload delivers a delta, which the protocol reconstructs against the existing local file, MD5
 * verifies, and writes. Covers the happy path, GZIP-compressed delta, MD5-mismatch rollback, and
 * the missing-base-file guard.
 */
class DeltaProtocolTest {

    private static final int BLOCK = 64;

    @TempDir Path tempDir;

    private byte[] randomBytes(int len, long seed) {
        Random rng = new Random(seed);
        byte[] b = new byte[len];
        rng.nextBytes(b);
        return b;
    }

    /**
     * Build a base (receiver's existing file) and a slightly-modified source, then delta-encode.
     */
    private byte[] buildDelta(byte[] base, byte[] source) throws IOException {
        return DeltaEncoder.encode(source, SignatureUtil.compute("big.bin", base, BLOCK));
    }

    @Test
    void receiveFileDelta_appliesDeltaAndWritesFile() throws IOException {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        SyncProtocol protocol = new SyncProtocol(serial);

        byte[] base = randomBytes(BLOCK * 2, 1); // 2 full blocks
        byte[] source = base.clone();
        source[10] = (byte) ~source[10]; // modify a few bytes inside block 0
        source[11] = (byte) ~source[11];

        byte[] delta = buildDelta(base, source);
        assertTrue(delta.length <= 128, "delta should fit one XMODEM block for this test");

        Path existing = tempDir.resolve("big.bin");
        Files.write(existing, base);
        serial.feedBytes(ScriptedSerialPortManager.buildSohFrame(delta));

        protocol.receiveFileDelta(
                tempDir.toFile(),
                "big.bin",
                delta.length,
                false,
                12345L,
                source.length,
                HashUtil.md5Hex(source));

        assertArrayEquals(
                source, Files.readAllBytes(existing), "file must match the sender's source");
    }

    @Test
    void receiveFileDelta_compressedDeltaRoundTrip() throws IOException {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        SyncProtocol protocol = new SyncProtocol(serial);

        byte[] base = randomBytes(BLOCK * 2, 2);
        byte[] source = base.clone();
        for (int i = 0; i < 8; i++) source[BLOCK + i] = (byte) ~source[BLOCK + i];

        byte[] delta = buildDelta(base, source);
        byte[] compressed = CompressionUtil.compress(delta);
        assertTrue(compressed.length <= 128, "compressed delta should fit one XMODEM block");

        Path existing = tempDir.resolve("big.bin");
        Files.write(existing, base);
        serial.feedBytes(ScriptedSerialPortManager.buildSohFrame(compressed));

        protocol.receiveFileDelta(
                tempDir.toFile(),
                "big.bin",
                compressed.length,
                true,
                0L,
                source.length,
                HashUtil.md5Hex(source));

        assertArrayEquals(source, Files.readAllBytes(existing));
    }

    @Test
    void receiveFileDelta_md5MismatchThrowsAndDoesNotCorruptFile() throws IOException {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        SyncProtocol protocol = new SyncProtocol(serial);

        byte[] base = randomBytes(BLOCK * 2, 3);
        byte[] source = base.clone();
        source[5] = (byte) ~source[5];
        byte[] delta = buildDelta(base, source);

        Path existing = tempDir.resolve("big.bin");
        Files.write(existing, base);
        serial.feedBytes(ScriptedSerialPortManager.buildSohFrame(delta));

        // Pass a deliberately wrong MD5: reconstruction is correct but verification must fail.
        IOException thrown =
                assertThrows(
                        IOException.class,
                        () ->
                                protocol.receiveFileDelta(
                                        tempDir.toFile(),
                                        "big.bin",
                                        delta.length,
                                        false,
                                        0L,
                                        source.length,
                                        "00000000000000000000000000000000"));

        assertTrue(
                thrown.getMessage().contains("verification failed"),
                "error must mention verification: " + thrown.getMessage());
        // The base file must be left intact (write happens only after verification).
        assertArrayEquals(base, Files.readAllBytes(existing));
    }

    @Test
    void receiveFileDelta_missingExistingFileThrows() throws IOException {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        SyncProtocol protocol = new SyncProtocol(serial);

        byte[] base = randomBytes(BLOCK * 2, 4);
        byte[] source = base.clone();
        source[0] = (byte) ~source[0];
        byte[] delta = buildDelta(base, source);

        // No existing file on disk.
        serial.feedBytes(ScriptedSerialPortManager.buildSohFrame(delta));

        IOException thrown =
                assertThrows(
                        IOException.class,
                        () ->
                                protocol.receiveFileDelta(
                                        tempDir.toFile(),
                                        "missing.bin",
                                        delta.length,
                                        false,
                                        0L,
                                        source.length,
                                        HashUtil.md5Hex(source)));

        assertTrue(
                thrown.getMessage().contains("existing file missing"),
                "error must mention missing base: " + thrown.getMessage());
    }

    @Test
    void receiveFileDelta_existingIsDirectoryThrows() throws IOException {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        SyncProtocol protocol = new SyncProtocol(serial);

        byte[] base = randomBytes(BLOCK * 2, 5);
        byte[] source = base.clone();
        source[0] = (byte) ~source[0];
        byte[] delta = buildDelta(base, source);

        // A directory at the target path passes !exists but fails !isFile.
        tempDir.resolve("dirlike.bin").toFile().mkdirs();
        serial.feedBytes(ScriptedSerialPortManager.buildSohFrame(delta));

        IOException thrown =
                assertThrows(
                        IOException.class,
                        () ->
                                protocol.receiveFileDelta(
                                        tempDir.toFile(),
                                        "dirlike.bin",
                                        delta.length,
                                        false,
                                        0L,
                                        source.length,
                                        HashUtil.md5Hex(source)));
        assertTrue(thrown.getMessage().contains("existing file missing"));
    }

    @Test
    void receiveFileDelta_nullSourceMd5SkipsVerification() throws IOException {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        SyncProtocol protocol = new SyncProtocol(serial);

        byte[] base = randomBytes(BLOCK * 2, 6);
        byte[] source = base.clone();
        source[0] = (byte) ~source[0];
        byte[] delta = buildDelta(base, source);

        Path existing = tempDir.resolve("big.bin");
        Files.write(existing, base);
        serial.feedBytes(ScriptedSerialPortManager.buildSohFrame(delta));

        // sourceMd5=null skips the verification branch and still writes the reconstructed file.
        protocol.receiveFileDelta(
                tempDir.toFile(), "big.bin", delta.length, false, 0L, source.length, null);
        assertArrayEquals(source, Files.readAllBytes(existing));
    }

    @Test
    void receiveFileDelta_emptySourceMd5SkipsVerification() throws IOException {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        SyncProtocol protocol = new SyncProtocol(serial);

        byte[] base = randomBytes(BLOCK * 2, 8);
        byte[] source = base.clone();
        source[0] = (byte) ~source[0];
        byte[] delta = buildDelta(base, source);

        Path existing = tempDir.resolve("big.bin");
        Files.write(existing, base);
        serial.feedBytes(ScriptedSerialPortManager.buildSohFrame(delta));

        // sourceMd5="" hits the !isEmpty()==false short-circuit, skipping verification.
        protocol.receiveFileDelta(
                tempDir.toFile(), "big.bin", delta.length, false, 0L, source.length, "");
        assertArrayEquals(source, Files.readAllBytes(existing));
    }

    @Test
    void receiveFileDelta_xmodemReceiveFailureThrows() throws IOException {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        // Feed CAN so xmodem.receive aborts and returns null immediately.
        serial.feedBytes(
                new byte[] {
                    XModemTransfer.CAN, XModemTransfer.CAN, XModemTransfer.CAN, XModemTransfer.CAN
                });
        SyncProtocol protocol = new SyncProtocol(serial);

        IOException thrown =
                assertThrows(
                        IOException.class,
                        () ->
                                protocol.receiveFileDelta(
                                        tempDir.toFile(), "big.bin", 50, false, 0L, 100, "abc"));
        assertTrue(
                thrown.getMessage().contains("Failed to receive file delta"),
                "error must mention receive failure: " + thrown.getMessage());
        assertFalse(protocol.isXmodemInProgress());
    }
}
