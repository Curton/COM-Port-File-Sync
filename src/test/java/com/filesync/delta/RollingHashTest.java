package com.filesync.delta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Random;
import org.junit.jupiter.api.Test;

/** Verifies the rolling weak checksum matches a naive recomputation and rolls correctly. */
class RollingHashTest {

    @Test
    void weakChecksumMatchesNaiveComputation() {
        byte[] data = {10, 20, 30, 40, 50};
        // s1 = 150, s2 = 5*10 + 4*20 + 3*30 + 2*40 + 1*50 = 50+80+90+80+50 = 350
        int expected = (350 << 16) | 150;
        assertEquals(expected, RollingHash.weakChecksum(data, 0, data.length));
    }

    @Test
    void rollingEqualsRebuildAtEveryOffset() {
        Random rng = new Random(12345);
        int blockSize = 8;
        byte[] data = new byte[64];
        rng.nextBytes(data);

        RollingHash rh = new RollingHash(blockSize);
        for (int k = 0; k < blockSize; k++) {
            rh.update(data[k]);
        }
        for (int i = 0; i + blockSize <= data.length; i++) {
            int rolled = rh.value();
            int rebuilt = RollingHash.weakChecksum(data, i, blockSize);
            assertEquals(rebuilt, rolled, "mismatch at offset " + i);
            if (i + blockSize < data.length) {
                rh.roll(data[i], data[i + blockSize]);
            }
        }
    }

    @Test
    void rejectsNonPositiveBlockLength() {
        assertThrows(IllegalArgumentException.class, () -> new RollingHash(0));
        assertThrows(IllegalArgumentException.class, () -> new RollingHash(-1));
    }

    @Test
    void emptyWindowHashIsZero() {
        RollingHash rh = new RollingHash(4);
        assertEquals(0, rh.value());
    }
}