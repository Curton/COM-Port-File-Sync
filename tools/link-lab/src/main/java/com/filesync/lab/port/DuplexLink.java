package com.filesync.lab.port;

import com.filesync.lab.Trace;
import com.filesync.lab.link.WireModel;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Two {@link LinkSerialPortManager}s wired back to back inside one JVM: whatever one side writes
 * the other side reads, with both directions paced by one shared {@link WireModel}. This is the
 * hardware-free stand-in for a com0com pair - the app runs its real protocol stack against the
 * tool's real protocol stack, so a regression test never touches a serial driver.
 *
 * <p>{@link #unplug()} and {@link #replug()} model the cable: unplugged, both sides see a dead port
 * (the peer's writes fail, this side's reads see a closed port); re-plugged, both listeners resume
 * over fresh conduits and the peers re-negotiate the session.
 */
public final class DuplexLink implements AutoCloseable {

    private static final int CONDUIT_BYTES = 256 * 1024;

    private final Trace trace;
    private final WireModel model;
    private final LinkSerialPortManager sideA;
    private final LinkSerialPortManager sideB;
    private volatile Conduit toA; // sideB writes here, sideA reads
    private volatile Conduit toB; // sideA writes here, sideB reads

    /** Creates a live pair on a pristine {@link WireModel}. */
    public DuplexLink(Trace trace, WireModel model) {
        this(trace, model, 0x5EEDL);
    }

    public DuplexLink(Trace trace, WireModel model, long seed) {
        this.trace = trace;
        this.model = model;
        this.toA = new Conduit(CONDUIT_BYTES);
        this.toB = new Conduit(CONDUIT_BYTES);
        this.sideA =
                new LinkSerialPortManager(
                        trace,
                        model,
                        List.of(),
                        List.of(),
                        seed,
                        toA.source(),
                        toB.sink(),
                        "LAB-A");
        this.sideB =
                new LinkSerialPortManager(
                        trace,
                        model,
                        List.of(),
                        List.of(),
                        seed + 1,
                        toB.source(),
                        toA.sink(),
                        "LAB-B");
        trace.log(Trace.Dir.WIRE, "duplex link up (" + model.summary() + ")");
    }

    public WireModel model() {
        return model;
    }

    /** The first attached manager; the caller decides which end is "the app". */
    public LinkSerialPortManager sideA() {
        return sideA;
    }

    public LinkSerialPortManager sideB() {
        return sideB;
    }

    /** Both ends lose the link: channels stop and the conduits close. */
    public void unplug() {
        sideA.unplug();
        sideB.unplug();
        toA.close();
        toB.close();
        trace.log(Trace.Dir.WIRE, "link unplugged");
    }

    /** Plugs the cable back in with fresh conduits on both ends. */
    public void replug() {
        Conduit freshA = new Conduit(CONDUIT_BYTES);
        Conduit freshB = new Conduit(CONDUIT_BYTES);
        sideA.reattach(freshA.source(), freshB.sink());
        sideB.reattach(freshB.source(), freshA.sink());
        toA = freshA;
        toB = freshB;
        trace.log(Trace.Dir.WIRE, "link re-plugged");
    }

    @Override
    public void close() {
        sideA.close();
        sideB.close();
        toA.close();
        toB.close();
    }

    /**
     * A bounded byte conduit between the two device ends. Blocking on a full buffer is the
     * driver-buffer half of the backpressure model: the emulator's writer parks here instead of
     * handing bytes to a port that cannot take them.
     */
    private static final class Conduit {
        private final byte[] buffer;
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition spaceFree = lock.newCondition();
        private final Condition dataAvailable = lock.newCondition();
        private int start;
        private int count;
        private boolean closed;

        Conduit(int size) {
            this.buffer = new byte[size];
        }

        InputStream source() {
            return new Source();
        }

        OutputStream sink() {
            return new Sink();
        }

        void close() {
            lock.lock();
            try {
                closed = true;
                spaceFree.signalAll();
                dataAvailable.signalAll();
            } finally {
                lock.unlock();
            }
        }

        private final class Source extends InputStream {
            @Override
            public int read() {
                byte[] one = new byte[1];
                int n = read(one, 0, 1);
                return n > 0 ? one[0] & 0xFF : -1;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (len == 0) {
                    return 0;
                }
                lock.lock();
                try {
                    while (count == 0 && !closed) {
                        dataAvailable.await();
                    }
                    if (count == 0) {
                        return -1; // closed and drained
                    }
                    int first = Math.min(len, count);
                    int toEnd = Math.min(first, buffer.length - start);
                    System.arraycopy(buffer, start, b, off, toEnd);
                    if (toEnd < first) {
                        System.arraycopy(buffer, 0, b, off + toEnd, first - toEnd);
                    }
                    start = (start + first) % buffer.length;
                    count -= first;
                    spaceFree.signalAll();
                    return first;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return -1;
                } finally {
                    lock.unlock();
                }
            }

            @Override
            public int available() {
                lock.lock();
                try {
                    return count;
                } finally {
                    lock.unlock();
                }
            }
        }

        private final class Sink extends OutputStream {
            @Override
            public void write(int b) throws IOException {
                write(new byte[] {(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                int written = 0;
                lock.lock();
                try {
                    while (written < len) {
                        while (count == buffer.length && !closed) {
                            spaceFree.await();
                        }
                        if (closed) {
                            throw new IOException("conduit closed");
                        }
                        int space = buffer.length - count;
                        int chunk = Math.min(len - written, space);
                        int at = (start + count) % buffer.length;
                        int toEnd = Math.min(chunk, buffer.length - at);
                        System.arraycopy(b, off + written, buffer, at, toEnd);
                        if (toEnd < chunk) {
                            System.arraycopy(b, off + written + toEnd, buffer, 0, chunk - toEnd);
                        }
                        count += chunk;
                        written += chunk;
                        dataAvailable.signalAll();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("conduit write interrupted", e);
                } finally {
                    lock.unlock();
                }
            }
        }
    }
}
