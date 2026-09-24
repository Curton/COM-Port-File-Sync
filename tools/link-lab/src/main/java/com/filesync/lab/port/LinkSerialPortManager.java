package com.filesync.lab.port;

import com.fazecast.jSerialComm.SerialPort;
import com.filesync.lab.Trace;
import com.filesync.lab.link.TamperRule;
import com.filesync.lab.link.WireChannel;
import com.filesync.lab.link.WireModel;
import com.filesync.lab.link.WireStats;
import com.filesync.serial.SerialPortManager;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * A {@link SerialPortManager} whose traffic runs through a {@link WireChannel}: the app believes it
 * talks to a real COM port, while every byte is paced, fault-injected and frame-tampered by the
 * link emulator. All I/O methods keep the behaviour of the real manager (byte-at-a-time line reads
 * with a deadline, sliding inactivity windows, {@code -1} on read timeout) so the protocol stack
 * cannot tell the difference - except for the speed and reliability of the link.
 *
 * <p>Two constructors exist: the production one opens a real port via jSerialComm (the peer end of
 * a virtual COM pair), and a package-visible one that attaches to arbitrary device streams for
 * tests and self-tests.
 */
public final class LinkSerialPortManager extends SerialPortManager {

    private static final int DEFAULT_TIMEOUT_MS = 5000;
    private static final int POLL_INTERVAL_MS = 1;

    private final Trace trace;
    private final WireModel model;
    private final List<TamperRule> inboundRules;
    private final List<TamperRule> outboundRules;
    private final long seed;

    private SerialPort serialPort;
    private String portName;
    private WireChannel inbound;
    private WireChannel outbound;
    private WireStats inboundStats;
    private WireStats outboundStats;
    private InputStream deviceIn;
    private OutputStream deviceOut;
    private int readTimeoutMs = DEFAULT_TIMEOUT_MS;
    private volatile boolean open;

    public LinkSerialPortManager(
            Trace trace,
            WireModel model,
            List<TamperRule> inboundRules,
            List<TamperRule> outboundRules,
            long seed) {
        super(model.baudRate(), 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        this.trace = trace;
        this.model = model;
        this.inboundRules = inboundRules;
        this.outboundRules = outboundRules;
        this.seed = seed;
    }

    /** Attaches the manager to existing streams without touching real hardware. */
    LinkSerialPortManager(
            Trace trace,
            WireModel model,
            List<TamperRule> inboundRules,
            List<TamperRule> outboundRules,
            long seed,
            InputStream deviceIn,
            OutputStream deviceOut,
            String name) {
        this(trace, model, inboundRules, outboundRules, seed);
        this.portName = name;
        attachChannels(deviceIn, deviceOut);
        this.open = true;
    }

    @Override
    public boolean open(String portName) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                SerialPort port = SerialPort.getCommPort(portName);
                port.setBaudRate(getBaudRate());
                port.setNumDataBits(8);
                port.setNumStopBits(SerialPort.ONE_STOP_BIT);
                port.setParity(SerialPort.NO_PARITY);
                port.setComPortTimeouts(
                        SerialPort.TIMEOUT_READ_SEMI_BLOCKING,
                        DEFAULT_TIMEOUT_MS,
                        DEFAULT_TIMEOUT_MS);
                if (port.openPort()) {
                    this.serialPort = port;
                    this.portName = portName;
                    attachChannels(port.getInputStream(), port.getOutputStream());
                    this.open = true;
                    // Same settling drain the real manager performs: drop whatever a previous
                    // session left in the driver buffers before any protocol byte is read.
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    clearInputBuffer();
                    trace.log(Trace.Dir.WIRE, "opened " + portName + " (" + model.summary() + ")");
                    return true;
                }
                port.closePort();
            } catch (Exception e) {
                trace.log(
                        Trace.Dir.WIRE,
                        "open " + portName + " attempt " + attempt + " failed: " + e.getMessage());
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void attachChannels(InputStream deviceIn, OutputStream deviceOut) {
        this.deviceIn = deviceIn;
        this.deviceOut = deviceOut;
        // The accumulators outlive the channels they are handed to, so fault counts cover the
        // whole session across re-attachments (re-plugged cable) instead of just the last conduit.
        if (inboundStats == null) {
            inboundStats = new WireStats();
        }
        if (outboundStats == null) {
            outboundStats = new WireStats();
        }
        this.inbound =
                WireChannel.inbound(
                        Trace.Dir.APP_TO_PEER,
                        trace,
                        model,
                        inboundRules,
                        seed,
                        deviceIn,
                        inboundStats);
        this.outbound =
                WireChannel.outbound(
                        Trace.Dir.PEER_TO_APP,
                        trace,
                        model,
                        outboundRules,
                        seed + 1,
                        deviceOut,
                        outboundStats);
    }

    @Override
    public void close() {
        open = false;
        closeChannels();
        closeDeviceStreams();
        if (serialPort != null && serialPort.isOpen()) {
            serialPort.closePort();
        }
        serialPort = null;
        trace.log(Trace.Dir.WIRE, "closed " + portName);
    }

    /**
     * Simulates an unplugged cable: the channels stop and the streams close, while the manager
     * still reports itself open - exactly how a pulled USB adapter looks to the application, whose
     * next read or write fails and whose connection service then declares the link lost.
     */
    void unplug() {
        closeChannels();
        closeDeviceStreams();
        trace.log(Trace.Dir.WIRE, "unplugged " + portName);
    }

    /** Re-attaches the manager to fresh device streams (a cable that was plugged back in). */
    void reattach(InputStream deviceIn, OutputStream deviceOut) {
        closeChannels();
        closeDeviceStreams();
        this.deviceIn = deviceIn;
        this.deviceOut = deviceOut;
        attachChannels(deviceIn, deviceOut);
        this.open = true;
        trace.log(Trace.Dir.WIRE, "re-attached " + portName);
    }

    private void closeChannels() {
        if (inbound != null) {
            inbound.close();
        }
        if (outbound != null) {
            outbound.close();
        }
        inbound = null;
        outbound = null;
    }

    private void closeDeviceStreams() {
        if (deviceIn != null) {
            try {
                deviceIn.close();
            } catch (IOException ignored) {
                // Nothing useful to do: the channel close above already stops the pumps.
            }
        }
        if (deviceOut != null) {
            try {
                deviceOut.close();
            } catch (IOException ignored) {
                // See above.
            }
        }
        deviceIn = null;
        deviceOut = null;
    }

    @Override
    public boolean isOpen() {
        return open && (serialPort == null || serialPort.isOpen());
    }

    @Override
    public String getPortName() {
        return portName;
    }

    @Override
    public void write(byte[] data) throws IOException {
        write(data, 0, data.length);
    }

    /** Not an override - the real manager only has the one-array form. */
    public void write(byte[] data, int off, int len) throws IOException {
        requireOutbound().write(data, off, len);
    }

    @Override
    public void write(int b) throws IOException {
        requireOutbound().write(b);
    }

    @Override
    public int read(byte[] buffer) throws IOException {
        return requireInbound().read(buffer, 0, buffer.length);
    }

    @Override
    public int read() throws IOException {
        return requireInbound().read();
    }

    @Override
    public byte[] readExact(int length, int timeoutMs) throws IOException {
        InputStream in = requireInbound();
        byte[] buffer = new byte[length];
        int bytesRead = 0;
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (bytesRead < length) {
            if (System.currentTimeMillis() > deadline) {
                throw new IOException(
                        "Read timeout: expected " + length + " bytes, got " + bytesRead);
            }
            int available = in.available();
            if (available > 0) {
                int toRead = Math.min(available, length - bytesRead);
                int read = in.read(buffer, bytesRead, toRead);
                if (read > 0) {
                    bytesRead += read;
                    deadline = System.currentTimeMillis() + timeoutMs;
                }
            } else {
                pollBriefly();
            }
        }
        return buffer;
    }

    @Override
    public String readLine(int timeoutMs) throws IOException {
        InputStream in = requireInbound();
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        long startTime = System.currentTimeMillis();

        while (true) {
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                throw new IOException("Read timeout");
            }
            int b = in.read();
            if (b == -1) {
                if (!isOpen()) {
                    throw new IOException("Serial port closed during read");
                }
                pollBriefly();
                continue;
            }
            if (b == '\n') {
                return baos.toString(StandardCharsets.UTF_8.name());
            }
            if (b != '\r') {
                baos.write(b);
            }
        }
    }

