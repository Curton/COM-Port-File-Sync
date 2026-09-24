package com.filesync.lab.link;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Byte and frame counters for one emulated channel. The throughput meter starts at the first byte
 * offered and reports the achieved wire rate, so a regression can assert that the model really
 * moved data at the configured speed (and only at that speed).
 */
public final class WireStats {

    private final AtomicLong offeredBytes = new AtomicLong();
    private final AtomicLong releasedBytes = new AtomicLong();
    private final AtomicLong droppedBytes = new AtomicLong();
    private final AtomicLong corruptedBytes = new AtomicLong();
    private final AtomicLong noiseBytes = new AtomicLong();
    private final AtomicLong framesPassed = new AtomicLong();
    private final AtomicLong framesDropped = new AtomicLong();
    private final AtomicLong framesDelayed = new AtomicLong();
    private final AtomicLong framesInjected = new AtomicLong();

    private final AtomicLong meterStartNanos = new AtomicLong();
    private final AtomicLong meterBytes = new AtomicLong();
    private final AtomicLong meterEndNanos = new AtomicLong();

    public void onOffered(int n) {
        offeredBytes.addAndGet(n);
        long now = System.nanoTime();
        meterStartNanos.compareAndSet(0, now);
        meterBytes.addAndGet(n);
        meterEndNanos.set(now);
    }

    public void onReleased(int n) {
        releasedBytes.addAndGet(n);
        long now = System.nanoTime();
        if (meterStartNanos.get() != 0) {
            meterEndNanos.set(now);
        }
    }

    public void onDropped(int n) {
        droppedBytes.addAndGet(n);
    }

    public void onCorrupted(int n) {
        corruptedBytes.addAndGet(n);
    }

    public void onNoise(int n) {
        noiseBytes.addAndGet(n);
    }

    public void onFramePassed() {
        framesPassed.incrementAndGet();
    }

    public void onFrameDropped() {
        framesDropped.incrementAndGet();
    }

    public void onFrameDelayed() {
        framesDelayed.incrementAndGet();
    }

    public void onFrameInjected() {
        framesInjected.incrementAndGet();
    }

    /** Achieved bytes/s since the first byte was offered (0 when idle). */
    public double measuredBytesPerSecond() {
        long bytes = meterBytes.get();
        long start = meterStartNanos.get();
        long end = meterEndNanos.get();
        if (bytes == 0 || start == 0 || end <= start) {
            return 0;
        }
        return bytes * 1_000_000_000.0 / (end - start);
    }

    /** Restarts the throughput meter. */
    public void resetMeter() {
        meterStartNanos.set(0);
        meterBytes.set(0);
        meterEndNanos.set(0);
    }

    public long offeredBytes() {
        return offeredBytes.get();
    }

    public long releasedBytes() {
        return releasedBytes.get();
    }

    public long droppedBytes() {
        return droppedBytes.get();
    }

    public long corruptedBytes() {
        return corruptedBytes.get();
    }

    /** Sums the two byte-fault counters of this and another channel, for a whole-link assertion. */
    public long droppedBytesPlus(WireStats other) {
        return droppedBytes.get() + other.droppedBytes.get();
    }

    public long corruptedBytesPlus(WireStats other) {
        return corruptedBytes.get() + other.corruptedBytes.get();
    }

    public long noiseBytes() {
        return noiseBytes.get();
    }

    public long framesPassed() {
        return framesPassed.get();
    }

    public long framesDropped() {
        return framesDropped.get();
    }

    public long framesDelayed() {
        return framesDelayed.get();
    }

    public long framesInjected() {
        return framesInjected.get();
    }

    public String summary() {
        return String.format(
                "wire: %d B offered, %d B released (%.0f B/s), dropped=%d corrupted=%d noise=%d, frames passed=%d dropped=%d delayed=%d injected=%d",
                offeredBytes.get(),
                releasedBytes.get(),
                measuredBytesPerSecond(),
                droppedBytes.get(),
                corruptedBytes.get(),
                noiseBytes.get(),
                framesPassed.get(),
                framesDropped.get(),
                framesDelayed.get(),
                framesInjected.get());
    }
}
