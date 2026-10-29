package com.filesync.serial;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.filesync.plugin.FakeLinkPlugin;
import com.filesync.plugin.LinkPluginRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Routing between the real serial manager and plugin endpoints, plus the session epoch the whole
 * protocol stack fences on.
 */
class RoutingPortManagerTest {

    @Test
    void aPlainPortNameGoesToTheSystemManager() throws IOException {
        RecordingPortManager system = new RecordingPortManager("COM3");
        RoutingPortManager routing = new RoutingPortManager(system, registryOf());

        assertTrue(routing.open("COM3"));
        routing.writeLine("hello");

        assertEquals(1, system.openCalls.get());
        assertTrue(routing.isOpen());
        assertEquals("COM3", routing.getPortName());
        assertEquals("hello\n", system.writtenText());
        assertEquals(42, routing.read());
        assertEquals(2, routing.available());
    }

    @Test
    void aPluginEndpointGoesToTheManagerThePluginCreates() throws IOException {
        RecordingPortManager system = new RecordingPortManager("COM3");
        RecordingPortManager pluginPort = new RecordingPortManager("hidlink:A");
        FakeLinkPlugin bridge =
                new FakeLinkPlugin("hidlink").endpoint("hidlink:A").manager(pluginPort);
        RoutingPortManager routing =
                new RoutingPortManager(system, new LinkPluginRegistry(List.of(bridge)));

        assertTrue(routing.open("hidlink:A"));
        routing.write(new byte[] {1, 2, 3});
        routing.write(9);
        routing.setReadTimeout(1234);

        assertEquals(0, system.openCalls.get());
        assertEquals(List.of("hidlink:A"), bridge.createRequests());
        assertEquals(1, pluginPort.openCalls.get());
        assertEquals("hidlink:A", routing.getPortName());
        assertArrayEquals(new byte[] {1, 2, 3}, pluginPort.writes.get(0));
        assertEquals(9, pluginPort.lastByteWrite);
        assertEquals(1234, pluginPort.readTimeout);
        assertEquals(7, routing.readExact(1, 10).length);
        assertEquals("line", routing.readLine(10));
    }

    @Test
    void everyOpenAndCloseAdvancesTheRoutingEpoch() {
        RecordingPortManager system = new RecordingPortManager("COM3");
        RecordingPortManager pluginPort = new RecordingPortManager("hidlink:B");
        FakeLinkPlugin bridge =
                new FakeLinkPlugin("hidlink").endpoint("hidlink:B").manager(pluginPort);
        RoutingPortManager routing =
                new RoutingPortManager(system, new LinkPluginRegistry(List.of(bridge)));

        long before = routing.currentEpoch();
        assertTrue(routing.open("hidlink:B"));
        long afterOpen = routing.currentEpoch();
        assertTrue(afterOpen > before, "a successful open starts a new session");

        routing.close();
        long afterClose = routing.currentEpoch();
        assertTrue(afterClose > afterOpen, "a close ends the session and must be observable");

        // Closing again is a teardown transition too, exactly like the base class.
        routing.close();
        assertTrue(routing.currentEpoch() > afterClose);

        assertTrue(routing.open("COM3"));
        assertTrue(routing.currentEpoch() > afterClose);
    }

    @Test
    void aFailedOpenNeitherInstallsTheDelegateNorAdvancesTheEpoch() throws IOException {
        RecordingPortManager system = new RecordingPortManager("COM3");
        RecordingPortManager pluginPort = new RecordingPortManager("hidlink:A");
        pluginPort.openResult = false;
        FakeLinkPlugin bridge =
                new FakeLinkPlugin("hidlink").endpoint("hidlink:A").manager(pluginPort);
        RoutingPortManager routing =
                new RoutingPortManager(system, new LinkPluginRegistry(List.of(bridge)));

        long before = routing.currentEpoch();
        assertFalse(routing.open("hidlink:A"));

        assertEquals(before, routing.currentEpoch());
        assertFalse(routing.isOpen());
        assertEquals(1, pluginPort.closeCalls.get(), "a delegate that failed to open is released");
        assertThrows(IOException.class, routing::read);
        assertEquals(0, routing.available());
    }

