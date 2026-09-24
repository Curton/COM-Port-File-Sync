package com.filesync.lab.link;

import com.filesync.lab.Trace;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One direction of the emulated wire, end to end: device bytes are inspected by the {@link
 * FrameTap}, mangled by the {@link FaultInjector}, scheduled by the {@link ByteSchedule} and only
 * then handed to the other side (a protocol reader on the inbound side, the real COM port on the
 * outbound side).
 *
 * <p>Both directions run one worker thread. The inbound pump drains the port as fast as the driver
 * allows and only the schedule decides the delivery rate; the outbound writer drips scheduled bytes
 * into the port. Backpressure is real: when the schedule is full the inbound pump stops draining,
 * which blocks the peer's write, exactly like a slow receiver on a real cable.
 */
public final class WireChannel implements AutoCloseable {

    public enum Kind {
        /** Bytes flow from the real port into the tool's protocol reader. */
        INBOUND,
        /** Bytes flow from the tool's protocol writer into the real port. */
        OUTBOUND
    }

    private static final int READY_BYTES = 64 * 1024;
    private static final int PUMP_CHUNK = 16 * 1024;

    private final Kind kind;
    private final Trace.Dir dir;
    private final Trace trace;
    private final WireModel model;
    private final WireStats stats;
    private final ByteSchedule schedule;
    private final FaultInjector faults;
    private final FrameTap tap;

    private final InputStream deviceIn;
    private final OutputStream deviceOut;
    private final Object writeLock = new Object();
    private Thread worker;
    private volatile boolean closed;

    // Inbound consumer buffer: bytes whose wire time has arrived, not yet read by the protocol.
    private final ReentrantLock readyLock = new ReentrantLock();
    private final byte[] readyBuf = new byte[READY_BYTES];
    private int readyStart;
    private int readyCount;

    private WireChannel(
            Kind kind,
            Trace.Dir dir,
            Trace trace,
            WireModel model,
            WireStats stats,
            List<TamperRule> rules,
            long seed,
            InputStream deviceIn,
            OutputStream deviceOut) {
        this.kind = kind;
        this.dir = dir;
        this.trace = trace;
        this.model = model;
        this.stats = stats;
        this.schedule = new ByteSchedule(model, model.highWaterBytes());
        this.faults = new FaultInjector(model, stats, seed);
        this.tap = new FrameTap(rules, trace, dir, stats);
        this.deviceIn = deviceIn;
        this.deviceOut = deviceOut;
    }

    /** A channel that reads a real port and delivers its bytes to the tool at the wire rate. */
    public static WireChannel inbound(
            Trace.Dir dir,
            Trace trace,
            WireModel model,
            List<TamperRule> rules,
            long seed,
            InputStream deviceIn) {
        return inbound(dir, trace, model, rules, seed, deviceIn, new WireStats());
    }

    /**
     * As {@link #inbound}, with a caller-owned stats object: a manager that re-attaches to fresh
     * streams (a re-plugged cable) passes the same accumulator to every channel it builds, so the
     * fault counts describe the whole session instead of only the latest conduit.
     */
    public static WireChannel inbound(
            Trace.Dir dir,
            Trace trace,
            WireModel model,
            List<TamperRule> rules,
            long seed,
            InputStream deviceIn,
            WireStats stats) {
        WireChannel channel =
                new WireChannel(
                        Kind.INBOUND, dir, trace, model, stats, rules, seed, deviceIn, null);
        channel.worker = new Thread(channel::pumpLoop, "wire-pump-" + dir.label());
        channel.worker.setDaemon(true);
        channel.worker.start();
        return channel;
    }

    /** A channel that takes the tool's outbound bytes and drips them into a real port. */
    public static WireChannel outbound(
            Trace.Dir dir,
            Trace trace,
            WireModel model,
            List<TamperRule> rules,
            long seed,
            OutputStream deviceOut) {
        return outbound(dir, trace, model, rules, seed, deviceOut, new WireStats());
    }

    /** As {@link #outbound}, with a caller-owned stats object (see the inbound overload). */
    public static WireChannel outbound(
            Trace.Dir dir,
            Trace trace,
            WireModel model,
            List<TamperRule> rules,
            long seed,
            OutputStream deviceOut,
            WireStats stats) {
        WireChannel channel =
                new WireChannel(
                        Kind.OUTBOUND, dir, trace, model, stats, rules, seed, null, deviceOut);
        channel.worker = new Thread(channel::writerLoop, "wire-writer-" + dir.label());
        channel.worker.setDaemon(true);
        channel.worker.start();
        return channel;
    }

    public WireStats stats() {
        return stats;
    }

    /** Adds a frame-tampering rule while the link is live. */
    public void addTamperRule(TamperRule rule) {
        tap.addRule(rule);
    }

    /**
     * The stream the protocol reads from (inbound channels). Mirrors the semantics of a timed-out
     * serial read: {@code -1} means "nothing arrived yet".
     */
    public InputStream readStream() {
        if (kind != Kind.INBOUND) {
            throw new IllegalStateException("not an inbound channel");
        }
        return new InputStream() {
            @Override
            public int read() {
                byte[] one = new byte[1];
                int n = takeReady(one, 0, 1);
                return n > 0 ? one[0] & 0xFF : -1;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                int n = takeReady(b, off, len);
                return n > 0 ? n : (len == 0 ? 0 : -1);
            }

            @Override
            public int available() {
                pumpReady();
                readyLock.lock();
                try {
                    return readyCount;
                } finally {
                    readyLock.unlock();
                }
            }
        };
    }

