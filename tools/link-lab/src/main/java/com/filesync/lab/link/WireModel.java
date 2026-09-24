package com.filesync.lab.link;

/**
 * The physical characteristics of the emulated serial link. All fields are live: a regression
 * scenario can change them mid-run (slow the link down, start dropping bytes) and the next byte
 * crosses under the new values.
 *
 * <p>Throughput follows the serial framing, not the raw baud number: at 8N1 each byte occupies
 * {@code 1 + 8 + 1 = 10} bit times, so 115200 baud carries 11520 bytes/s.
 */
public final class WireModel {

    private volatile int baudRate = 115200;
    private volatile int bitsPerByte = 10;
    private volatile long latencyMillis = 2;
    private volatile long jitterMillis = 1;
    private volatile double lossPercent = 0;
    private volatile double corruptPercent = 0;
    private volatile int noisePeriodBytes = 0;
    private volatile int noiseBurstBytes = 0;
    private volatile int highWaterBytes = 1 << 20;

    /** Creates an 115200 8N1 link with a small default latency, no loss. */
    public WireModel() {}

    public WireModel baud(int baud) {
        if (baud <= 0) {
            throw new IllegalArgumentException("baud must be positive: " + baud);
        }
        this.baudRate = baud;
        return this;
    }

    /**
     * Sets the frame format so the byte rate matches the serial timing on the wire:
     * {@code bitsPerByte = 1 start + dataBits + (parity ? 1 : 0) + stopBits}.
     */
    public WireModel framing(int dataBits, boolean parity, int stopBits) {
        this.bitsPerByte = 1 + dataBits + (parity ? 1 : 0) + stopBits;
        return this;
    }

    /** One-way propagation delay added to every write batch. */
    public WireModel latencyMillis(long ms) {
        this.latencyMillis = Math.max(0, ms);
        return this;
    }

    /** Random extra delay in {@code [0, jitterMillis)} per write batch. */
    public WireModel jitterMillis(long ms) {
        this.jitterMillis = Math.max(0, ms);
        return this;
    }

    /** Percent of bytes silently dropped on the wire (applied after frame tampering). */
    public WireModel lossPercent(double pct) {
        this.lossPercent = Math.max(0, Math.min(100, pct));
        return this;
    }

    /** Percent of bytes delivered with one flipped bit. */
    public WireModel corruptPercent(double pct) {
        this.corruptPercent = Math.max(0, Math.min(100, pct));
        return this;
    }

    /** Emits a noise burst every {@code period} passing bytes; 0 disables noise. */
    public WireModel noise(int periodBytes, int burstBytes) {
        this.noisePeriodBytes = periodBytes;
        this.noiseBurstBytes = burstBytes;
        return this;
    }

    /** How many bytes may sit unscheduled before the writer is blocked (driver buffer model). */
    public WireModel highWaterBytes(int bytes) {
        this.highWaterBytes = Math.max(4096, bytes);
        return this;
    }

    public int baudRate() {
        return baudRate;
    }

    public long latencyMillis() {
        return latencyMillis;
    }

    public long jitterMillis() {
        return jitterMillis;
    }

    public double lossPercent() {
        return lossPercent;
    }

    public double corruptPercent() {
        return corruptPercent;
    }

    public int noisePeriodBytes() {
        return noisePeriodBytes;
    }

    public int noiseBurstBytes() {
        return noiseBurstBytes;
    }

    public int highWaterBytes() {
        return highWaterBytes;
    }

    /** Bytes per second the link can carry at the current baud and frame format. */
    public double bytesPerSecond() {
        return baudRate / (double) bitsPerByte;
    }

    /** Wire time of a single byte, in nanoseconds. */
    public double nanosPerByte() {
        return bitsPerByte * 1_000_000_000.0 / baudRate;
    }

    public String summary() {
        return String.format(
                "baud=%d (%.0f B/s @ %d bits/byte), latency=%dms jitter=%dms loss=%.3f%% corrupt=%.3f%% noise=%d/%d",
                baudRate,
                bytesPerSecond(),
                bitsPerByte,
                latencyMillis,
                jitterMillis,
                lossPercent,
                corruptPercent,
                noisePeriodBytes,
                noiseBurstBytes);
    }
}
