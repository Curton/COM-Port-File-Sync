package com.filesync.lab.link;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A byte queue that releases its contents at a fixed wire rate, modelling the serial line itself.
 *
 * <p>Every byte carries the wire time at which it should reach the other end. Offering bytes never
 * runs ahead of the configured rate: the queue keeps a wire clock and a byte offered now leaves at
 * {@code max(wireClock, now) + latency + byteIndex * nanosPerByte}, so bursts are smoothed and the
 * long-run rate equals {@link WireModel#bytesPerSecond()} exactly. When the queue holds more than
 * the configured high-water mark the offering thread blocks, which is how a real driver backpressures
 * the sender when the receiver cannot keep up.
 */
final class ByteSchedule {

    private static final int MAX_SLICE = 64 * 1024;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition spaceFree = lock.newCondition();
    private final WireModel model;
    private final int capacity;
    private final byte[] data;
    private final long[] releaseAt;

    private int head;
    private int count;
    private long wireClockNanos;
    private volatile boolean closed;

    ByteSchedule(WireModel model, int capacityBytes) {
        this.model = model;
        this.capacity = Math.max(MAX_SLICE * 2, capacityBytes);
        this.data = new byte[capacity];
        this.releaseAt = new long[capacity];
    }

    /**
     * Schedules {@code len} bytes at the current wire rate, optionally held back by an extra
     * delay. Blocks while the queue is full; returns the number of bytes actually scheduled
     * (fewer than {@code len} only when the channel is closed).
     */
    int offer(byte[] src, int off, int len, long extraDelayNanos) {
        int written = 0;
        long delay = extraDelayNanos;
        while (written < len) {
            int slice = Math.min(len - written, MAX_SLICE);
            lock.lock();
            try {
                while (!closed && count + slice > capacity) {
                    // Bounded wait so an interrupted caller (test timeout, shutdown) always
                    // escapes instead of parking inside the lock forever.
                    try {
                        spaceFree.await(100, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return written;
                    }
                }
                if (closed) {
                    break;
                }
                long start = Math.max(wireClockNanos, System.nanoTime()) + delay;
                double nanosPerByte = model.nanosPerByte();
                for (int i = 0; i < slice; i++) {
                    int slot = head + count + i;
                    if (slot >= capacity) {
                        slot -= capacity;
                    }
                    data[slot] = src[off + written + i];
                    releaseAt[slot] = start + (long) (i * nanosPerByte);
                }
                count += slice;
                wireClockNanos = start + (long) (slice * nanosPerByte);
                written += slice;
                // The latency belongs to the write batch, not to every slice of it.
                delay = 0;
            } finally {
                lock.unlock();
            }
        }
        return written;
    }

    /** Copies bytes whose wire time has arrived into {@code dst}; returns how many. */
    int takeDue(byte[] dst, int off, int max, long nowNanos) {
        lock.lock();
        try {
            int taken = 0;
            while (taken < max && count > 0 && releaseAt[head] <= nowNanos) {
                dst[off + taken] = data[head];
                head++;
                if (head == capacity) {
                    head = 0;
                }
                count--;
                taken++;
            }
            if (taken > 0) {
                spaceFree.signalAll();
            }
            return taken;
        } finally {
            lock.unlock();
        }
    }

    /** Wire time of the next byte, or {@link Long#MAX_VALUE} when empty. */
    long nextDueNanos() {
        lock.lock();
        try {
            return count == 0 ? Long.MAX_VALUE : releaseAt[head];
        } finally {
            lock.unlock();
        }
    }

    /** Drops everything still pending (the wire is now silent). */
    void clear() {
        lock.lock();
        try {
            head = 0;
            count = 0;
            spaceFree.signalAll();
        } finally {
            lock.unlock();
        }
    }

    void close() {
        lock.lock();
        try {
            closed = true;
            spaceFree.signalAll();
        } finally {
            lock.unlock();
        }
    }
}
