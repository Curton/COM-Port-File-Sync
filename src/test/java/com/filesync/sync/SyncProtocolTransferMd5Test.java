package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.filesync.delta.DeltaEncoder;
import com.filesync.delta.HashUtil;
import com.filesync.delta.SignatureUtil;
import com.filesync.protocol.FileWriteException;
import com.filesync.protocol.ManifestMismatchException;
import com.filesync.protocol.SyncProtocol;
import com.filesync.serial.SerialPortManager;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the manifest md5 carried by the four transfer headers (FILE_DATA, FILE_DELTA,
 * FILE_APPEND and the version-2 batch envelope): a matching hash lets the receiver write and
 * confirm the path's base state, a mismatching hash stops the content before it reaches the disk
 * (never written, never queued for retry), and a missing hash (fast mode) disables verification.
 */
class SyncProtocolTransferMd5Test {

    private static final int BLOCK = 512;

    @TempDir java.nio.file.Path tempDir;

    // ========== CMD_FILE_DATA ==========

    @Test
    void receiveFile_manifestMd5Match_writesAndConfirms() throws IOException {
        byte[] payload = randomBytes(300, 1);
        String manifestMd5 = FileChangeDetector.manifestMd5(payload);
        SyncProtocol protocol = new SyncProtocol(new ByteStreamSerialPortManager(feed(payload)));
        List<String> confirmed = recordConfirmations(protocol);

        protocol.receiveFile(tempDir.toFile(), "f.bin", payload.length, false, 42L, manifestMd5);

        assertArrayEquals(payload, Files.readAllBytes(tempDir.resolve("f.bin")));
        assertEquals(
                List.of("f.bin:" + manifestMd5 + ":300"),
                confirmed,
                "the verified transfer must confirm path, manifest md5 and written size");
    }

    @Test
    void receiveFile_manifestMd5Mismatch_rejectsBeforeWriteAndReportsFailure() throws IOException {
        byte[] payload = randomBytes(300, 2);
        SyncProtocol protocol = new SyncProtocol(new ByteStreamSerialPortManager(feed(payload)));
        List<String> confirmed = recordConfirmations(protocol);
        List<String> failures = recordFailures(protocol);

        IOException thrown =
                assertThrows(
                        ManifestMismatchException.class,
                        () ->
                                protocol.receiveFile(
                                        tempDir.toFile(),
                                        "f.bin",
                                        payload.length,
                                        false,
                                        42L,
                                        FileChangeDetector.manifestMd5("other".getBytes())));
        assertTrue(
                thrown.getMessage().contains("Manifest md5 mismatch"),
                "the reason must name the verification failure: " + thrown.getMessage());
        assertFalse(
                new File(tempDir.toFile(), "f.bin").exists(),
                "content that failed its hash must never be written");
        assertEquals(List.of("f.bin"), failures, "the mismatch must be reported as a failure");
        assertTrue(confirmed.isEmpty(), "a rejected transfer confirms nothing");
    }

    @Test
    void receiveFile_emptyManifestMd5_writesUnverifiedAndConfirmsWithNullHash() throws IOException {
        byte[] payload = randomBytes(300, 3);
        SyncProtocol protocol = new SyncProtocol(new ByteStreamSerialPortManager(feed(payload)));
        List<String> confirmed = recordConfirmations(protocol);

        protocol.receiveFile(tempDir.toFile(), "f.bin", payload.length, false, 42L, null);

        assertArrayEquals(payload, Files.readAllBytes(tempDir.resolve("f.bin")));
        assertEquals(
                List.of("f.bin:null:300"),
                confirmed,
                "fast mode: the transfer is written and confirmed without a hash");
    }

    // ========== CMD_FILE_DELTA ==========