    @Override
    public void writeLine(String line) throws IOException {
        write((line + "\n").getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public int available() throws IOException {
        return open ? requireInbound().available() : 0;
    }

    @Override
    public void clearInputBuffer() throws IOException {
        if (!open || inbound == null) {
            return;
        }
        inbound.silence();
    }

    @Override
    public void setReadTimeout(int timeoutMs) {
        this.readTimeoutMs = timeoutMs;
        if (serialPort != null) {
            serialPort.setComPortTimeouts(
                    SerialPort.TIMEOUT_READ_SEMI_BLOCKING, timeoutMs, timeoutMs);
        }
    }

    public int getReadTimeout() {
        return readTimeoutMs;
    }

    /** The live wire model this port runs on (fault knobs can be turned at runtime). */
    public WireModel model() {
        return model;
    }

    // Settings changed from the app's Settings dialog must reach the real port, not only the
    // bookkeeping field the inherited setters would update.
    @Override
    public void setBaudRate(int baudRate) {
        super.setBaudRate(baudRate);
        if (serialPort != null) {
            serialPort.setBaudRate(baudRate);
        }
    }

    @Override
    public void setDataBits(int dataBits) {
        super.setDataBits(dataBits);
        if (serialPort != null) {
            serialPort.setNumDataBits(dataBits);
        }
    }

    @Override
    public void setStopBits(int stopBits) {
        super.setStopBits(stopBits);
        if (serialPort != null) {
            serialPort.setNumStopBits(stopBits);
        }
    }

    @Override
    public void setParity(int parity) {
        super.setParity(parity);
        if (serialPort != null) {
            serialPort.setParity(parity);
        }
    }

    /** Emulator statistics for the inbound (app -> tool) direction. */
    public WireChannel inboundChannel() {
        return inbound;
    }

    /** Emulator statistics for the outbound (tool -> app) direction. */
    public WireChannel outboundChannel() {
        return outbound;
    }

    /** Adds a tamper rule on one direction while the link is live. */
    public void addTamperRule(TamperRule rule, boolean towardApp) {
        WireChannel channel = towardApp ? outbound : inbound;
        if (channel != null) {
            channel.addTamperRule(rule);
            trace.log(
                    Trace.Dir.WIRE,
                    "tamper rule added on "
                            + (towardApp ? "peer->app" : "app->peer")
                            + ": "
                            + rule.describe());
        }
    }

    /** One poll interval between deadline checks, mirroring the real manager's read loop. */
    private static void pollBriefly() throws IOException {
        try {
            Thread.sleep(POLL_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Read interrupted");
        }
    }

    private OutputStream requireOutbound() throws IOException {
        if (!open || outbound == null) {
            throw new IOException("Serial port is not open");
        }
        return outbound.writeStream();
    }

    private InputStream requireInbound() throws IOException {
        if (!open || inbound == null) {
            throw new IOException("Serial port is not open");
        }
        return inbound.readStream();
    }
}
