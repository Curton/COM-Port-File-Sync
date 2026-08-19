package com.filesync.delta;

/**
 * Adler-style rolling weak checksum used for rsync-style block matching.
 *
 * <p>For a window of {@code n} bytes {@code x0..x(n-1)}:
 *
 * <pre>
 *   s1 = (x0 + x1 + ... + x(n-1)) mod 65536
 *   s2 = (n*x0 + (n-1)*x1 + ... + 1*x(n-1)) mod 65536
 *   weak = (s2 &lt;&lt; 16) | s1
 * </pre>
 *
 * <p>The hash rolls one byte at a time: removing the oldest byte {@code out} and adding a new byte
 * {@code in} updates {@code s1} and {@code s2} in O(1). The 16-bit halves are masked with
 * {@code 0xFFFF}; the {@code (s2 - n*out + s1)} term is masked after the subtraction so negative
 * intermediates wrap correctly.
 */
public final class RollingHash {

    private static final int MASK = 0xFFFF;

    private final int blockLength;
    private int s1;
    private int s2;

    public RollingHash(int blockLength) {
        if (blockLength <= 0) {
            throw new IllegalArgumentException("blockLength must be positive: " + blockLength);
        }
        this.blockLength = blockLength;
        reset();
    }

    /** Reset the accumulator to the empty-window state. */
    public void reset() {
        s1 = 0;
        s2 = 0;
    }

    /** Append one byte while building the initial window. */
    public void update(byte b) {
        int v = b & 0xFF;
        s1 = (s1 + v) & MASK;
        s2 = (s2 + s1) & MASK;
    }

    /**
     * Slide the window by one byte: remove {@code outByte} (oldest) and add {@code inByte} (newest).
     * The window length stays constant at {@code blockLength}.
     */
    public void roll(byte outByte, byte inByte) {
        int out = outByte & 0xFF;
        int in = inByte & 0xFF;
        s1 = (s1 - out + in) & MASK;
        s2 = (s2 - blockLength * out + s1) & MASK;
    }

    /** Current weak checksum value: high 16 bits = s2, low 16 bits = s1. */
    public int value() {
        return (s2 << 16) | s1;
    }

    /** Block length this rolling hash was configured for. */
    public int getBlockLength() {
        return blockLength;
    }

    /**
     * Compute the weak checksum of a fixed block in one pass (used when generating signatures).
     */
    public static int weakChecksum(byte[] data, int off, int len) {
        if (len < 0) {
            throw new IllegalArgumentException("len must be non-negative: " + len);
        }
        RollingHash h = new RollingHash(len);
        for (int i = 0; i < len; i++) {
            h.update(data[off + i]);
        }
        return h.value();
    }
}