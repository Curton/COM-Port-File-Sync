package com.filesync.delta;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Coverage-focused tests for the delta package: constructor validation, equals/hashCode, accessor
 * methods, and every error/validation branch in serialization and decoding. The delta package is
 * held to 100% line+branch coverage by the jacoco check rule.
 */
class DeltaCoverageTest {

    private static final int BLOCK = 64;

    private byte[] sigStrong(byte seed) {
        byte[] b = new byte[16];
        java.util.Arrays.fill(b, seed);
        return b;
    }

    // ---------- BlockSignature ----------

    @Test
    void blockSignature_constructorRejectsInvalidStrongHash() {
        assertThrows(IllegalArgumentException.class, () -> new BlockSignature(0, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new BlockSignature(0, 0, new byte[5]));
        assertThrows(IllegalArgumentException.class, () -> new BlockSignature(0, 0, new byte[17]));
    }

    @Test
    void blockSignature_equalsAndHashCode() {
        BlockSignature a = new BlockSignature(0, 5, sigStrong((byte) 1));
        // same instance
        assertEquals(a, a);
        // null and wrong type
        assertFalse(a.equals(null));
        assertFalse(a.equals("not a signature"));
        // blockIndex differs -> equals false (short-circuit on first &&)
        assertNotEquals(a, new BlockSignature(1, 5, sigStrong((byte) 1)));
        // weakHash differs (blockIndex equal)
        assertNotEquals(a, new BlockSignature(0, 6, sigStrong((byte) 1)));
        // strong differs (blockIndex and weak equal)
        assertNotEquals(a, new BlockSignature(0, 5, sigStrong((byte) 2)));
        // fully equal (defensive copy of strong)
        assertEquals(a, new BlockSignature(0, 5, sigStrong((byte) 1)));
        // getStrongHash returns a defensive copy
        assertEquals(a, new BlockSignature(0, 5, a.getStrongHash()));
        // hashCode is callable and consistent
        assertEquals(a.hashCode(), new BlockSignature(0, 5, sigStrong((byte) 1)).hashCode());
    }

    // ---------- RollingHash ----------

    @Test
    void rollingHash_weakChecksumRejectsNegativeLength() {
        assertThrows(IllegalArgumentException.class, () -> RollingHash.weakChecksum(new byte[8], 0, -1));
    }

    @Test
    void rollingHash_getBlockLengthAccessor() {
        RollingHash rh = new RollingHash(32);
        assertEquals(32, rh.getBlockLength());
    }

    // ---------- DeltaEncoder edge branches ----------

    @Test
    void encode_nonPositiveBlockSizeEmitsAllLiteral() throws IOException {
        FileSignatures sigs =
                new FileSignatures("x", 0, 1, 100, List.of(new BlockSignature(0, 0, new byte[16])));
        byte[] source = new byte[100];
        byte[] delta = DeltaEncoder.encode(source, sigs);
        // No COPY tokens (TAG_COPY never appears after the 17-byte header). The header carries
        // blockSize=0, so DeltaDecoder would reject it; we only assert the encode path here.
        assertTrue(noCopyToken(delta), "delta should be all-literal when blockSize<=0");
        // The literal payload reconstructs the source when decoded with a valid-blockSize header.
        assertEquals(100, delta.length - 17 - 5);
    }

    @Test
    void encode_weakHashCollisionWithNoStrongMatchEmitsAllLiteral() throws IOException {
        byte[] base = new byte[BLOCK];
        for (int i = 0; i < BLOCK; i++) base[i] = (byte) (i * 3 + 7);
        int actualWeak = RollingHash.weakChecksum(base, 0, BLOCK);
        // A signature whose weak hash matches the source block but whose strong hash does not.
        FileSignatures sigs =
                new FileSignatures("x", BLOCK, 1, BLOCK, List.of(new BlockSignature(0, actualWeak, new byte[16])));
        byte[] delta = DeltaEncoder.encode(base, sigs);
        assertTrue(noCopyToken(delta), "no strong match -> delta should be all-literal");
        assertArrayEquals(base, DeltaDecoder.decode(base, delta));
    }

    private boolean noCopyToken(byte[] delta) {
        for (int i = 17; i < delta.length; ) {
            int tag = delta[i] & 0xFF;
            if (tag == DeltaCodec.TAG_COPY) return false;
            else if (tag == DeltaCodec.TAG_LITERAL) {
                int len = ((delta[i + 1] & 0xFF) << 24) | ((delta[i + 2] & 0xFF) << 16)
                        | ((delta[i + 3] & 0xFF) << 8) | (delta[i + 4] & 0xFF);
                i += 5 + len;
            } else {
                throw new AssertionError("unexpected tag " + tag);
            }
        }
        return true;
    }

    // ---------- DeltaDecoder validation branches ----------

    private byte[] header(int blockSize, long sourceSize) {
        ByteBuffer b = ByteBuffer.allocate(17);
        b.put(DeltaCodec.MAGIC);
        b.put((byte) DeltaCodec.VERSION);
        b.putInt(blockSize);
        b.putLong(sourceSize);
        return b.array();
    }

    private byte[] copyToken(int blockIndex, int length) {
        ByteBuffer b = ByteBuffer.allocate(9);
        b.put((byte) DeltaCodec.TAG_COPY);
        b.putInt(blockIndex);
        b.putInt(length);
        return b.array();
    }

    private byte[] literalHeader(int length) {
        ByteBuffer b = ByteBuffer.allocate(5);
        b.put((byte) DeltaCodec.TAG_LITERAL);
        b.putInt(length);
        return b.array();
    }

    private byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) total += p.length;
        byte[] out = new byte[total];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }

    @Test
    void decode_emptyOrNullDeltaThrows() {
        assertThrows(IOException.class, () -> DeltaDecoder.decode(new byte[8], new byte[0]));
        assertThrows(IOException.class, () -> DeltaDecoder.decode(new byte[8], null));
    }

    @Test
    void decode_badMagicThrows() {
        byte[] h = header(64, 0);
        h[0] = 0; // corrupt magic
        assertThrows(IOException.class, () -> DeltaDecoder.decode(new byte[8], h));
    }

    @Test
    void decode_badVersionThrows() {
        byte[] d = concat(new byte[] {DeltaCodec.MAGIC[0], DeltaCodec.MAGIC[1], DeltaCodec.MAGIC[2],
                        DeltaCodec.MAGIC[3], 9, 0, 0, 0, 64, 0, 0, 0, 0, 0, 0, 0, 0});
        assertThrows(IOException.class, () -> DeltaDecoder.decode(new byte[8], d));
    }

    @Test
    void decode_nonPositiveBlockSizeThrows() {
        assertThrows(IOException.class, () -> DeltaDecoder.decode(new byte[8], header(0, 0)));
    }

    @Test
    void decode_negativeSourceSizeThrows() {
        assertThrows(IOException.class, () -> DeltaDecoder.decode(new byte[8], header(64, -1L)));
    }

    @Test
    void decode_sourceSizeAboveIntMaxUsesFallbackCapacity() throws IOException {
        long huge = (long) Integer.MAX_VALUE + 5;
        byte[] d = concat(header(64, huge), literalHeader(0));
        // An empty literal reconstructs to nothing despite the huge declared sourceSize.
        assertEquals(0, DeltaDecoder.decode(new byte[0], d).length);
    }

    @Test
    void decode_negativeCopyFieldsThrow() {
        // blockIndex < 0 (length non-negative): first condition of the guard.
        assertThrows(
                IOException.class,
                () -> DeltaDecoder.decode(new byte[64], concat(header(64, 0), copyToken(-1, 1))));
        // length < 0 (blockIndex non-negative): second condition of the guard.
        assertThrows(
                IOException.class,
                () -> DeltaDecoder.decode(new byte[64], concat(header(64, 0), copyToken(0, -1))));
    }

    @Test
    void decode_copyOutOfBoundsThrows() {
        // blockIndex 5 with blockSize 64 -> offset 320, exceeds an 8-byte existing file.
        byte[] d = concat(header(64, 0), copyToken(5, 64));
        assertThrows(IOException.class, () -> DeltaDecoder.decode(new byte[8], d));
    }

    @Test
    void decode_negativeLiteralLengthThrows() {
        byte[] d = concat(header(64, 0), literalHeader(-1));
        assertThrows(IOException.class, () -> DeltaDecoder.decode(new byte[8], d));
    }

    @Test
    void decode_unknownTokenTagThrows() {
        byte[] d = concat(header(64, 0), new byte[] {0x55});
        assertThrows(IOException.class, () -> DeltaDecoder.decode(new byte[8], d));
    }

    // ---------- SignatureSet validation + accessors ----------

    private byte[] sigSetHeader(byte version, int count) {
        ByteBuffer b = ByteBuffer.allocate(9);
        b.put(SignatureSetBytes.MAGIC);
        b.put(version);
        b.putInt(count);
        return b.array();
    }

    // SignatureSet's MAGIC is package-private; mirror it here for test byte crafting.
    private static final class SignatureSetBytes {
        static final byte[] MAGIC = new byte[] {0x53, 0x47, 0x53, 0x00};
    }

    @Test
    void signatureSet_badVersionThrows() throws IOException {
        byte[] d = sigSetHeader((byte) 9, 0);
        assertThrows(IOException.class, () -> SignatureSet.fromBytes(d));
    }

    @Test
    void signatureSet_negativeCountThrows() {
        ByteBuffer b = ByteBuffer.allocate(9);
        b.put(SignatureSetBytes.MAGIC);
        b.put((byte) 1);
        b.putInt(-1);
        assertThrows(IOException.class, () -> SignatureSet.fromBytes(b.array()));
    }

    @Test
    void signatureSet_negativeEntryLengthThrows() {
        ByteBuffer b = ByteBuffer.allocate(13);
        b.put(SignatureSetBytes.MAGIC);
        b.put((byte) 1);
        b.putInt(1); // count
        b.putInt(-1); // entryLen
        assertThrows(IOException.class, () -> SignatureSet.fromBytes(b.array()));
    }

    @Test
    void signatureSet_entriesAccessor() throws IOException {
        FileSignatures fs = new FileSignatures("a.bin", 64, 0, 0, List.of());
        SignatureSet set = new SignatureSet(List.of(fs));
        assertEquals(1, set.entries().size());
        // size/isEmpty/get already covered elsewhere; re-check isEmpty here.
        assertFalse(set.isEmpty());
    }

    // ---------- FileSignatures edge branches ----------

    @Test
    void fileSignatures_pathTooLongThrows() {
        String longPath = "p".repeat(70000);
        FileSignatures fs = new FileSignatures(longPath, 64, 0, 0, List.of());
        assertThrows(IOException.class, fs::toBytes);
    }

    @Test
    void fileSignatures_encodedLengthAccessor() throws IOException {
        FileSignatures fs =
                new FileSignatures("a.bin", 64, 2, 128, List.of(
                        new BlockSignature(0, 1, new byte[16]),
                        new BlockSignature(1, 2, new byte[16])));
        // 2 (pathLen) + 5 (path "a.bin") + 4 (blockSize) + 4 (blockCount) + 8 (sourceSize)
        // + 2 blocks * 20 bytes
        assertEquals(2 + 5 + 4 + 4 + 8 + 40, fs.encodedLength());
    }

    // ---------- SignatureUtil branches ----------

    @Test
    void signatureUtil_nonPositiveBlockSizeThrows() {
        assertThrows(IllegalArgumentException.class, () -> SignatureUtil.compute("x", new byte[200], 0));
        assertThrows(IllegalArgumentException.class, () -> SignatureUtil.compute("x", new byte[200], -1));
    }

    @Test
    void signatureUtil_computeFromFileOverload(@TempDir Path tmp) throws IOException {
        // 600 bytes -> adaptive blockSize 512 -> at least one full block.
        byte[] data = new byte[600];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i % 127);
        Path file = tmp.resolve("data.bin");
        Files.write(file, data);
        FileSignatures sigs = SignatureUtil.compute("data.bin", file.toFile());
        assertEquals(SignatureUtil.chooseBlockSize(data.length), sigs.getBlockSize());
        assertTrue(sigs.getBlockCount() > 0);
        assertEquals(data.length, sigs.getSourceSize());
    }
}