    @Test
    void reopeningAPluginEndpointRetiresThePreviousDelegate() {
        RecordingPortManager system = new RecordingPortManager("COM3");
        List<RecordingPortManager> created = new CopyOnWriteArrayList<>();
        FakeLinkPlugin bridge =
                new FakeLinkPlugin("hidlink")
                        .endpoint("hidlink:A")
                        .managerFactory(
                                () -> {
                                    RecordingPortManager manager =
                                            new RecordingPortManager("hidlink:A");
                                    created.add(manager);
                                    return manager;
                                });
        RoutingPortManager routing =
                new RoutingPortManager(system, new LinkPluginRegistry(List.of(bridge)));

        assertTrue(routing.open("hidlink:A"));
        assertTrue(routing.open("hidlink:A"));

        assertEquals(2, created.size());
        assertEquals(
                1,
                created.get(0).closeCalls.get(),
                "the first delegate's native handle must not outlive the new session");
        assertEquals(0, created.get(1).closeCalls.get());
    }

    @Test
    void lineSettingsStayOnTheSystemManager() {
        RecordingPortManager system = new RecordingPortManager("COM3");
        RoutingPortManager routing = new RoutingPortManager(system, registryOf());

        routing.setBaudRate(9600);
        routing.setDataBits(7);
        routing.setStopBits(2);
        routing.setParity(1);

        assertEquals(9600, routing.getBaudRate());
        assertEquals(9600, system.getBaudRate());
        assertEquals(7, routing.getDataBits());
        assertEquals(7, system.getDataBits());
        assertEquals(2, routing.getStopBits());
        assertEquals(2, system.getStopBits());
        assertEquals(1, routing.getParity());
        assertEquals(1, system.getParity());
    }

    @Test
    void readingAndWritingWhileClosedFailLikeTheBaseClass() throws IOException {
        RoutingPortManager routing =
                new RoutingPortManager(new RecordingPortManager("COM3"), registryOf());

        assertThrows(IOException.class, routing::read);
        assertThrows(IOException.class, () -> routing.read(new byte[1]));
        assertThrows(IOException.class, () -> routing.readExact(1, 10));
        assertThrows(IOException.class, () -> routing.readLine(10));
        assertThrows(IOException.class, () -> routing.write(1));
        assertThrows(IOException.class, () -> routing.write(new byte[1]));
        // Clearing and setting a read timeout are silent no-ops while nothing is open, like the
        // base class.
        routing.clearInputBuffer();
        routing.setReadTimeout(1000);
        assertEquals(0, routing.available());
    }

    private static LinkPluginRegistry registryOf() {
        return LinkPluginRegistry.empty();
    }

    /** Records what the protocol stack asked for and answers with fixed values. */
    private static final class RecordingPortManager extends SerialPortManager {

        private final String name;
        final AtomicInteger openCalls = new AtomicInteger();
        final AtomicInteger closeCalls = new AtomicInteger();
        final List<byte[]> writes = new CopyOnWriteArrayList<>();
        volatile boolean open;
        volatile boolean openResult = true;
        volatile int lastByteWrite = -1;
        volatile int readTimeout = -1;

        RecordingPortManager(String name) {
            this.name = name;
        }

        String writtenText() {
            StringBuilder text = new StringBuilder();
            for (byte[] chunk : writes) {
                text.append(new String(chunk, StandardCharsets.UTF_8));
            }
            return text.toString();
        }

        @Override
        public boolean open(String portName) {
            openCalls.incrementAndGet();
            open = openResult;
            return openResult;
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            open = false;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public String getPortName() {
            return open ? name : null;
        }

        @Override
        public int available() {
            return 2;
        }

        @Override
        public int read() {
            return 42;
        }

        @Override
        public int read(byte[] buffer) {
            buffer[0] = 42;
            return 1;
        }

        @Override
        public byte[] readExact(int length, int timeoutMs) {
            return new byte[7];
        }

        @Override
        public String readLine(int timeoutMs) {
            return "line";
        }

        @Override
        public void write(int b) {
            lastByteWrite = b;
        }

        @Override
        public void write(byte[] data) {
            writes.add(data.clone());
        }

        @Override
        public void clearInputBuffer() {}

        @Override
        public void setReadTimeout(int timeoutMs) {
            readTimeout = timeoutMs;
        }
    }
}
