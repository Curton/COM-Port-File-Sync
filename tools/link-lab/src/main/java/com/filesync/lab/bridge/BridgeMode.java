package com.filesync.lab.bridge;

import com.filesync.lab.Trace;
import com.filesync.lab.link.TamperRule;
import com.filesync.lab.link.WireChannel;
import com.filesync.lab.link.WireModel;
import com.filesync.lab.port.LinkSerialPortManager;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Bridge mode: the tool becomes the cable. Two real COM ports are opened (the two ends of two
 * virtual-port pairs) and every byte is relayed through the emulated wire, so two real instances
 * of the application talk to each other over a link that behaves like a slow, lossy serial line.
 *
 * <p>This is the closest setup to a genuine two-machine regression: no protocol code of the
 * application is replaced, only the wire. Peer mode remains the better choice when the goal is a
 * deterministic, scripted counterpart.
 */
public final class BridgeMode implements AutoCloseable {

    private final LinkSerialPortManager sideA;
    private final LinkSerialPortManager sideB;
    private final Trace trace;
    private final Thread aToB;
    private final Thread bToA;
    private volatile boolean closed;

    private BridgeMode(Trace trace, WireModel model, String portA, String portB) throws IOException {
        this.trace = trace;
        List<TamperRule> noRules = List.of();
        this.sideA = new LinkSerialPortManager(trace, model, noRules, noRules, 0xB1A1);
        this.sideB = new LinkSerialPortManager(trace, model, noRules, noRules, 0xB1B1);
        if (!sideA.open(portA)) {
            throw new IOException("cannot open bridge port " + portA);
        }
        if (!sideB.open(portB)) {
            sideA.close();
            throw new IOException("cannot open bridge port " + portB);
        }
        this.aToB = relay(sideA, sideB, "bridge-A->B");
        this.bToA = relay(sideB, sideA, "bridge-B->A");
        trace.log(Trace.Dir.WIRE, "bridge " + portA + " <-> " + portB + " (" + model.summary() + ")");
    }

    public static BridgeMode start(Trace trace, WireModel model, String portA, String portB) throws IOException {
        return new BridgeMode(trace, model, portA, portB);
    }

    private Thread relay(LinkSerialPortManager from, LinkSerialPortManager to, String name) {
        Thread thread =
                new Thread(
                        () -> {
                            try {
                                InputStream in = from.inboundChannel().readStream();
                                OutputStream out = to.outboundChannel().writeStream();
                                byte[] buffer = new byte[16 * 1024];
                                while (!closed) {
                                    int n = in.read(buffer, 0, buffer.length);
                                    if (n > 0) {
                                        out.write(buffer, 0, n);
                                    } else {
                                        Thread.sleep(1);
                                    }
                                }
                            } catch (Exception e) {
                                if (!closed) {
                                    trace.log(Trace.Dir.WIRE, name + " relay stopped: " + e.getMessage());
                                }
                            }
                        },
                        name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * Adds a tamper rule on one direction of the bridge. {@code aToB} targets frames travelling
     * from side A to side B, which physically means the bytes B is about to receive.
     */
    public void addTamperRule(TamperRule rule, boolean aToB) {
        if (aToB) {
            sideB.addTamperRule(rule, true);
        } else {
            sideA.addTamperRule(rule, true);
        }
    }

    public String stats() {
        return "A->B " + sideA.inboundChannel().stats().summary()
                + "\nB->A " + sideB.inboundChannel().stats().summary();
    }

    /** Live wire knobs for the shared model. */
    public WireModel model() {
        return sideA.model();
    }

    @Override
    public void close() {
        closed = true;
        sideA.close();
        sideB.close();
        trace.log(Trace.Dir.WIRE, "bridge closed");
    }
}
