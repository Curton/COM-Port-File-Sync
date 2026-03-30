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
    private static final String CANCEL_TEXT = "Cancel";

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
                        if (!state.isConnected()) {
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
                    if (!state.isConnected()) {
                        logController.log("Attempting auto-connect to " + lastPort + "...");
                        connect();
                    }
                });
    }

    private void toggleConnection() {
        if (state.isConnected()) {
            disconnect();
        } else {
            connect();
        }
    }

    private void connect() {
        String selectedPort = (String) components.getPortComboBox().getSelectedItem();
        if (selectedPort == null || selectedPort.isEmpty()) {
            JOptionPane.showMessageDialog(
                    owner, "Please select a COM port", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        if (serialPort.open(selectedPort)) {
            state.setConnected(true);
            components.getConnectButton().setText(CANCEL_TEXT);
            components.getStatusLabel().setText("Connecting...");
            components.getStatusLabel().setForeground(Color.ORANGE);
            components.getPortComboBox().setEnabled(false);
            components.getRefreshPortsButton().setEnabled(false);
            components.getSettingsButton().setEnabled(false);
            components.getSyncButton().setEnabled(false);
            components.getPreviewSyncButton().setEnabled(false);
            components.getDirectionButton().setEnabled(false);

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
                                                components
                                                        .getConnectButton()
                                                        .setText(DISCONNECT_TEXT);
                                                components.getStatusLabel().setText("Connected");
                                                components
                                                        .getStatusLabel()
                                                        .setForeground(new Color(0, 128, 0));
                                                components.getDirectionButton().setEnabled(true);
                                                components.getPortComboBox().setEnabled(false);
                                                components
                                                        .getRefreshPortsButton()
                                                        .setEnabled(false);
                                                components.getSettingsButton().setEnabled(false);
                                                updateSyncButtonState.run();
                                                logController.log("Connected to " + selectedPort);
                                            } else {
                                                syncManager.stopListening();
                                                serialPort.close();
                                                state.setConnected(false);
                                                components.getConnectButton().setText(CONNECT_TEXT);
                                                components.getStatusLabel().setText("Disconnected");
                                                components
                                                        .getStatusLabel()
                                                        .setForeground(Color.RED);
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
        } else {
            JOptionPane.showMessageDialog(
                    owner,
                    "Failed to open " + selectedPort,
                    "Connection Error",
                    JOptionPane.ERROR_MESSAGE);
            logController.log("Failed to connect to " + selectedPort);
        }
    }

    private void disconnect() {
        syncManager.disconnect(true);
        state.setConnected(false);
        components.getConnectButton().setText(CONNECT_TEXT);
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
