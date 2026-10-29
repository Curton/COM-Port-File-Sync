package com.filesync.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.filesync.config.SettingsManager;
import com.filesync.plugin.FakeLinkPlugin;
import com.filesync.plugin.LinkPluginRegistry;
import com.filesync.serial.SerialPortManager;
import com.filesync.sync.FileSyncManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The port drop-down as the user sees it: system ports plus plugin endpoints, live updates while
 * disconnected, and no auto-connect into an endpoint (a half-plugged bridge must not turn the 60 s
 * handshake timeout into a startup dialog).
 */
class ConnectionPortRefreshTest {

    private final List<String> systemPorts = new CopyOnWriteArrayList<>(List.of("COM1"));

    @Test
    void refreshShowsSystemPortsFollowedByPluginEndpoints() throws Exception {
        Fixture fixture = fixture(bridgePlugin());

        SwingUtilities.invokeAndWait(fixture.controller()::refreshPorts);

        assertEquals(
                List.of("COM1", "hidlink:A", "hidlink:B"),
                items(fixture),
                "the bridge's own CDC ports (COM4/COM5) must be hidden");
    }

    @Test
    @Timeout(30)
    void thePollRebuildsTheListWhenItChanges() throws Exception {
        Fixture fixture = fixture(bridgePlugin());
        SwingUtilities.invokeAndWait(fixture.controller()::refreshPorts);

        systemPorts.add("COM2");
        fixture.controller().pollPortsOnce();
        flushEdt();

        assertEquals(List.of("COM1", "COM2", "hidlink:A", "hidlink:B"), items(fixture));
    }

    @Test
    @Timeout(30)
    void thePollKeepsTheStillPresentSelection() throws Exception {
        Fixture fixture = fixture(bridgePlugin());
        SwingUtilities.invokeAndWait(fixture.controller()::refreshPorts);
        SwingUtilities.invokeAndWait(
                () -> fixture.components().getPortComboBox().setSelectedItem("COM1"));

        systemPorts.add("COM2");
        fixture.controller().pollPortsOnce();
        flushEdt();

        assertEquals("COM1", fixture.components().getPortComboBox().getSelectedItem());
    }

    @Test
    @Timeout(30)
    void thePollDoesNothingWhileAConnectionIsUp() throws Exception {
        Fixture fixture = fixture(bridgePlugin());
        SwingUtilities.invokeAndWait(fixture.controller()::refreshPorts);
        fixture.state().setPhase(MainFrameState.ConnectionPhase.CONNECTED);

        systemPorts.add("COM2");
        fixture.controller().pollPortsOnce();
        flushEdt();

        assertEquals(List.of("COM1", "hidlink:A", "hidlink:B"), items(fixture));
    }

    @Test
    @Timeout(30)
    void aLonePluginEndpointIsSelectedButNotAutoConnected() throws Exception {
        systemPorts.clear();
        Fixture fixture = fixture(new FakeLinkPlugin("hidlink").endpoint("hidlink:A"));

        SwingUtilities.invokeAndWait(
                () -> {
                    fixture.controller().refreshPorts();
                    fixture.controller().attemptAutoConnectOnStartup();
                });

        assertEquals(1, fixture.components().getPortComboBox().getItemCount());
        assertEquals("hidlink:A", fixture.components().getPortComboBox().getSelectedItem());
        assertEquals(
                0,
                fixture.port().openCalls.get(),
                "a plugin endpoint must only be connected on an explicit click");
    }

    @Test
    @Timeout(30)
    void aSavedPluginEndpointIsSelectedButNotAutoConnected() throws Exception {
        Fixture fixture = fixture(new FakeLinkPlugin("hidlink").endpoint("hidlink:A"));
        fixture.settings().setLastPort("hidlink:A");
        fixture.settings().save();
        systemPorts.add("COM2");

        SwingUtilities.invokeAndWait(
                () -> {
                    fixture.controller().refreshPorts();
                    fixture.controller().attemptAutoConnectOnStartup();
                });

        assertEquals("hidlink:A", fixture.components().getPortComboBox().getSelectedItem());
        assertEquals(0, fixture.port().openCalls.get());
    }

