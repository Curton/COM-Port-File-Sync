package com.filesync.delta;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * End-to-end delta round-trip tests: build a base (receiver's file), derive signatures, mutate into
 * a source (sender's file), encode a delta, decode it against the base, and assert the
 * reconstruction equals the source. Covers identical, append, prepend-insert, mid-block modify,
 * totally different, and shorter-than-base scenarios.
 */
class DeltaEncoderDecoderTest {

    private static final int BLOCK = 64;

    private byte[] randomBytes(int len, long seed) {
        Random rng = new Random(seed);
        byte[] b = new byte[len];
        rng.nextBytes(b);
        return b;
    }

    /** Compute signatures for the receiver's existing bytes and delta-encode the source. */
    private byte[] encode(byte[] base, byte[] source) throws IOException {
        FileSignatures sigs = SignatureUtil.compute("x", base, BLOCK);
        return DeltaEncoder.encode(source, sigs);
    }

    private byte[] roundTrip(byte[] base, byte[] source) throws IOException {
        byte[] delta = encode(base, source);
        return DeltaDecoder.decode(base, delta);
    }

    @Test
    void identicalFileProducesAllCopies() throws IOException {
        byte[] base = randomBytes(BLOCK * 4, 1);
        byte[] delta = encode(base, base);
        byte[] rebuilt = DeltaDecoder.decode(base, delta);
        assertArrayEquals(base, rebuilt);
        // Delta should be much smaller than the source: header + 4 COPY tokens.
        assertTrue(
                delta.length < base.length / 2, "delta=" + delta.length + " base=" + base.length);
    }

    @Test
    void appendedBytesReuseAllBaseBlocks() throws IOException {
        byte[] base = randomBytes(BLOCK * 3, 2);
        byte[] source = new byte[base.length + 25];
        System.arraycopy(base, 0, source, 0, base.length);
        System.arraycopy(randomBytes(25, 99), 0, source, base.length, 25);

        byte[] delta = encode(base, source);
        byte[] rebuilt = DeltaDecoder.decode(base, delta);
        assertArrayEquals(source, rebuilt);
        assertTrue(delta.length < source.length / 2, "delta=" + delta.length);
    }

    @Test
    void prependOneByteShiftsAllBlocksButStillReconstructs() throws IOException {
        byte[] base = randomBytes(BLOCK * 3, 3);
        byte[] source = new byte[base.length + 1];
        source[0] = (byte) 0xAB;
        System.arraycopy(base, 0, source, 1, base.length);

        byte[] rebuilt = roundTrip(base, source);
        assertArrayEquals(source, rebuilt);
        // Insertion shifts every block; few full-block matches are expected, but correctness holds.
    }

    @Test
    void midBlockModificationReconstructs() throws IOException {
        byte[] base = randomBytes(BLOCK * 4, 4);
        byte[] source = base.clone();
        // Corrupt bytes in the middle of block 1.
        for (int i = BLOCK + 10; i < BLOCK + 18; i++) {
            source[i] = (byte) ~source[i];
        }
        byte[] rebuilt = roundTrip(base, source);
        assertArrayEquals(source, rebuilt);
    }

    @Test
    void completelyDifferentFileFallsBackToAllLiteral() throws IOException {
        byte[] base = randomBytes(BLOCK * 2, 5);
        byte[] source = randomBytes(BLOCK * 2, 6);
        byte[] delta = encode(base, source);
        byte[] rebuilt = DeltaDecoder.decode(base, delta);
        assertArrayEquals(source, rebuilt);
        // No block matches -> delta is roughly source size + overhead.
        assertTrue(delta.length >= source.length, "delta should be ~full size");
    }

    @Test
    void sourceShorterThanBaseReconstructs() throws IOException {
        byte[] base = randomBytes(BLOCK * 3, 7);
        byte[] source = new byte[BLOCK * 2 + 3];
        System.arraycopy(base, 0, source, 0, source.length);
        byte[] rebuilt = roundTrip(base, source);
        assertArrayEquals(source, rebuilt);
    }

    @Test
    void sourceSmallerThanBlockSizeIsAllLiteral() throws IOException {
        byte[] base = randomBytes(BLOCK, 8);
        byte[] source = randomBytes(10, 9);
        byte[] delta = encode(base, source);
        byte[] rebuilt = DeltaDecoder.decode(base, delta);
        assertArrayEquals(source, rebuilt);
    }

    @Test
    void emptySourceProducesEmptyReconstruction() throws IOException {
        byte[] base = randomBytes(BLOCK * 2, 10);
        byte[] delta = encode(base, new byte[0]);
        byte[] rebuilt = DeltaDecoder.decode(base, delta);
        assertEquals(0, rebuilt.length);
    }

    @Test
    void emptySignaturesProduceAllLiteralDelta() throws IOException {
        byte[] base = new byte[0];
        FileSignatures sigs = SignatureUtil.compute("x", base, BLOCK);
        byte[] source = randomBytes(BLOCK * 2 + 5, 11);
        byte[] delta = DeltaEncoder.encode(source, sigs);
        byte[] rebuilt = DeltaDecoder.decode(base, delta);
        assertArrayEquals(source, rebuilt);
    }

    @Test
    void decoderRejectsOutOfBoundsCopy() throws IOException {
        // Build a base with one block and a source that reuses it (guarantees a COPY token),
        // then corrupt the COPY block index so it references a non-existent block.
        byte[] base = randomBytes(BLOCK, 12);
        byte[] source = new byte[BLOCK + 30];
        System.arraycopy(base, 0, source, 0, BLOCK);
        System.arraycopy(randomBytes(30, 77), 0, source, BLOCK, 30);

        byte[] delta = encode(base, source);
        // Header is MAGIC(4)+VERSION(1)+BLOCK_SIZE(4)+SOURCE_SIZE(8) = 17 bytes; first token at 17.
        assertEquals(DeltaCodec.TAG_COPY, delta[17], "expected first token to be a COPY");
        // Overwrite the 4-byte block index (bytes 18..21) with a huge value.
        delta[18] = (byte) 0xFF;
        delta[19] = (byte) 0xFF;
        delta[20] = (byte) 0xFF;
        delta[21] = (byte) 0xFF;
        assertThrows(IOException.class, () -> DeltaDecoder.decode(base, delta));
    }

    @Test
    void isBeneficialThreshold() {
        assertTrue(DeltaEncoder.isBeneficial(100, 1000));
        assertTrue(DeltaEncoder.isBeneficial(799, 1000));
        assertTrue(!DeltaEncoder.isBeneficial(800, 1000));
        assertTrue(!DeltaEncoder.isBeneficial(10, 0));
    }
}
