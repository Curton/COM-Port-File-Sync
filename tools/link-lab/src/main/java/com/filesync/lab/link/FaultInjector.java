package com.filesync.lab.link;

import java.util.Random;

/**
 * Byte-level faults: silent loss, single-bit corruption and periodic noise bursts, driven by a
 * seeded RNG so a failing regression can be reproduced exactly.
 */
final class FaultInjector {

    private final WireModel model;
    private final WireStats stats;
    private final Random rng;
    private byte[] scratch = new byte[0];
    private long passedSinceNoise;

    FaultInjector(WireModel model, WireStats stats, long seed) {
        this.model = model;
        this.stats = stats;
        this.rng = new Random(seed);
    }

    private boolean anyFaultActive() {
        return model.lossPercent() > 0
                || model.corruptPercent() > 0
                || model.noisePeriodBytes() > 0
                        && model.noiseBurstBytes() > 0;
    }

    /**
     * Applies the configured byte faults to {@code src[off, off+len)} and records them in the
     * stats. Returns the surviving length, or {@code -1} when no fault is active and {@code src}
     * must be used unchanged.
     */
    int apply(byte[] src, int off, int len) {
        if (!anyFaultActive()) {
            return -1;
        }
        double loss = model.lossPercent() / 100.0;
        double corrupt = model.corruptPercent() / 100.0;
        int period = model.noisePeriodBytes();
        int burst = model.noiseBurstBytes();
        int bursts = period > 0 && burst > 0 ? (len + period - 1) / period : 0;
        if (scratch.length < len + bursts * burst) {
            scratch = new byte[len + bursts * burst];
        }
        int dropped = 0;
        int corrupted = 0;
        int w = 0;
        for (int i = 0; i < len; i++) {
            if (loss > 0 && rng.nextDouble() < loss) {
                dropped++;
                continue;
            }
            byte b = src[off + i];
            if (corrupt > 0 && rng.nextDouble() < corrupt) {
                b ^= (byte) (1 << rng.nextInt(8));
                corrupted++;
            }
            scratch[w++] = b;
            if (period > 0 && burst > 0 && ++passedSinceNoise >= period) {
                passedSinceNoise = 0;
                for (int k = 0; k < burst; k++) {
                    scratch[w++] = (byte) rng.nextInt(256);
                }
                stats.onNoise(burst);
            }
        }
        if (dropped > 0) {
            stats.onDropped(dropped);
        }
        if (corrupted > 0) {
            stats.onCorrupted(corrupted);
        }
        return w;
    }

    /** The output of the last {@link #apply} call, valid only when a fault was active. */
    byte[] scratch() {
        return scratch;
    }
}