    @Test
    @Timeout(30)
    void aLoneComPortStillAutoConnects() throws Exception {
        // No endpoints: the drop-down holds exactly one real port, the classic auto-connect case.
        Fixture fixture = fixture(new FakeLinkPlugin("hidlink"));

        SwingUtilities.invokeAndWait(
                () -> {
                    fixture.controller().refreshPorts();
                    fixture.controller().attemptAutoConnectOnStartup();
                });

        assertEquals(
                "COM1", awaitOpenedPort(fixture), "one COM port is still the auto-connect case");
    }

    @Test
    @Timeout(30)
    void aSavedComPortStillAutoConnects() throws Exception {
        Fixture fixture = fixture(bridgePlugin());
        fixture.settings().setLastPort("COM1");
        systemPorts.add("COM2");

        SwingUtilities.invokeAndWait(
                () -> {
                    fixture.controller().refreshPorts();
                    fixture.controller().attemptAutoConnectOnStartup();
                });

        assertEquals("COM1", awaitOpenedPort(fixture));
    }

    @Test
    void anEmptyListLogsAndLeavesTheComboEmpty() throws Exception {
        systemPorts.clear();
        Fixture fixture = fixture(new FakeLinkPlugin("hidlink"));

        SwingUtilities.invokeAndWait(fixture.controller()::refreshPorts);

        assertEquals(0, fixture.components().getPortComboBox().getItemCount());
        assertNull(fixture.components().getPortComboBox().getSelectedItem());
    }

    // ------------------------------------------------------------------ fixture

    private FakeLinkPlugin bridgePlugin() {
        return new FakeLinkPlugin("hidlink")
                .endpoint("hidlink:A", "COM5 (MI_00)")
                .endpoint("hidlink:B", "COM4 (MI_02)")
                .hidden("COM4", "COM5");
    }

    private Fixture fixture(FakeLinkPlugin plugin) {
        LineRecordingPortManager port = new LineRecordingPortManager();
        plugin.manager(port);
        MainFrameComponents components = new MainFrameComponents();
        MainFrameState state = new MainFrameState();
        SettingsManager settings = new SettingsManager(true);
        settings.setLastPort("");
        PortListComposer composer =
                new PortListComposer(new LinkPluginRegistry(List.of(plugin)), () -> systemPorts);
        ConnectionController controller =
                new ConnectionController(
                        null,
                        components,
                        settings,
                        port,
                        new CountingSyncManager(port),
                        state,
                        new LogController(new JTextArea()),
                        () -> {},
                        null,
                        composer);
        return new Fixture(controller, components, state, port, settings);
    }

    private record Fixture(
            ConnectionController controller,
            MainFrameComponents components,
            MainFrameState state,
            LineRecordingPortManager port,
            SettingsManager settings) {}

    private static List<String> items(Fixture fixture) {
        List<String> items = new ArrayList<>();
        for (int i = 0; i < fixture.components().getPortComboBox().getItemCount(); i++) {
            items.add(fixture.components().getPortComboBox().getItemAt(i));
        }
        return items;
    }

    private static String awaitOpenedPort(Fixture fixture) throws InterruptedException {
        if (!fixture.port().openEntered.await(10, TimeUnit.SECONDS)) {
            return "no open within 10 s";
        }
        return fixture.port().lastOpened;
    }

    private static void flushEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {});
    }

    /** Port manager that records opens and never fails. */
    private static final class LineRecordingPortManager extends SerialPortManager {
        final AtomicInteger openCalls = new AtomicInteger();
        final CountDownLatch openEntered = new CountDownLatch(1);
        volatile String lastOpened;
        private volatile boolean open;

        @Override
        public boolean open(String portName) {
            openCalls.incrementAndGet();
            lastOpened = portName;
            open = true;
            openEntered.countDown();
            return true;
        }

        @Override
        public void close() {
            open = false;
        }

        @Override
        public boolean isOpen() {
            return open;
        }
    }

    /** Sync manager stub: connects instantly, tears down silently. */
    private static final class CountingSyncManager extends FileSyncManager {
        CountingSyncManager(SerialPortManager port) {
            super(port, new SettingsManager(true));
        }

        @Override
        public void startListening(String portName) {}

        @Override
        public boolean waitForConnection(long timeoutMs) {
            return true;
        }

        @Override
        public void disconnect(boolean notifyRemote) {}

        @Override
        public boolean wasManuallyDisconnected() {
            return true;
        }
    }
}
