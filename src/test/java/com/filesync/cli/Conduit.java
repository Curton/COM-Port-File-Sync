package com.filesync.cli;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory byte conduit between the two ends of a null-modem pair: one side writes through the
 * offer methods, the other side reads. Reads wait at most the requested timeout and then return -1,
 * mirroring the semi-blocking read timeout of a real jSerialComm port, so protocol-level timeouts
 * keep working. {@link #close()} models a cable pull: buffered bytes drain, then readers see EOF.
 */
final class Conduit {

    private final ArrayDeque<Byte> buffer = new ArrayDeque<>();
    private boolean closed;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();

    void offer(int b) {
        lock.lock();
        try {
            if (closed) {
                return;
            }
            buffer.addLast((byte) b);
            notEmpty.signalAll();
        } finally {
            lock.unlock();
        }
    }

    void offer(byte[] data, int off, int len) {
        lock.lock();
        try {
            if (closed) {
                return;
            }
            for (int i = 0; i < len; i++) {
                buffer.addLast(data[off + i]);
            }
            notEmpty.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Reads one byte, waiting at most {@code timeoutMs}; -1 on timeout or after close and drain.
     */
    int read(long timeoutMs) throws IOException {
        lock.lock();
        try {
            long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (buffer.isEmpty()) {
                if (closed) {
                    return -1;
                }
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    return -1;
                }
                try {
                    notEmpty.awaitNanos(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException();
                }
            }
            return buffer.pollFirst() & 0xFF;
        } finally {
            lock.unlock();
        }
    }

    int available() {
        lock.lock();
        try {
            return buffer.size();
        } finally {
            lock.unlock();
        }
    }

    /** Closes the write end: readers drain buffered bytes, then see EOF. */
    void close() {
        lock.lock();
        try {
            closed = true;
            notEmpty.signalAll();
        } finally {
            lock.unlock();
        }
    }
}
