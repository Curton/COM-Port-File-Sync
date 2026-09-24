package com.filesync.lab.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Byte-level faults: silent loss, bit corruption and noise bursts, all seeded and counted. */
class FaultInjectorTest {

    @Test
    void inactiveFaultsPassTheInputThrough() {
        WireModel model = new WireModel();
        WireStats stats = new WireStats();
        FaultInjector injector = new FaultInjector(model, stats, 1);
        byte[] data = "hello".getBytes(StandardCharsets.ISO_8859_1);
        assertEquals(-1, injector.apply(data, 0, data.length));
        assertEquals(0, stats.droppedBytes());
        assertEquals(0, stats.corruptedBytes());
        assertEquals(0, stats.noiseBytes());
    }

    @Test
    void totalLossRemovesEverything() {
        WireModel model = new WireModel().lossPercent(100);
        WireStats stats = new WireStats();
        FaultInjector injector = new FaultInjector(model, stats, 1);
        byte[] data = new byte[100];
        assertEquals(0, injector.apply(data, 0, data.length));
        assertEquals(100, stats.droppedBytes());
    }

    @Test
    void partialLossKeepsTheRestAndCountsTheDrop() {
        WireModel model = new WireModel().lossPercent(50);
        WireStats stats = new WireStats();
        FaultInjector injector = new FaultInjector(model, stats, 42);
        byte[] data = new byte[1000];
        int surviving = injector.apply(data, 0, data.length);
        assertTrue(surviving > 400 && surviving < 600, "surviving=" + surviving);
        assertEquals(1000 - surviving, stats.droppedBytes());
        assertTrue(injector.scratch().length >= surviving);
    }

    @Test
    void corruptionKeepsTheLengthButChangesBits() {
        WireModel model = new WireModel().corruptPercent(100);
        WireStats stats = new WireStats();
        FaultInjector injector = new FaultInjector(model, stats, 7);
        byte[] data = "the quick brown fox".getBytes(StandardCharsets.ISO_8859_1);
        int surviving = injector.apply(data, 0, data.length);
        assertEquals(data.length, surviving);
        assertEquals(data.length, stats.corruptedBytes());
        assertTrue(!Arrays.equals(data, Arrays.copyOf(injector.scratch(), surviving)));
    }

    @Test
    void noiseBurstsAreAddedOnSchedule() {
        WireModel model = new WireModel().noise(10, 4);
        WireStats stats = new WireStats();
        FaultInjector injector = new FaultInjector(model, stats, 1);
        byte[] data = new byte[40];
        int surviving = injector.apply(data, 0, data.length);
        assertEquals(40 + 4 * 4, surviving);
        assertEquals(16, stats.noiseBytes());
    }

    @Test
    void sameSeedReproducesTheSameBehaviour() {
        byte[] data = new byte[500];
        WireStats statsA = new WireStats();
        FaultInjector a = new FaultInjector(new WireModel().lossPercent(20).corruptPercent(10), statsA, 99);
        int outA = a.apply(data, 0, data.length);

        WireStats statsB = new WireStats();
        FaultInjector b = new FaultInjector(new WireModel().lossPercent(20).corruptPercent(10), statsB, 99);
        int outB = b.apply(data, 0, data.length);

        assertEquals(outA, outB);
        assertEquals(statsA.droppedBytes(), statsB.droppedBytes());
        assertEquals(statsA.corruptedBytes(), statsB.corruptedBytes());
        assertTrue(Arrays.equals(
                Arrays.copyOf(a.scratch(), outA), Arrays.copyOf(b.scratch(), outB)));
    }
}
