package com.filesync.ui;

import com.filesync.config.SettingsManager;
import com.filesync.serial.SerialPortManager;
import com.filesync.sync.FileSyncManager;
import java.awt.Color;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/** Connection-related commands and button state transitions. */
public class ConnectionController {
    private static final String CONNECT_TEXT = "Connect";
    private static final String DISCONNECT_TEXT = "Disconnect";
    private static final String CONNECTING_TEXT = "Connecting...";
    private static final String DISCONNECTING_TEXT = "Disconnecting...";

    private final JFrame owner;
    private final MainFrameComponents components;
    private final SettingsManager settings;
    private final SerialPortManager serialPort;
    private final FileSyncManager syncManager;
    private final MainFrameState state;
    private final LogController logController;
    private final Runnable updateSyncButtonState;
    private final SettingsDialog settingsDialog;

    public ConnectionController(
            JFrame owner,
            MainFrameComponents components,
            SettingsManager settings,
            SerialPortManager serialPort,
            FileSyncManager syncManager,
            MainFrameState state,
            LogController logController,
            Runnable updateSyncButtonState,
            SettingsDialog settingsDialog) {
        this.owner = owner;
        this.components = components;
        this.settings = settings;
        this.serialPort = serialPort;
        this.syncManager = syncManager;
        this.state = state;
        this.logController = logController;
        this.updateSyncButtonState = updateSyncButtonState;
        this.settingsDialog = settingsDialog;
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
        components.getPortComboBox().removeAllItems();
        java.util.List<String> ports = SerialPortManager.getAvailablePorts();
        for (String port : ports) {
            components.getPortComboBox().addItem(port);
        }
        if (ports.isEmpty()) {
            logController.log("No COM ports found");
        } else {
            logController.log("Found " + ports.size() + " COM port(s)");
            if (ports.size() == 1) {
                components.getPortComboBox().setSelectedIndex(0);
            }
        }
    }

    public void attemptAutoConnectOnStartup() {
        if (components.getPortComboBox().getItemCount() == 1) {
            String singlePort = components.getPortComboBox().getItemAt(0);
            components.getPortComboBox().setSelectedIndex(0);

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

        boolean portAvailable = false;
        for (int i = 0; i < components.getPortComboBox().getItemCount(); i++) {
            String portName = components.getPortComboBox().getItemAt(i);
            if (lastPort.equals(portName)) {
                portAvailable = true;
                components.getPortComboBox().setSelectedIndex(i);
                break;
            }
        }

        if (!portAvailable) {
            logController.log(
                    "Last used COM port " + lastPort + " not available, skipping auto-connect");
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
                                            components.getConnectButton().setText(DISCONNECT_TEXT);
                                            components.getStatusLabel().setText("Connected");
                                            components
                                                    .getStatusLabel()
                                                    .setForeground(new Color(0, 128, 0));
                                            components.getDirectionButton().setEnabled(true);
                                            components.getPortComboBox().setEnabled(false);
                                            components.getRefreshPortsButton().setEnabled(false);
                                            components.getSettingsButton().setEnabled(false);
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
                                            components.getConnectButton().setText(CONNECT_TEXT);
                                            components.getConnectButton().setEnabled(true);
                                            components.getStatusLabel().setText("Disconnected");
                                            components.getStatusLabel().setForeground(Color.RED);
                                            components.getPortComboBox().setEnabled(true);
                                            components.getRefreshPortsButton().setEnabled(true);
                                            components.getSettingsButton().setEnabled(true);
                                            components.getDirectionButton().setEnabled(true);
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
        components.getConnectButton().setText(CONNECT_TEXT);
        components.getConnectButton().setEnabled(true);
        components.getStatusLabel().setText("Disconnected");
        components.getStatusLabel().setForeground(Color.RED);
        components.getPortComboBox().setEnabled(true);
        components.getRefreshPortsButton().setEnabled(true);
        components.getSettingsButton().setEnabled(true);
        components.getDirectionButton().setEnabled(true);
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
        components.getConnectButton().setText(CONNECT_TEXT);
        components.getConnectButton().setEnabled(true);
        components.getStatusLabel().setText("Disconnected");
        components.getStatusLabel().setForeground(Color.RED);
        components.getPortComboBox().setEnabled(true);
        components.getRefreshPortsButton().setEnabled(true);
        components.getSettingsButton().setEnabled(true);
        components.getDirectionButton().setEnabled(true);
        updateSyncButtonState.run();
        logController.log("Disconnected");
    }
}
