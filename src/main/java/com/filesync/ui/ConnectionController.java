package com.filesync.ui;

import com.filesync.config.SettingsManager;
import com.filesync.plugin.LinkEndpoint;
import com.filesync.serial.SerialPortManager;
import com.filesync.sync.FileSyncManager;
import java.awt.Color;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/** Connection-related commands and button state transitions. */
public class ConnectionController {
    private static final String DISCONNECT_TEXT = "Disconnect";
    private static final String CONNECTING_TEXT = "Connecting...";
    private static final String DISCONNECTING_TEXT = "Disconnecting...";

    /**
     * How often the port drop-down is re-enumerated while disconnected. An adapter (or a bridge)
     * plugged in after startup must show up without the user pressing Refresh, but the poll must
     * not cost anything while a session is live: it is skipped outside the idle phase.
     */
    private static final long PORT_POLL_INTERVAL_MS = 2000;

    private final JFrame owner;
    private final MainFrameComponents components;
    private final SettingsManager settings;
    private final SerialPortManager serialPort;
    private final FileSyncManager syncManager;
    private final MainFrameState state;
    private final LogController logController;
    private final Runnable updateSyncButtonState;
    private final SettingsDialog settingsDialog;
    private final PortListComposer portListComposer;

    /** Items the drop-down currently holds, so a poll can tell a change from a repeated answer. */
    private volatile List<String> composedPorts = List.of();

    private ScheduledExecutorService portPoller;

    public ConnectionController(
            JFrame owner,
            MainFrameComponents components,
            SettingsManager settings,
            SerialPortManager serialPort,
            FileSyncManager syncManager,
            MainFrameState state,
            LogController logController,
            Runnable updateSyncButtonState,
            SettingsDialog settingsDialog,
            PortListComposer portListComposer) {
        this.owner = owner;
        this.components = components;
        this.settings = settings;
        this.serialPort = serialPort;
        this.syncManager = syncManager;
        this.state = state;
        this.logController = logController;
        this.updateSyncButtonState = updateSyncButtonState;
        this.settingsDialog = settingsDialog;
        this.portListComposer = portListComposer;
    }

    public void initEventHandlers(Runnable onConnectionStatusChangedLabel) {
        components.getRefreshPortsButton().addActionListener(event -> refreshPorts());
        components
                .getSettingsButton()
                .addActionListener(
                        event ->
                                settingsDialog.showDialog(
                                        owner,
                                        settings,
                                        serialPort,
                                        onConnectionStatusChangedLabel,
                                        logController));
        components.getConnectButton().addActionListener(event -> toggleConnection());
    }

    public void refreshPorts() {
        applyComposedPorts(portListComposer.compose());
    }

    /**
     * Rebuilds the drop-down from one composition: items are replaced wholesale, a still-present
     * selection survives, and a lone item is selected (the behaviour a single COM port always had).
     * Must run on the event dispatch thread.
     */
    private void applyComposedPorts(PortListComposer.Composed composed) {
        JComboBox<String> combo = components.getPortComboBox();
        List<String> ports = composed.ports();
        String selected = (String) combo.getSelectedItem();
        combo.removeAllItems();
        for (String port : ports) {
            combo.addItem(port);
        }
        if (selected != null && ports.contains(selected)) {
            combo.setSelectedItem(selected);
        } else if (ports.size() == 1) {
            combo.setSelectedIndex(0);
        }
        composedPorts = ports;
        logComposedPorts(composed);
    }

    private void logComposedPorts(PortListComposer.Composed composed) {
        if (composed.ports().isEmpty()) {
            logController.log("No COM ports found");
            return;
        }
        logController.log("Found " + composed.systemPorts().size() + " COM port(s)");
        if (composed.endpoints().isEmpty()) {
            return;
        }
        logController.log("Found " + composed.endpoints().size() + " plugin endpoint(s)");
        for (LinkEndpoint endpoint : composed.endpoints()) {
            // The description is where a bridge's resolved COM numbers and interface indices show
            // up, which is what a support log needs to tell a data port from a debug console.
            logController.log("  " + endpoint.id() + " (" + endpoint.describe() + ")");
        }
        if (composed.hiddenSystemPortCount() > 0) {
            logController.log(
                    "Hidden " + composed.hiddenSystemPortCount() + " plugin-internal COM port(s)");
        }
    }