    @Test
    void receiveFileDelta_afterWrite_confirmsWithManifestMd5() throws IOException {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        SyncProtocol protocol = new SyncProtocol(serial);

        byte[] base = randomBytes(BLOCK * 2, 4);
        byte[] source = base.clone();
        source[10] = (byte) ~source[10];
        byte[] delta = DeltaEncoder.encode(source, SignatureUtil.compute("big.bin", base, BLOCK));
        serial.feedBytes(feed(delta));
        Files.write(tempDir.resolve("big.bin"), base);

        String manifestMd5 = FileChangeDetector.manifestMd5(source);
        List<String> confirmed = recordConfirmations(protocol);

        protocol.receiveFileDelta(
                tempDir.toFile(),
                "big.bin",
                delta.length,
                false,
                12345L,
                source.length,
                HashUtil.md5Hex(source),
                manifestMd5);

        assertArrayEquals(source, Files.readAllBytes(tempDir.resolve("big.bin")));
        assertEquals(
                List.of("big.bin:" + manifestMd5 + ":" + source.length),
                confirmed,
                "the delta path confirms from the header's manifest md5 (the raw md5 check"
                        + " already proved byte equality)");
    }

    @Test
    void receiveFileDelta_rawMd5Mismatch_confirmsNothingAndReportsFailure() throws IOException {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        SyncProtocol protocol = new SyncProtocol(serial);

        byte[] base = randomBytes(BLOCK * 2, 5);
        byte[] source = base.clone();
        source[10] = (byte) ~source[10];
        byte[] delta = DeltaEncoder.encode(source, SignatureUtil.compute("big.bin", base, BLOCK));
        serial.feedBytes(feed(delta));
        Files.write(tempDir.resolve("big.bin"), base);
        byte[] before = Files.readAllBytes(tempDir.resolve("big.bin"));

        List<String> confirmed = recordConfirmations(protocol);
        List<String> failures = recordFailures(protocol);

        // The reconstruction succeeds against {base}, but the announced raw md5 describes
        // something else: a stale signature, rejected before anything is written.
        assertThrows(
                IOException.class,
                () ->
                        protocol.receiveFileDelta(
                                tempDir.toFile(),
                                "big.bin",
                                delta.length,
                                false,
                                12345L,
                                source.length,
                                HashUtil.md5Hex(before),
                                FileChangeDetector.manifestMd5(source)));
        assertArrayEquals(
                before,
                Files.readAllBytes(tempDir.resolve("big.bin")),
                "a failed reconstruction must leave the existing file untouched");
        assertTrue(confirmed.isEmpty(), "a failed reconstruction confirms nothing");
        assertTrue(
                failures.isEmpty(),
                "a reconstruction failure means the base is stale, not that the bytes are"
                        + " corrupt: nothing is reported as a write failure");
    }

    // ========== CMD_FILE_APPEND ==========

    @Test
    void receiveFileAppend_afterWrite_confirmsWithManifestMd5() throws IOException {
        ScriptedSerialPortManager serial = new ScriptedSerialPortManager();
        SyncProtocol protocol = new SyncProtocol(serial);

        byte[] base = randomBytes(200, 7);
        byte[] tail = randomBytes(30, 8);
        byte[] full = new byte[base.length + tail.length];
        System.arraycopy(base, 0, full, 0, base.length);
        System.arraycopy(tail, 0, full, base.length, tail.length);
        serial.feedBytes(feed(tail));
        Files.write(tempDir.resolve("app.log"), base);

        String manifestMd5 = FileChangeDetector.manifestMd5(full);
        List<String> confirmed = recordConfirmations(protocol);

        protocol.receiveFileAppend(
                tempDir.toFile(),
                "app.log",
                tail.length,
                false,
                12345L,
                base.length,
                full.length,
                HashUtil.md5Hex(full),
                manifestMd5);

        assertArrayEquals(full, Files.readAllBytes(tempDir.resolve("app.log")));
        assertEquals(
                List.of("app.log:" + manifestMd5 + ":" + full.length),
                confirmed,
                "the append path confirms from the header's manifest md5");
    }

    // ========== write-failure confirmation ==========

