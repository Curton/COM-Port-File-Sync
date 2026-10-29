package com.filesync.serial;

import com.filesync.plugin.LinkPluginRegistry;
import java.io.IOException;

/**
 * A {@link SerialPortManager} that decides at {@link #open(String)} time whether the requested
 * entry is a real COM port or a {@link com.filesync.plugin.LinkPlugin} endpoint, and forwards every
 * byte-level call to the manager that owns it.
 *
 * <p>This is the only change the protocol stack sees: {@code FileSyncManager}, {@code
 * SyncProtocol}, {@code XModemTransfer} and the settings dialog keep calling the same methods they
 * always did. The routing manager holds the session epoch itself (the base class's counter) and
 * bumps it on open and close, so epoch-fenced loops keep working across delegated sessions — the
 * delegate's own epoch is unrelated noise. That is why {@link #currentEpoch()} is not overridden.
 *
 * <p>Line settings ({@code get/setBaudRate}, data bits, stop bits, parity) always go to the real
 * serial manager: they configure hardware, a plugin endpoint has no line coding, and the settings
 * dialog must keep editing the values a plain COM port will later be opened with.
 */
public class RoutingPortManager extends SerialPortManager {

    private final SerialPortManager systemPort;
    private final LinkPluginRegistry registry;

    /** The manager currently owning the session; null while closed. */
    private volatile SerialPortManager active;

    public RoutingPortManager(SerialPortManager systemPort, LinkPluginRegistry registry) {
        this.systemPort = systemPort;
        this.registry = registry;
    }

    @Override
    public boolean open(String portName) {
        SerialPortManager delegate =
                registry.isPluginPort(portName) ? registry.createPortManager(portName) : systemPort;
        SerialPortManager previous = active;
        if (previous != null && previous != delegate) {
            // A new session begins: the previous delegate must not keep a native handle (and, for
            // a plugin, a link manager thread) alive behind the new one.
            active = null;
            previous.close();
        }
        boolean opened = delegate.open(portName);
        if (opened) {
            active = delegate;
            // A new port session began. The delegate bumped its own epoch too, but loops fence on
            // this manager's counter, so this is the bump they observe.
            bumpSessionEpoch();
        } else if (delegate != systemPort) {
            delegate.close();
        }
        return opened;
    }

    @Override
    public void close() {
        SerialPortManager delegate = active;
        active = null;
        if (delegate != null) {
            delegate.close();
        } else {
            // Nothing was opened through this manager; still mirror the base contract of closing
            // the (idle) default manager so its own state is reset.
            systemPort.close();
        }
        bumpSessionEpoch();
    }

    @Override
    public boolean isOpen() {
        SerialPortManager delegate = active;
        return delegate != null && delegate.isOpen();
    }

    @Override
    public String getPortName() {
        SerialPortManager delegate = active;
        return delegate != null ? delegate.getPortName() : null;
    }

    @Override
    public int available() throws IOException {
        SerialPortManager delegate = active;
        return delegate != null ? delegate.available() : 0;
    }

    @Override
    public int read() throws IOException {
        return requireActive().read();
    }

    @Override
    public int read(byte[] buffer) throws IOException {
        return requireActive().read(buffer);
    }

    @Override
    public byte[] readExact(int length, int timeoutMs) throws IOException {
        return requireActive().readExact(length, timeoutMs);
    }

    @Override
    public String readLine(int timeoutMs) throws IOException {
        return requireActive().readLine(timeoutMs);
    }

    @Override
    public void write(int b) throws IOException {
        requireActive().write(b);
    }

    @Override
    public void write(byte[] data) throws IOException {
        requireActive().write(data);
    }

    @Override
    public void clearInputBuffer() throws IOException {
        SerialPortManager delegate = active;
        if (delegate != null) {
            delegate.clearInputBuffer();
        }
    }

    @Override
    public void setReadTimeout(int timeoutMs) {
        SerialPortManager delegate = active;
        if (delegate != null) {
            delegate.setReadTimeout(timeoutMs);
        }
    }

    @Override
    public int getBaudRate() {
        return systemPort.getBaudRate();
    }

    @Override
    public void setBaudRate(int baudRate) {
        systemPort.setBaudRate(baudRate);
    }

    @Override
    public int getDataBits() {
        return systemPort.getDataBits();
    }

    @Override
    public void setDataBits(int dataBits) {
        systemPort.setDataBits(dataBits);
    }

    @Override
    public int getStopBits() {
        return systemPort.getStopBits();
    }

    @Override
    public void setStopBits(int stopBits) {
        systemPort.setStopBits(stopBits);
    }

    @Override
    public int getParity() {
        return systemPort.getParity();
    }

    @Override
    public void setParity(int parity) {
        systemPort.setParity(parity);
    }

    /** The delegate owning the session, or the base class's "not open" failure. */
    private SerialPortManager requireActive() throws IOException {
        SerialPortManager delegate = active;
        if (delegate == null) {
            throw new IOException("Serial port is not open");
        }
        return delegate;
    }
}