    /**
     * Starts the periodic port scan that makes hot-plugged adapters and bridges appear on their
     * own. Only ports are scanned; nothing is connected automatically from here.
     */
    public void startPortPolling() {
        if (portPoller != null) {
            return;
        }
        portPoller =
                Executors.newSingleThreadScheduledExecutor(
                        runnable -> {
                            Thread thread = new Thread(runnable, "PortEnumerator");
                            thread.setDaemon(true);
                            return thread;
                        });
        portPoller.scheduleWithFixedDelay(
                this::pollPortsOnce,
                PORT_POLL_INTERVAL_MS,
                PORT_POLL_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
    }

    /** Stops the periodic scan; called when the window closes. */
    public void stopPortPolling() {
        ScheduledExecutorService poller = portPoller;
        portPoller = null;
        if (poller != null) {
            poller.shutdownNow();
        }
    }

    /**
     * One scan pass on the {@code PortEnumerator} thread. The task is scheduled with a fixed delay,
     * so it must never throw — a failed scan is logged and retried on the next tick. The drop-down
     * is only touched when the composed list actually changed, and only through {@code
     * invokeLater}.
     */
    void pollPortsOnce() {
        if (state.getPhase() != MainFrameState.ConnectionPhase.IDLE) {
            return;
        }
        PortListComposer.Composed composed;
        try {
            composed = portListComposer.compose();
        } catch (RuntimeException e) {
            logController.log("Port scan failed: " + e);
            return;
        }
        if (composed.ports().equals(composedPorts)) {
            return;
        }
        SwingUtilities.invokeLater(
                () -> {
                    if (components.getPortComboBox().isPopupVisible()) {
                        // Rebuilding under an open drop-down would move the item the user is
                        // aiming at. Leaving composedPorts untouched makes the next tick retry.
                        return;
                    }
                    applyComposedPorts(composed);
                });
    }

    public void attemptAutoConnectOnStartup() {
        if (components.getPortComboBox().getItemCount() == 1) {
            String singlePort = components.getPortComboBox().getItemAt(0);
            components.getPortComboBox().setSelectedIndex(0);
            if (portListComposer.isPluginPort(singlePort)) {
                // A half-plugged bridge is a normal intermediate state; the 60 s handshake
                // timeout with its modal error box must not become a startup ritual.
                logController.log(
                        "Only plugin endpoint " + singlePort + " found, connect manually");
                return;
            }

            SwingUtilities.invokeLater(
                    () -> {
                        if (state.getPhase() == MainFrameState.ConnectionPhase.IDLE) {
                            logController.log(
                                    "Only one COM port found ("
                                            + singlePort
                                            + "), auto-connecting...");
                            connect();
                        }
                    });
            return;
        }

        String lastPort = settings.getLastPort();
        if (lastPort == null || lastPort.isEmpty()) {
            return;
        }

        if (!MainFrameComponents.selectPortByName(components, lastPort)) {
            logController.log(
                    "Last used COM port " + lastPort + " not available, skipping auto-connect");
            return;
        }

        if (portListComposer.isPluginPort(lastPort)) {
            // The entry stays selected, so one click on Connect is all it takes.
            logController.log(
                    "Last used port " + lastPort + " is a plugin endpoint, skipping auto-connect");
            return;
        }

        SwingUtilities.invokeLater(
                () -> {
                    if (state.getPhase() == MainFrameState.ConnectionPhase.IDLE) {
                        logController.log("Attempting auto-connect to " + lastPort + "...");
                        connect();
                    }
                });
    }

    private void toggleConnection() {
        // The button label cannot drive the decision: while an attempt is in flight the link is not
        // up yet (or is being torn down), so isConnected() would send a second click into connect()
        // again. The phase is the single-flight lock.
        switch (state.getPhase()) {
            case CONNECTED:
                disconnect();
                return;
            case CONNECTING:
            case DISCONNECTING:
                // An attempt is already running; a second one would race it.
                return;
            case IDLE:
            default:
                connect();
        }
    }

    private void connect() {
        if (state.getPhase() != MainFrameState.ConnectionPhase.IDLE) {
            // Single-flight: auto-connect or a double click must not start a second open on a
            // SerialPortManager whose fields the losing attempt would null out.
            return;
        }
        String selectedPort = (String) components.getPortComboBox().getSelectedItem();
        if (selectedPort == null || selectedPort.isEmpty()) {
            JOptionPane.showMessageDialog(
                    owner, "Please select a COM port", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // Opening the port sleeps between its attempts and calls into the native driver, so it
        // must not run on the event dispatch thread. The connecting state is shown up front and
        // restored if the open fails.
        applyConnectingState();
        Thread openThread =
                new Thread(
                        () -> {
                            boolean opened = serialPort.open(selectedPort);
                            SwingUtilities.invokeLater(
                                    () -> {
                                        if (opened) {
                                            onPortOpened(selectedPort);
                                        } else {
                                            onPortOpenFailed(selectedPort);
                                        }
                                    });
                        },
                        "SerialPortOpen");
        openThread.setDaemon(true);
        openThread.start();
    }

    private void applyConnectingState() {
        state.setPhase(MainFrameState.ConnectionPhase.CONNECTING);
        components.getConnectButton().setText(CONNECTING_TEXT);
        // The native open cannot be interrupted, so there is nothing for a second click to cancel;
        // disabling the button keeps the label honest instead of offering a "Cancel" that connects.
        components.getConnectButton().setEnabled(false);
        components.getStatusLabel().setText("Connecting...");
        components.getStatusLabel().setForeground(Color.ORANGE);
        setPortControlsEnabled(false);
    }

    private void setPortControlsEnabled(boolean enabled) {
        components.getPortComboBox().setEnabled(enabled);
        components.getRefreshPortsButton().setEnabled(enabled);
        components.getSettingsButton().setEnabled(enabled);
        components.getSyncButton().setEnabled(enabled);
        components.getPreviewSyncButton().setEnabled(enabled);
        components.getDirectionButton().setEnabled(enabled);
    }

    private void onPortOpened(String selectedPort) {
        if (state.getPhase() != MainFrameState.ConnectionPhase.CONNECTING) {
            // The attempt was abandoned while the port was opening; do not leave it open.
            serialPort.close();
            return;
        }
        state.setConnected(true);
        components.getConnectButton().setText(DISCONNECT_TEXT);
        components.getConnectButton().setEnabled(true);
        components.getStatusLabel().setText("Connecting...");
        components.getStatusLabel().setForeground(Color.ORANGE);
        setPortControlsEnabled(false);

        settings.setLastPort(selectedPort);
        settings.save();

        logController.log("Connecting to " + selectedPort + "...");
        syncManager.startListening(selectedPort);

        Thread connectThread =
                new Thread(
                        () -> {
                            boolean connected =
                                    syncManager.waitForConnection(
                                            FileSyncManager.getInitialConnectTimeoutMs());
                            SwingUtilities.invokeLater(
                                    () -> {
                                        if (connected) {
                                            components.applyConnectedUi();
                                            updateSyncButtonState.run();
                                            logController.log("Connected to " + selectedPort);
                                        } else {
                                            if (state.getPhase()
                                                    != MainFrameState.ConnectionPhase.CONNECTED) {
                                                // The user already disconnected: that teardown
                                                // owns the link, so this timeout must not stop
                                                // the listener and close the port underneath it.
                                                return;
                                            }
                                            syncManager.stopListening();
                                            serialPort.close();
                                            state.setConnected(false);
                                            components.applyDisconnectedUi(Color.RED);
                                            updateSyncButtonState.run();
                                            if (!syncManager.wasManuallyDisconnected()) {
                                                logController.log(
                                                        "Connection timeout - other side not responding");
                                                JOptionPane.showMessageDialog(
                                                        owner,
                                                        "Connection timeout - other side not responding",
                                                        "Connection Error",
                                                        JOptionPane.ERROR_MESSAGE);
                                            }
                                        }
                                    });
                        },
                        "ConnectionWaiter");
        connectThread.setDaemon(true);
        connectThread.start();
    }

    private void onPortOpenFailed(String selectedPort) {
        state.setConnected(false);
        components.applyDisconnectedUi(Color.RED);
        // The sync controls follow connection state, so let the authoritative method decide
        // instead of enabling them here.
        updateSyncButtonState.run();
        JOptionPane.showMessageDialog(
                owner,
                "Failed to open " + selectedPort,
                "Connection Error",
                JOptionPane.ERROR_MESSAGE);
        logController.log("Failed to connect to " + selectedPort);
    }

    private void disconnect() {
        if (state.getPhase() != MainFrameState.ConnectionPhase.CONNECTED) {
            // Single-flight: a second teardown would run stopListening(), the port close and the
            // executor shutdown concurrently, and a late one could null out the executor a fresh
            // startListening() has just installed.
            return;
        }
        state.setPhase(MainFrameState.ConnectionPhase.DISCONNECTING);
        components.getConnectButton().setText(DISCONNECTING_TEXT);
        components.getConnectButton().setEnabled(false);
        // Teardown waits on the executor's awaitTermination (up to 2 s) and on closing the serial
        // port, so it must not run on the event dispatch thread.
        Thread teardownThread =
                new Thread(
                        () -> {
                            syncManager.disconnect(true);
                            SwingUtilities.invokeLater(this::applyDisconnectedState);
                        },
                        "DisconnectWorker");
        teardownThread.setDaemon(true);
        teardownThread.start();
    }

    private void applyDisconnectedState() {
        state.setPhase(MainFrameState.ConnectionPhase.IDLE);
        components.applyDisconnectedUi(Color.RED);
        updateSyncButtonState.run();
        logController.log("Disconnected");
    }
}
