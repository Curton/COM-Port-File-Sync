package com.filesync.delta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies adaptive block-size selection and file-based signature computation. */
class SignatureUtilTest {

    @Test
    void chooseBlockSizeClampsToRange() {
        assertEquals(512, SignatureUtil.chooseBlockSize(0));
        assertEquals(512, SignatureUtil.chooseBlockSize(1));
        assertEquals(512, SignatureUtil.chooseBlockSize(100));
        // sqrt(1MB) = 1024
        assertEquals(1024, SignatureUtil.chooseBlockSize(1024 * 1024));
        // sqrt(100MB) ~ 10000 -> clamped to 8192
        assertEquals(8192, SignatureUtil.chooseBlockSize(100L * 1024 * 1024));
        assertEquals(8192, SignatureUtil.chooseBlockSize(1L * 1024 * 1024 * 1024));
    }

    @Test
    void computeProducesFullBlocksOnly(@TempDir Path tmp) throws IOException {
        byte[] data = new byte[BLOCK * 3 + 17]; // 17 trailing bytes < one block
        new Random(42).nextBytes(data);
        Path file = tmp.resolve("a.bin");
        Files.write(file, data);

        // Use the explicit-block-size overload so the trailing partial block is excluded.
        byte[] fileBytes = Files.readAllBytes(file);
        FileSignatures sigs = SignatureUtil.compute("a.bin", fileBytes, BLOCK);
        assertEquals(BLOCK, sigs.getBlockSize());
        assertEquals(3, sigs.getBlockCount());
        assertEquals(data.length, sigs.getSourceSize());
        assertEquals(3, sigs.getSignatures().size());
        // Block indices are 0..2, each with a truncated strong hash.
        for (int i = 0; i < sigs.getSignatures().size(); i++) {
            assertEquals(i, sigs.getSignatures().get(i).getBlockIndex());
            assertEquals(
                    BlockSignature.STRONG_HASH_LENGTH,
                    sigs.getSignatures().get(i).getStrongHash().length);
        }
    }

    @Test
    void computeOnSmallFileHasNoFullBlocks(@TempDir Path tmp) throws IOException {
        byte[] data = new byte[10];
        Path file = tmp.resolve("tiny.bin");
        Files.write(file, data);
        FileSignatures sigs = SignatureUtil.compute("tiny.bin", file.toFile());
        assertTrue(sigs.getBlockCount() == 0 || sigs.getSignatures().isEmpty());
    }

    private static final int BLOCK = 64;
}
