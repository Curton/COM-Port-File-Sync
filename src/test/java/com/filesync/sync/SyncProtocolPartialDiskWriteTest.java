package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.filesync.delta.HashUtil;
import com.filesync.protocol.FileWriteException;
import com.filesync.protocol.SyncProtocol;
import com.filesync.protocol.TransferCancelledException;
import com.filesync.serial.SerialPortManager;
import com.filesync.serial.XModemTransfer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the partial disk-write receive of large transfers: verified XMODEM blocks stream to a
 * {@code .filesync-part} staging file next to the target as they arrive, and an interrupted
 * transfer salvages the staged prefix to the target path (stamped with the sender's lastModified)
 * so the next sync can append only the missing tail instead of restarting from byte zero.
 *
 * <p>Covers both staging flavors: large NEW files (whole payload staged) and large append tails
 * (the received tail prefix is merged into the existing base, growing it into a longer prefix of
 * the sender's file).
 */
class SyncProtocolPartialDiskWriteTest {

    private static final int PAYLOAD_SIZE = 4 * 1024 * 1024 + 1024;
    private static final int FIRST_BLOCK_END = 3 + 4096 + 2;

    @TempDir java.nio.file.Path tempDir;

    // ========== clean transfers ==========

    @Test
    void receiveFile_largeNewFile_successWritesTargetAndDropsStage() throws IOException {
        byte[] payload = patternedPayload(PAYLOAD_SIZE);
        SyncProtocol protocol =
                new SyncProtocol(new ByteStreamSerialPortManager(buildXmodemFrame(payload)));

        File baseDir = tempDir.toFile();
        protocol.receiveFile(baseDir, "big.bin", payload.length, false, 1234567890L);

        assertArrayEquals(
                payload,
                Files.readAllBytes(new File(baseDir, "big.bin").toPath()),
                "The complete payload must land at the target path");
        assertNoStageFile(baseDir, "big.bin");
        assertFalse(protocol.isXmodemInProgress(), "xmodemInProgress must be reset after success");
    }

    @Test
    void receiveFile_largeNewFileCompressed_successDecodesFromStage() throws IOException {
        // Pseudo-random payload: the compressed wire size stays above the 4MB staging threshold.
        byte[] payload = pseudoRandomPayload(PAYLOAD_SIZE);
        byte[] compressed = CompressionUtil.compress(payload);
        SyncProtocol protocol =
                new SyncProtocol(new ByteStreamSerialPortManager(buildXmodemFrame(compressed)));

        File baseDir = tempDir.toFile();
        protocol.receiveFile(baseDir, "big.txt", compressed.length, true, 55L);

        File target = new File(baseDir, "big.txt");
        assertArrayEquals(payload, Files.readAllBytes(target.toPath()));
        assertEquals(55L, target.lastModified(), "Sender timestamp must be preserved");
        assertNoStageFile(baseDir, "big.txt");
    }

    @Test
    void receiveFile_smallFile_keepsBufferedPathWithoutStaging() throws IOException {
        byte[] payload = "small file".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        SyncProtocol protocol =
                new SyncProtocol(new ByteStreamSerialPortManager(buildXmodemFrame(payload)));

        File baseDir = tempDir.toFile();
        protocol.receiveFile(baseDir, "small.txt", payload.length, false, 7L);

        assertArrayEquals(payload, Files.readAllBytes(new File(baseDir, "small.txt").toPath()));
        // Below the threshold nothing may be staged, not even transiently.
        try (var files = Files.list(tempDir)) {
            assertTrue(
                    files.allMatch(f -> !f.getFileName().toString().startsWith(".")),
                    "Small-file receives must not create a staging file");
        }
    }

    // ========== interrupted transfers ==========

    @Test
    void receiveFile_largeNewFile_interrupted_salvagesVerifiedPrefixWithSenderMtime()
            throws IOException {
        byte[] payload = patternedPayload(PAYLOAD_SIZE);
        // First block fully delivered, second block cut off mid-data: exactly one verified block
        // reaches the stage before the stream dies.
        byte[] truncated = Arrays.copyOf(buildXmodemFrame(payload), FIRST_BLOCK_END + 10);
        SyncProtocol protocol = new SyncProtocol(new ByteStreamSerialPortManager(truncated));

        File baseDir = tempDir.toFile();
        long senderMtime = 1234567890L;
        IOException thrown =
                assertThrows(
                        IOException.class,
                        () ->
                                protocol.receiveFile(
                                        baseDir, "big.bin", payload.length, false, senderMtime));

        File target = new File(baseDir, "big.bin");
        assertTrue(target.isFile(), "An interrupted transfer must salvage the prefix");
        byte[] saved = Files.readAllBytes(target.toPath());
        assertEquals(4096, saved.length, "Exactly the verified blocks are salvaged");
        assertArrayEquals(Arrays.copyOf(payload, saved.length), saved);
        assertEquals(
                senderMtime,
                target.lastModified(),
                "The salvaged prefix must carry the sender's timestamp so the next sync plans"
                        + " an append instead of a conflict");
        assertNoStageFile(baseDir, "big.bin");
        assertTrue(
                thrown.getMessage().contains("kept " + saved.length + " received bytes"),
                "The failure must report the salvaged prefix");
    }

    @Test
    void receiveFile_largeNewFile_interruptedCompressed_salvagesDecodedPrefix() throws IOException {
        // Pseudo-random payload so the compressed wire size stays above the staging threshold.
        byte[] payload = pseudoRandomPayload(PAYLOAD_SIZE);
        byte[] compressed = CompressionUtil.compress(payload);
        byte[] truncated = Arrays.copyOf(buildXmodemFrame(compressed), FIRST_BLOCK_END + 10);
        SyncProtocol protocol = new SyncProtocol(new ByteStreamSerialPortManager(truncated));

        File baseDir = tempDir.toFile();
        assertThrows(
                IOException.class,
                () -> protocol.receiveFile(baseDir, "big.txt", compressed.length, true, 42L));

        File target = new File(baseDir, "big.txt");
        assertTrue(target.isFile(), "A compressed interrupted transfer must salvage a prefix");
        byte[] saved = Files.readAllBytes(target.toPath());
        assertTrue(saved.length > 0, "The salvaged prefix must not be empty");
        assertTrue(
                saved.length < payload.length,
                "A truncated transfer cannot decode the full payload");
        assertArrayEquals(
                Arrays.copyOf(payload, saved.length),
                saved,
                "The salvaged bytes must be an exact byte prefix of the original");
        assertEquals(42L, target.lastModified());
        assertNoStageFile(baseDir, "big.txt");
    }

    @Test
    void receiveFile_largeNewFile_cleanEarlyEot_salvagesPartialPrefix() throws IOException {
        byte[] payload = patternedPayload(PAYLOAD_SIZE);
        // Announced size covers two blocks, but the sender only delivers the first block and EOTs.
        byte[] fullFrame = buildXmodemFrame(payload);
        byte[] oneBlockPlusEot = Arrays.copyOf(fullFrame, FIRST_BLOCK_END + 1);
        oneBlockPlusEot[FIRST_BLOCK_END] = XModemTransfer.EOT;
        SyncProtocol protocol = new SyncProtocol(new ByteStreamSerialPortManager(oneBlockPlusEot));

        File baseDir = tempDir.toFile();
        IOException thrown =
                assertThrows(
                        IOException.class,
                        () -> protocol.receiveFile(baseDir, "big.bin", payload.length, false, 99L));

        byte[] saved = Files.readAllBytes(new File(baseDir, "big.bin").toPath());
        assertEquals(4096, saved.length);
        assertArrayEquals(Arrays.copyOf(payload, 4096), saved);
        assertTrue(thrown.getMessage().contains("kept 4096 received bytes"));
        assertNoStageFile(baseDir, "big.bin");
    }

    // ========== invariants of the existing buffered path ==========

    @Test
    void receiveFile_existingTarget_interrupted_leavesTargetUntouched() throws IOException {
        byte[] payload = patternedPayload(PAYLOAD_SIZE);
        byte[] truncated = Arrays.copyOf(buildXmodemFrame(payload), FIRST_BLOCK_END + 10);
        SyncProtocol protocol = new SyncProtocol(new ByteStreamSerialPortManager(truncated));

        File baseDir = tempDir.toFile();
        File target = new File(baseDir, "exists.bin");
        Files.write(target.toPath(), "OLD".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        // An existing target takes the buffered path: no staging, and the old content survives.
        assertThrows(
                IOException.class,
                () -> protocol.receiveFile(baseDir, "exists.bin", payload.length, false, 1L));
        assertEquals(
                "OLD",
                new String(Files.readAllBytes(target.toPath())),
                "An interrupted overwrite must not touch the existing file");
        assertNoStageFile(baseDir, "exists.bin");
    }

    @Test
    void receiveFile_largeNewFile_writeFailure_throwsFileWriteExceptionAndDropsStage()
            throws IOException {
        byte[] payload = patternedPayload(PAYLOAD_SIZE);
        SyncProtocol protocol =
                new SyncProtocol(new ByteStreamSerialPortManager(buildXmodemFrame(payload)));

        File baseDir = tempDir.toFile();
        // A directory at the target path makes FileOutputStream fail on every platform, which is
        // how a file locked by another program surfaces on Windows.
        new File(baseDir, "locked.bin").mkdirs();

        FileWriteException thrown =
                assertThrows(
                        FileWriteException.class,
                        () ->
                                protocol.receiveFile(
                                        baseDir, "locked.bin", payload.length, false, 5L));

        assertArrayEquals(
                payload,
                thrown.getData(),
                "The exception must carry the full payload for a later retry");
        assertNoStageFile(baseDir, "locked.bin");
    }

    // ========== staged append tails ==========

    @Test
    void receiveFileAppend_largeTail_successAppliesTailAndDropsStage() throws IOException {
        byte[] base = patternedPayload(8192);
        byte[] tail = pseudoRandomPayload(PAYLOAD_SIZE);
        byte[] full = concat(base, tail);
        SyncProtocol protocol =
                new SyncProtocol(new ByteStreamSerialPortManager(buildXmodemFrame(tail)));

        File baseDir = tempDir.toFile();
        File target = new File(baseDir, "growing.bin");
        Files.write(target.toPath(), base);

        protocol.receiveFileAppend(
                baseDir,
                "growing.bin",
                tail.length,
                false,
                77L,
                base.length,
                full.length,
                HashUtil.md5Hex(full));

        assertArrayEquals(full, Files.readAllBytes(target.toPath()));
        assertEquals(77L, target.lastModified(), "Sender timestamp must be preserved");
        assertNoStageFile(baseDir, "growing.bin");
        assertFalse(protocol.isXmodemInProgress(), "xmodemInProgress must be reset after success");
    }

    @Test
    void receiveFileAppend_largeTailCompressed_successDecodesFromStage() throws IOException {
        byte[] base = patternedPayload(4096);
        // Pseudo-random tail: the compressed wire size stays above the 4MB staging threshold.
        byte[] tail = pseudoRandomPayload(PAYLOAD_SIZE);
        byte[] compressed = CompressionUtil.compress(tail);
        byte[] full = concat(base, tail);
        SyncProtocol protocol =
                new SyncProtocol(new ByteStreamSerialPortManager(buildXmodemFrame(compressed)));

        File baseDir = tempDir.toFile();
        File target = new File(baseDir, "growing.bin");
        Files.write(target.toPath(), base);

        protocol.receiveFileAppend(
                baseDir,
                "growing.bin",
                compressed.length,
                true,
                88L,
                base.length,
                full.length,
                HashUtil.md5Hex(full));

        assertArrayEquals(full, Files.readAllBytes(target.toPath()));
        assertEquals(88L, target.lastModified());
        assertNoStageFile(baseDir, "growing.bin");
    }

    @Test
    void receiveFileAppend_cancelledMidTail_mergesTailPrefixIntoBaseWithSenderMtime()
            throws IOException {
        byte[] base = patternedPayload(8192);
        byte[] tail = pseudoRandomPayload(PAYLOAD_SIZE);
        byte[] full = concat(base, tail);
        byte[] frame = buildXmodemFrame(tail);
        // First tail block delivered, then the sender aborts with CAN.
        byte[] cancelled = Arrays.copyOf(frame, FIRST_BLOCK_END + 1);
        cancelled[FIRST_BLOCK_END] = XModemTransfer.CAN;
        SyncProtocol protocol = new SyncProtocol(new ByteStreamSerialPortManager(cancelled));

        File baseDir = tempDir.toFile();
        File target = new File(baseDir, "growing.bin");
        Files.write(target.toPath(), base);

        TransferCancelledException thrown =
                assertThrows(
                        TransferCancelledException.class,
                        () ->
                                protocol.receiveFileAppend(
                                        baseDir,
                                        "growing.bin",
                                        tail.length,
                                        false,
                                        1234567890L,
                                        base.length,
                                        full.length,
                                        HashUtil.md5Hex(full)));

        assertTrue(
                thrown.getMessage().contains("4096 tail bytes salvaged"),
                "The cancel must report the merged tail prefix: " + thrown.getMessage());
        byte[] merged = Files.readAllBytes(target.toPath());
        assertEquals(base.length + 4096, merged.length, "The base must grow by one verified block");
        assertArrayEquals(concat(base, Arrays.copyOf(tail, 4096)), merged);
        assertEquals(
                1234567890L,
                target.lastModified(),
                "The merged prefix must carry the sender's timestamp so the next sync plans"
                        + " another append instead of a conflict");
        assertNoStageFile(baseDir, "growing.bin");
    }

    @Test
    void receiveFileAppend_interruptedMidTail_keepsExactTailPrefixOnDisk() throws IOException {
        byte[] base = patternedPayload(8192);
        byte[] tail = pseudoRandomPayload(PAYLOAD_SIZE);
        byte[] full = concat(base, tail);
        byte[] truncated = Arrays.copyOf(buildXmodemFrame(tail), FIRST_BLOCK_END + 10);
        SyncProtocol protocol = new SyncProtocol(new ByteStreamSerialPortManager(truncated));

        File baseDir = tempDir.toFile();
        File target = new File(baseDir, "growing.bin");
        Files.write(target.toPath(), base);

        IOException thrown =
                assertThrows(
                        IOException.class,
                        () ->
                                protocol.receiveFileAppend(
                                        baseDir,
                                        "growing.bin",
                                        tail.length,
                                        false,
                                        42L,
                                        base.length,
                                        full.length,
                                        HashUtil.md5Hex(full)));

        assertFalse(
                thrown instanceof TransferCancelledException,
                "A communication failure must not be reported as a benign cancel");
        assertTrue(
                thrown.getMessage().contains("kept 4096 received tail bytes"),
                "The failure must report the merged tail prefix: " + thrown.getMessage());
        assertArrayEquals(
                concat(base, Arrays.copyOf(tail, 4096)), Files.readAllBytes(target.toPath()));
        assertEquals(42L, target.lastModified());
        assertNoStageFile(baseDir, "growing.bin");
    }

    @Test
    void receiveFileAppend_interruptedCompressedTail_mergesDecodedPrefix() throws IOException {
        byte[] base = patternedPayload(4096);
        byte[] tail = pseudoRandomPayload(PAYLOAD_SIZE);
        byte[] compressed = CompressionUtil.compress(tail);
        byte[] full = concat(base, tail);
        byte[] truncated = Arrays.copyOf(buildXmodemFrame(compressed), FIRST_BLOCK_END + 10);
        SyncProtocol protocol = new SyncProtocol(new ByteStreamSerialPortManager(truncated));

        File baseDir = tempDir.toFile();
        File target = new File(baseDir, "growing.bin");
        Files.write(target.toPath(), base);

        assertThrows(
                IOException.class,
                () ->
                        protocol.receiveFileAppend(
                                baseDir,
                                "growing.bin",
                                compressed.length,
                                true,
                                42L,
                                base.length,
                                full.length,
                                HashUtil.md5Hex(full)));

        byte[] merged = Files.readAllBytes(target.toPath());
        assertTrue(merged.length > base.length, "A decoded tail prefix must be merged");
        assertTrue(merged.length < full.length, "A truncated tail cannot complete the file");
        assertArrayEquals(Arrays.copyOf(full, merged.length), merged);
        assertEquals(42L, target.lastModified());
        assertNoStageFile(baseDir, "growing.bin");
    }

    @Test
    void receiveFileAppend_cancelBeforeAnyTailByte_leavesBaseUntouched() throws IOException {
        byte[] base = patternedPayload(8192);
        byte[] tail = pseudoRandomPayload(PAYLOAD_SIZE);
        SyncProtocol protocol =
                new SyncProtocol(new ByteStreamSerialPortManager(new byte[] {XModemTransfer.CAN}));

        File baseDir = tempDir.toFile();
        File target = new File(baseDir, "growing.bin");
        Files.write(target.toPath(), base);
        long beforeMtime = target.lastModified();

        TransferCancelledException thrown =
                assertThrows(
                        TransferCancelledException.class,
                        () ->
                                protocol.receiveFileAppend(
                                        baseDir,
                                        "growing.bin",
                                        tail.length,
                                        false,
                                        7L,
                                        base.length,
                                        base.length + tail.length,
                                        HashUtil.md5Hex(concat(base, tail))));

        assertFalse(
                thrown.getMessage().contains("salvaged"),
                "Nothing was received, so nothing may be reported as salvaged");
        assertArrayEquals(base, Files.readAllBytes(target.toPath()));
        assertEquals(beforeMtime, target.lastModified(), "The base must not even be re-stamped");
        assertNoStageFile(baseDir, "growing.bin");
    }

    @Test
    void receiveFileAppend_baseDriftedDuringInterrupt_leavesBaseUntouched() throws IOException {
        byte[] base = patternedPayload(8192);
        byte[] tail = pseudoRandomPayload(PAYLOAD_SIZE);
        byte[] truncated = Arrays.copyOf(buildXmodemFrame(tail), FIRST_BLOCK_END + 10);
        SyncProtocol protocol = new SyncProtocol(new ByteStreamSerialPortManager(truncated));

        File baseDir = tempDir.toFile();
        File target = new File(baseDir, "growing.bin");
        Files.write(target.toPath(), base);
        long beforeMtime = target.lastModified();

        // The announced baseSize does not match the base on disk: the drift guard must refuse
        // to extend a base the sender did not diff against.
        IOException thrown =
                assertThrows(
                        IOException.class,
                        () ->
                                protocol.receiveFileAppend(
                                        baseDir,
                                        "growing.bin",
                                        tail.length,
                                        false,
                                        7L,
                                        base.length + 123,
                                        base.length + 123 + tail.length,
                                        "irrelevant"));

        assertFalse(
                thrown.getMessage().contains("kept"),
                "A drifted base must not be extended: " + thrown.getMessage());
        assertArrayEquals(base, Files.readAllBytes(target.toPath()));
        assertEquals(beforeMtime, target.lastModified());
        assertNoStageFile(baseDir, "growing.bin");
    }

    @Test
    void receiveFileAppend_wholeTailThenCancel_leavesBaseForCleanRetransfer() throws IOException {
        byte[] base = patternedPayload(8192);
        byte[] tail = pseudoRandomPayload(PAYLOAD_SIZE);
        byte[] frame = buildXmodemFrame(tail);
        // Every tail block is delivered, but the sender cancels instead of EOT: the
        // reconstruction cannot be verified mid-failure, so the whole-tail case conservatively
        // keeps the base for a clean retransfer of the tail.
        byte[] cancelled = Arrays.copyOf(frame, frame.length);
        cancelled[frame.length - 1] = XModemTransfer.CAN;
        SyncProtocol protocol = new SyncProtocol(new ByteStreamSerialPortManager(cancelled));

        File baseDir = tempDir.toFile();
        File target = new File(baseDir, "growing.bin");
        Files.write(target.toPath(), base);

        assertThrows(
                TransferCancelledException.class,
                () ->
                        protocol.receiveFileAppend(
                                baseDir,
                                "growing.bin",
                                tail.length,
                                false,
                                7L,
                                base.length,
                                base.length + tail.length,
                                HashUtil.md5Hex(concat(base, tail))));

        assertArrayEquals(base, Files.readAllBytes(target.toPath()));
        assertNoStageFile(baseDir, "growing.bin");
    }

    @Test
    void receiveFileAppend_smallTail_keepsBufferedPathWithoutStaging() throws IOException {
        byte[] base = patternedPayload(1024);
        byte[] tail = pseudoRandomPayload(2048);
        byte[] full = concat(base, tail);
        SyncProtocol protocol =
                new SyncProtocol(new ByteStreamSerialPortManager(buildXmodemFrame(tail)));

        File baseDir = tempDir.toFile();
        File target = new File(baseDir, "growing.bin");
        Files.write(target.toPath(), base);

        protocol.receiveFileAppend(
                baseDir,
                "growing.bin",
                tail.length,
                false,
                9L,
                base.length,
                full.length,
                HashUtil.md5Hex(full));

        assertArrayEquals(full, Files.readAllBytes(target.toPath()));
        // Below the threshold nothing may be staged, not even transiently.
        try (var files = Files.list(tempDir)) {
            assertTrue(
                    files.allMatch(f -> !f.getFileName().toString().startsWith(".")),
                    "Small-tail appends must not create a staging file");
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    // ========== helpers ==========

    /** Deterministic payload with enough variation to survive compression unchanged. */
    private static byte[] patternedPayload(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) ((i * 31 + (i >>> 8)) & 0xFF);
        }
        return data;
    }

    /** Deterministic xorshift payload: high entropy, so gzip cannot shrink it materially. */
    private static byte[] pseudoRandomPayload(int size) {
        byte[] data = new byte[size];
        int state = 0x1234567;
        for (int i = 0; i < size; i++) {
            state ^= state << 13;
            state ^= state >>> 17;
            state ^= state << 5;
            data[i] = (byte) (state & 0xFF);
        }
        return data;
    }

    /**
     * Build the XMODEM wire stream for {@code payload} mirroring the sender's block-format
     * selection: 4096-byte (STX4K), then 1024-byte (STX), falling back to 128-byte (SOH) tails,
     * followed by EOT.
     */
    private static byte[] buildXmodemFrame(byte[] payload) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        int offset = 0;
        int blockNumber = 1;
        while (offset < payload.length) {
            int remaining = payload.length - offset;
            int blockSize;
            byte header;
            if (remaining >= 4096) {
                blockSize = 4096;
                header = XModemTransfer.STX4K;
            } else if (remaining >= 1024 || remaining > 128) {
                blockSize = 1024;
                header = XModemTransfer.STX;
            } else {
                blockSize = 128;
                header = XModemTransfer.SOH;
            }
            byte[] block = new byte[blockSize];
            int toCopy = Math.min(remaining, blockSize);
            System.arraycopy(payload, offset, block, 0, toCopy);
            Arrays.fill(block, toCopy, blockSize, (byte) 0x1A);

            int crc = XModemTransfer.calculateCRC16(block);
            stream.write(header);
            stream.write(blockNumber & 0xFF);
            stream.write(255 - (blockNumber & 0xFF));
            stream.writeBytes(block);
            stream.write((crc >> 8) & 0xFF);
            stream.write(crc & 0xFF);

            offset += toCopy;
            blockNumber++;
        }
        stream.write(XModemTransfer.EOT);
        return stream.toByteArray();
    }

    private void assertNoStageFile(File baseDir, String targetName) {
        File stage = new File(baseDir, "." + targetName + SyncProtocol.PARTIAL_SUFFIX);
        assertFalse(stage.exists(), "The staging file must be removed after the transfer ends");
    }

    /**
     * A {@link SerialPortManager} serving a fixed byte stream: reads drain it in order and a read
     * past the end throws immediately, simulating a serial link that died mid-transfer.
     */
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