    /** The stream the protocol writes to (outbound channels). */
    public OutputStream writeStream() {
        if (kind != Kind.OUTBOUND) {
            throw new IllegalStateException("not an outbound channel");
        }
        return new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                writeBytes(new byte[] {(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                if (len == 0) {
                    return;
                }
                writeBytes(b, off, len);
            }

            @Override
            public void flush() {
                // Delivery rate is governed by the wire model, not by flush().
            }
        };
    }

    private void writeBytes(byte[] b, int off, int len) throws IOException {
        if (closed) {
            throw new IOException("link channel is closed");
        }
        synchronized (writeLock) {
            tap.process(
                    b,
                    off,
                    len,
                    new FrameTap.Sink() {
                        @Override
                        public void pass(byte[] data, int o, int n) {
                            offerToWire(data, o, n, 0);
                        }

                        @Override
                        public void passDelayed(byte[] data, int o, int n, long delayMillis) {
                            offerToWire(data, o, n, delayMillis * 1_000_000);
                        }
                    });
        }
    }

    private void offerToWire(byte[] data, int off, int len, long extraDelayNanos) {
        int surviving = faults.apply(data, off, len);
        byte[] src = surviving < 0 ? data : faults.scratch();
        int n = surviving < 0 ? len : surviving;
        schedule.offer(src, 0, n, extraDelayNanos);
        stats.onOffered(n);
    }

    private void pumpLoop() {
        while (!closed) {
            int n;
            try {
                n = deviceIn.read(pumpBuf);
            } catch (IOException e) {
                if (closed) {
                    break;
                }
                // Read timeout from the port driver: keep pumping, the peer may speak later.
                n = -1;
            }
            if (n <= 0) {
                // A real port blocks inside read(); test doubles and edge cases do not.
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }
            acceptAsInput(pumpBuf, n);
        }
    }

    private final byte[] pumpBuf = new byte[PUMP_CHUNK];

    /**
     * Runs bytes through the same path as bytes arriving from the device: frame tampering, byte
     * faults, then the wire schedule. Used by the pump loop and by scenario steps that need to hand
     * the tool's protocol layer bytes "from the other side".
     */
    public void acceptAsInput(byte[] data, int len) {
        if (kind != Kind.INBOUND) {
            throw new IllegalStateException("not an inbound channel");
        }
        if (closed || len == 0) {
            return;
        }
        synchronized (this) {
            tap.process(
                    data,
                    0,
                    len,
                    new FrameTap.Sink() {
                        @Override
                        public void pass(byte[] d, int o, int l) {
                            offerToWire(d, o, l, 0);
                        }

                        @Override
                        public void passDelayed(byte[] d, int o, int l, long delayMillis) {
                            offerToWire(d, o, l, delayMillis * 1_000_000);
                        }
                    });
        }
    }

    private void writerLoop() {
        byte[] buf = new byte[8192];
        try {
            while (!closed) {
                long now = System.nanoTime();
                int n = schedule.takeDue(buf, 0, buf.length, now);
                if (n > 0) {
                    deviceOut.write(buf, 0, n);
                    deviceOut.flush();
                    stats.onReleased(n);
                    continue;
                }
                long next = schedule.nextDueNanos();
                if (next == Long.MAX_VALUE) {
                    LockSupport.parkNanos(500_000);
                } else {
                    LockSupport.parkNanos(Math.max(50_000, Math.min(next - now, 1_000_000)));
                }
            }
        } catch (IOException e) {
            if (!closed) {
                trace.wire(dir, "device write failed: " + e.getMessage());
            }
        }
    }

    private void pumpReady() {
        long now = System.nanoTime();
        readyLock.lock();
        try {
            byte[] tmp = new byte[4096];
            while (READY_BYTES - readyCount >= tmp.length) {
                int n = schedule.takeDue(tmp, 0, tmp.length, now);
                if (n == 0) {
                    break;
                }
                int space = READY_BYTES - readyCount;
                int first = Math.min(n, space);
                writeIntoReady(tmp, first);
                stats.onReleased(first);
                if (first < n) {
                    // Should not happen given the loop guard; drop the remainder defensively.
                    trace.wire(dir, "ready buffer overflow, dropped " + (n - first) + " bytes");
                }
            }
        } finally {
            readyLock.unlock();
        }
    }

    private void writeIntoReady(byte[] src, int n) {
        int at = (readyStart + readyCount) % READY_BYTES;
        int firstChunk = Math.min(n, READY_BYTES - at);
        System.arraycopy(src, 0, readyBuf, at, firstChunk);
        if (firstChunk < n) {
            System.arraycopy(src, firstChunk, readyBuf, 0, n - firstChunk);
        }
        readyCount += n;
    }

    private int takeReady(byte[] dst, int off, int max) {
        pumpReady();
        readyLock.lock();
        try {
            int n = Math.min(max, readyCount);
            if (n == 0) {
                return 0;
            }
            int firstChunk = Math.min(n, READY_BYTES - readyStart);
            System.arraycopy(readyBuf, readyStart, dst, off, firstChunk);
            if (firstChunk < n) {
                System.arraycopy(readyBuf, 0, dst, off + firstChunk, n - firstChunk);
            }
            readyStart = (readyStart + n) % READY_BYTES;
            readyCount -= n;
            return n;
        } finally {
            readyLock.unlock();
        }
    }

    /** Clears everything in flight; the wire goes silent from here. */
    public void silence() {
        schedule.clear();
        readyLock.lock();
        try {
            readyStart = 0;
            readyCount = 0;
        } finally {
            readyLock.unlock();
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        schedule.close();
        if (worker != null) {
            worker.interrupt();
        }
    }

    /** A short human-readable state line for the console. */
    public String describe() {
        return String.format("%s %s", dir.label(), stats.summary());
    }
}