    @Test
    void receiveFile_lockedTarget_reportsFailureWithoutConfirming() throws IOException {
        byte[] payload = randomBytes(300, 9);
        // A directory at the target path makes FileOutputStream fail on every platform, which is
        // how a file locked by another program surfaces on Windows.
        Files.createDirectory(tempDir.resolve("locked.bin"));
        SyncProtocol protocol = new SyncProtocol(new ByteStreamSerialPortManager(feed(payload)));
        List<String> confirmed = recordConfirmations(protocol);
        List<String> failures = recordFailures(protocol);

        String manifestMd5 = FileChangeDetector.manifestMd5(payload);
        FileWriteException thrown =
                assertThrows(
                        FileWriteException.class,
                        () ->
                                protocol.receiveFile(
                                        tempDir.toFile(),
                                        "locked.bin",
                                        payload.length,
                                        false,
                                        42L,
                                        manifestMd5));
        assertEquals("locked.bin", thrown.getRelativePath());
        assertArrayEquals(
                payload,
                thrown.getData(),
                "the payload must still travel with the exception for a deferred retry");
        assertEquals(
                List.of("locked.bin"), failures, "a locked target is reported as a write failure");
        assertTrue(confirmed.isEmpty(), "a failed write confirms nothing");
    }

    // ========== harness ==========

    /** Record confirmations as {@code path:md5:size} strings (null hashes print as "null"). */
    private List<String> recordConfirmations(SyncProtocol protocol) {
        List<String> confirmed = new java.util.ArrayList<>();
        protocol.setTransferConfirmedHandler(
                (path, md5, size) -> confirmed.add(path + ":" + md5 + ":" + size));
        return confirmed;
    }

    /** Record write-failure notifications as path strings. */
    private List<String> recordFailures(SyncProtocol protocol) {
        List<String> failures = new java.util.ArrayList<>();
        protocol.setWriteFailedHandler(failures::add);
        return failures;
    }

    /** Wrap the payload in XMODEM data frames plus EOT, as a scripted sender would. */
    private static byte[] feed(byte[] payload) {
        java.io.ByteArrayOutputStream stream = new java.io.ByteArrayOutputStream();
        int offset = 0;
        int blockNumber = 1;
        while (offset < payload.length) {
            int remaining = payload.length - offset;
            int blockSize;
            byte header;
            if (remaining >= 4096) {
                blockSize = 4096;
                header = com.filesync.serial.XModemTransfer.STX4K;
            } else if (remaining >= 1024 || remaining > 128) {
                blockSize = 1024;
                header = com.filesync.serial.XModemTransfer.STX;
            } else {
                blockSize = 128;
                header = com.filesync.serial.XModemTransfer.SOH;
            }
            byte[] block = new byte[blockSize];
            int toCopy = Math.min(remaining, blockSize);
            System.arraycopy(payload, offset, block, 0, toCopy);
            java.util.Arrays.fill(block, toCopy, blockSize, (byte) 0x1A);

            int crc = com.filesync.serial.XModemTransfer.calculateCRC16(block);
            stream.write(header);
            stream.write(blockNumber & 0xFF);
            stream.write(255 - (blockNumber & 0xFF));
            stream.writeBytes(block);
            stream.write((crc >> 8) & 0xFF);
            stream.write(crc & 0xFF);

            offset += toCopy;
            blockNumber++;
        }
        stream.write(com.filesync.serial.XModemTransfer.EOT);
        return stream.toByteArray();
    }

    private static byte[] randomBytes(int len, long seed) {
        byte[] b = new byte[len];
        new Random(seed).nextBytes(b);
        return b;
    }

    /** Minimal serial port whose read stream is a fixed byte array; writes are ignored. */
    private static final class ByteStreamSerialPortManager extends SerialPortManager {
        private final ByteArrayInputStream inputStream;

        private ByteStreamSerialPortManager(byte[] input) {
            this.inputStream = new ByteArrayInputStream(input);
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public int available() {
            return inputStream.available();
        }

        @Override
        public int read() throws IOException {
            return inputStream.read();
        }

        @Override
        public byte[] readExact(int length, int timeoutMs) throws IOException {
            byte[] data = new byte[length];
            int bytesRead = 0;
            while (bytesRead < length) {
                int read = inputStream.read(data, bytesRead, length - bytesRead);
                if (read < 0) {
                    throw new IOException(
                            "Unexpected end of stream while reading " + length + " bytes");
                }
                bytesRead += read;
            }
            return data;
        }

        @Override
        public void write(int b) throws IOException {
            // Outbound handshake bytes are ignored by the scripted peer.
        }

        @Override
        public void write(byte[] data) throws IOException {
            // Outbound handshake bytes are ignored by the scripted peer.
        }

        @Override
        public void clearInputBuffer() throws IOException {
            // No-op: no real input stream to drain.
        }
    }
}
