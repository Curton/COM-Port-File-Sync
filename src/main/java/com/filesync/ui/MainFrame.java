package com.filesync.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;

import com.filesync.config.SettingsManager;
import com.filesync.serial.SerialPortManager;
import com.filesync.sync.FileSyncManager;

/**
 * Main application window for COM Port File Sync.
 * Provides UI for COM port selection, folder selection, sync direction, and sync control.
 */
public class MainFrame extends JFrame implements FileSyncManager.SyncEventListener {

    private static final int WINDOW_WIDTH = 700;
    private static final int WINDOW_HEIGHT = 520;

    // UI Components
    private JComboBox<String> portComboBox;
    private JButton refreshPortsButton;
    private JButton settingsButton;
    private JButton connectButton;
    private JTextField folderTextField;
    private JButton browseFolderButton;
    private JButton directionButton;
    private JButton syncButton;
    private JProgressBar progressBar;
    private JTextArea logTextArea;
    private JLabel statusLabel;
    private JLabel settingsLabel;

    // Application state
    private SerialPortManager serialPort;
    private FileSyncManager syncManager;
    private SettingsManager settings;
    private boolean isSender = true;
    private boolean isConnected = false;

    public MainFrame() {
        initSettings();
        initComponents();
        initSerialPort();
        layoutComponents();
        setupEventHandlers();
        refreshPorts();
        loadSavedState();
    }

    private void initSettings() {
        settings = new SettingsManager();
    }

    private void loadSavedState() {
        // Restore last selected port
        String lastPort = settings.getLastPort();
        if (lastPort != null && !lastPort.isEmpty()) {
            for (int i = 0; i < portComboBox.getItemCount(); i++) {
                if (lastPort.equals(portComboBox.getItemAt(i))) {
                    portComboBox.setSelectedIndex(i);
                    break;
                }
            }
        }

        // Restore last folder
        String lastFolder = settings.getLastFolder();
        if (lastFolder != null && !lastFolder.isEmpty()) {
            File folder = new File(lastFolder);
            if (folder.exists() && folder.isDirectory()) {
                folderTextField.setText(lastFolder);
                syncManager.setSyncFolder(folder);
                updateSyncButtonState();
            }
        }

        // Update settings label
        updateSettingsLabel();
    }

    private void initComponents() {
        setTitle("COM Port File Sync");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(WINDOW_WIDTH, WINDOW_HEIGHT);
        setMinimumSize(new Dimension(650, 450));
        setLocationRelativeTo(null);

        // COM Port components
        portComboBox = new JComboBox<>();
        portComboBox.setPreferredSize(new Dimension(200, 25));

        refreshPortsButton = new JButton("Refresh");
        settingsButton = new JButton("Settings");
        connectButton = new JButton("Connect");

        // Settings display label
        settingsLabel = new JLabel();
        settingsLabel.setFont(settingsLabel.getFont().deriveFont(Font.PLAIN, 11f));
        settingsLabel.setForeground(Color.GRAY);

        // Folder selection components
        folderTextField = new JTextField();
        folderTextField.setEditable(false);
        browseFolderButton = new JButton("Browse...");

        // Direction button
        directionButton = new JButton("A -> B (Sender)");
        directionButton.setFont(directionButton.getFont().deriveFont(Font.BOLD));

        // Sync button
        syncButton = new JButton("Start Sync");
        syncButton.setEnabled(false);

        // Progress bar
        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);

        // Log area
        logTextArea = new JTextArea();
        logTextArea.setEditable(false);
        logTextArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        // Status label
        statusLabel = new JLabel("Disconnected");
        statusLabel.setForeground(Color.RED);
    }

    private void initSerialPort() {
        serialPort = new SerialPortManager(
                settings.getBaudRate(),
                settings.getDataBits(),
                settings.getStopBits(),
                settings.getParity()
        );
        syncManager = new FileSyncManager(serialPort);
        syncManager.setEventListener(this);
    }

    private void layoutComponents() {
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

        // Top panel - Connection settings
        JPanel connectionPanel = new JPanel(new GridBagLayout());
        connectionPanel.setBorder(new TitledBorder("Connection"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);

        gbc.gridx = 0;
        gbc.gridy = 0;
        connectionPanel.add(new JLabel("COM Port:"), gbc);

        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        connectionPanel.add(portComboBox, gbc);

        gbc.gridx = 2;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        connectionPanel.add(refreshPortsButton, gbc);

        gbc.gridx = 3;
        connectionPanel.add(settingsButton, gbc);

        gbc.gridx = 4;
        connectionPanel.add(connectButton, gbc);

        gbc.gridx = 5;
        connectionPanel.add(statusLabel, gbc);

        // Settings display row
        gbc.gridx = 0;
        gbc.gridy = 1;
        connectionPanel.add(new JLabel("Config:"), gbc);

        gbc.gridx = 1;
        gbc.gridwidth = 5;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        connectionPanel.add(settingsLabel, gbc);
        gbc.gridwidth = 1;

        // Folder panel
        JPanel folderPanel = new JPanel(new GridBagLayout());
        folderPanel.setBorder(new TitledBorder("Sync Folder"));
        gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);

        gbc.gridx = 0;
        gbc.gridy = 0;
        folderPanel.add(new JLabel("Folder:"), gbc);

        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        folderPanel.add(folderTextField, gbc);

        gbc.gridx = 2;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        folderPanel.add(browseFolderButton, gbc);

        // Control panel
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 20, 10));
        controlPanel.setBorder(new TitledBorder("Sync Control"));
        controlPanel.add(directionButton);
        controlPanel.add(syncButton);

        // Top section combining connection, folder, and control
        JPanel topSection = new JPanel();
        topSection.setLayout(new BoxLayout(topSection, BoxLayout.Y_AXIS));
        topSection.add(connectionPanel);
        topSection.add(Box.createVerticalStrut(5));
        topSection.add(folderPanel);
        topSection.add(Box.createVerticalStrut(5));
        topSection.add(controlPanel);

        // Progress panel
        JPanel progressPanel = new JPanel(new BorderLayout(5, 5));
        progressPanel.setBorder(new TitledBorder("Progress"));
        progressPanel.add(progressBar, BorderLayout.CENTER);

        // Log panel
        JPanel logPanel = new JPanel(new BorderLayout());
        logPanel.setBorder(new TitledBorder("Log"));
        JScrollPane scrollPane = new JScrollPane(logTextArea);
        scrollPane.setPreferredSize(new Dimension(0, 150));
        logPanel.add(scrollPane, BorderLayout.CENTER);

        // Bottom section
        JPanel bottomSection = new JPanel(new BorderLayout(0, 5));
        bottomSection.add(progressPanel, BorderLayout.NORTH);
        bottomSection.add(logPanel, BorderLayout.CENTER);

        mainPanel.add(topSection, BorderLayout.NORTH);
        mainPanel.add(bottomSection, BorderLayout.CENTER);

        setContentPane(mainPanel);
    }

    private void setupEventHandlers() {
        // Refresh ports button
        refreshPortsButton.addActionListener(e -> refreshPorts());

        // Settings button
        settingsButton.addActionListener(e -> showSettingsDialog());

        // Connect button
        connectButton.addActionListener(e -> toggleConnection());

        // Browse folder button
        browseFolderButton.addActionListener(e -> browseFolder());

        // Direction button
        directionButton.addActionListener(e -> toggleDirection());

        // Sync button
        syncButton.addActionListener(e -> startSync());

        // Window close handler
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                cleanup();
            }
        });
    }

    private void refreshPorts() {
        portComboBox.removeAllItems();
        List<String> ports = SerialPortManager.getAvailablePorts();
        for (String port : ports) {
            portComboBox.addItem(port);
        }
        if (ports.isEmpty()) {
            log("No COM ports found");
        } else {
            log("Found " + ports.size() + " COM port(s)");
        }
    }

    private void toggleConnection() {
        if (isConnected) {
            disconnect();
        } else {
            connect();
        }
    }

    private boolean isConnecting() {
        return isConnected && !syncManager.isConnectionAlive();
    }

    private void connect() {
        String selectedPort = (String) portComboBox.getSelectedItem();
        if (selectedPort == null || selectedPort.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please select a COM port", 
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        if (serialPort.open(selectedPort)) {
            // Show connecting status and disable UI
            isConnected = true;
            connectButton.setText("Cancel");
            statusLabel.setText("Connecting...");
            statusLabel.setForeground(Color.ORANGE);
            portComboBox.setEnabled(false);
            refreshPortsButton.setEnabled(false);
            settingsButton.setEnabled(false);
            syncButton.setEnabled(false);

            // Save the selected port
            settings.setLastPort(selectedPort);
            settings.save();

            log("Connecting to " + selectedPort + "...");

            // Start listening and wait for connection in background thread
            syncManager.startListening();
            
            Thread connectThread = new Thread(() -> {
                boolean connected = syncManager.waitForConnection(
                        FileSyncManager.getInitialConnectTimeoutMs());
                
                SwingUtilities.invokeLater(() -> {
                    if (connected) {
                        connectButton.setText("Disconnect");
                        statusLabel.setText("Connected");
                        statusLabel.setForeground(new Color(0, 128, 0));
                        updateSyncButtonState();
                        log("Connected to " + selectedPort);
                    } else {
                        // Connection timeout - disconnect
                        syncManager.stopListening();
                        serialPort.close();
                        isConnected = false;
                        connectButton.setText("Connect");
                        statusLabel.setText("Disconnected");
                        statusLabel.setForeground(Color.RED);
                        portComboBox.setEnabled(true);
                        refreshPortsButton.setEnabled(true);
                        settingsButton.setEnabled(true);
                        updateSyncButtonState();
                        log("Connection timeout - other side not responding");
                        JOptionPane.showMessageDialog(MainFrame.this, 
                                "Connection timeout - other side not responding", 
                                "Connection Error", JOptionPane.ERROR_MESSAGE);
                    }
                });
            }, "ConnectionWaiter");
            connectThread.setDaemon(true);
            connectThread.start();
        } else {
            JOptionPane.showMessageDialog(this, "Failed to open " + selectedPort, 
                    "Connection Error", JOptionPane.ERROR_MESSAGE);
            log("Failed to connect to " + selectedPort);
        }
    }

    private void disconnect() {
        syncManager.stopListening();
        serialPort.close();
        isConnected = false;
        connectButton.setText("Connect");
        statusLabel.setText("Disconnected");
        statusLabel.setForeground(Color.RED);
        portComboBox.setEnabled(true);
        refreshPortsButton.setEnabled(true);
        settingsButton.setEnabled(true);
        updateSyncButtonState();
        log("Disconnected");
    }

    private void browseFolder() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fileChooser.setDialogTitle("Select Sync Folder");

        // Start from last folder if available
        String lastFolder = settings.getLastFolder();
        if (lastFolder != null && !lastFolder.isEmpty()) {
            File lastDir = new File(lastFolder);
            if (lastDir.exists()) {
                fileChooser.setCurrentDirectory(lastDir);
            }
        }

        if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File selectedFolder = fileChooser.getSelectedFile();
            folderTextField.setText(selectedFolder.getAbsolutePath());
            syncManager.setSyncFolder(selectedFolder);
            updateSyncButtonState();

            // Save the selected folder
            settings.setLastFolder(selectedFolder.getAbsolutePath());
            settings.save();

            log("Selected folder: " + selectedFolder.getAbsolutePath());
        }
    }

    private void showSettingsDialog() {
        JDialog dialog = new JDialog(this, "COM Port Settings", true);
        dialog.setLayout(new BorderLayout(10, 10));
        dialog.setSize(350, 250);
        dialog.setLocationRelativeTo(this);

        JPanel formPanel = new JPanel(new GridBagLayout());
        formPanel.setBorder(new EmptyBorder(15, 15, 15, 15));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        // Baud Rate
        gbc.gridx = 0;
        gbc.gridy = 0;
        formPanel.add(new JLabel("Baud Rate:"), gbc);

        JComboBox<Integer> baudRateCombo = new JComboBox<>();
        for (int rate : SettingsManager.BAUD_RATES) {
            baudRateCombo.addItem(rate);
        }
        baudRateCombo.setSelectedItem(settings.getBaudRate());
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        formPanel.add(baudRateCombo, gbc);

        // Data Bits
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        formPanel.add(new JLabel("Data Bits:"), gbc);

        JComboBox<Integer> dataBitsCombo = new JComboBox<>();
        for (int bits : SettingsManager.DATA_BITS_OPTIONS) {
            dataBitsCombo.addItem(bits);
        }
        dataBitsCombo.setSelectedItem(settings.getDataBits());
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        formPanel.add(dataBitsCombo, gbc);

        // Stop Bits
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        formPanel.add(new JLabel("Stop Bits:"), gbc);

        JComboBox<String> stopBitsCombo = new JComboBox<>(SettingsManager.STOP_BITS_NAMES);
        stopBitsCombo.setSelectedIndex(SettingsManager.getStopBitsIndex(settings.getStopBits()));
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        formPanel.add(stopBitsCombo, gbc);

        // Parity
        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        formPanel.add(new JLabel("Parity:"), gbc);

        JComboBox<String> parityCombo = new JComboBox<>(SettingsManager.PARITY_NAMES);
        parityCombo.setSelectedIndex(SettingsManager.getParityIndex(settings.getParity()));
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        formPanel.add(parityCombo, gbc);

        // Buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton okButton = new JButton("OK");
        JButton cancelButton = new JButton("Cancel");

        okButton.addActionListener(e -> {
            // Save settings
            settings.setBaudRate((Integer) baudRateCombo.getSelectedItem());
            settings.setDataBits((Integer) dataBitsCombo.getSelectedItem());
            settings.setStopBits(SettingsManager.STOP_BITS_VALUES[stopBitsCombo.getSelectedIndex()]);
            settings.setParity(SettingsManager.PARITY_VALUES[parityCombo.getSelectedIndex()]);
            settings.save();

            // Update serial port settings
            serialPort.setBaudRate(settings.getBaudRate());
            serialPort.setDataBits(settings.getDataBits());
            serialPort.setStopBits(settings.getStopBits());
            serialPort.setParity(settings.getParity());

            updateSettingsLabel();
            log("Settings updated: " + getSettingsString());
            dialog.dispose();
        });

        cancelButton.addActionListener(e -> dialog.dispose());

        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);

        dialog.add(formPanel, BorderLayout.CENTER);
        dialog.add(buttonPanel, BorderLayout.SOUTH);
        dialog.setVisible(true);
    }

    private void updateSettingsLabel() {
        settingsLabel.setText(getSettingsString());
    }

    private String getSettingsString() {
        int stopBitsIdx = SettingsManager.getStopBitsIndex(settings.getStopBits());
        int parityIdx = SettingsManager.getParityIndex(settings.getParity());
        return String.format("%d baud, %d data bits, %s stop bits, %s parity",
                settings.getBaudRate(),
                settings.getDataBits(),
                SettingsManager.STOP_BITS_NAMES[stopBitsIdx],
                SettingsManager.PARITY_NAMES[parityIdx]);
    }

    private void toggleDirection() {
        isSender = !isSender;
        updateDirectionButton();
        syncManager.setIsSender(isSender);
        syncManager.notifyDirectionChange();
        log("Direction changed: " + (isSender ? "Sender (A -> B)" : "Receiver (B <- A)"));
    }

    private void updateDirectionButton() {
        if (isSender) {
            directionButton.setText("A -> B (Sender)");
            directionButton.setBackground(new Color(144, 238, 144));
        } else {
            directionButton.setText("B <- A (Receiver)");
            directionButton.setBackground(new Color(173, 216, 230));
        }
    }

    private void startSync() {
        if (!isSender) {
            log("Waiting for sync from sender...");
            return;
        }

        syncButton.setEnabled(false);
        progressBar.setValue(0);
        syncManager.initiateSync();
    }

    private void updateSyncButtonState() {
        boolean canSync = isConnected && syncManager.getSyncFolder() != null && isSender 
                && syncManager.isConnectionAlive();
        syncButton.setEnabled(canSync && !syncManager.isSyncing());
    }

    private void log(String message) {
        SwingUtilities.invokeLater(() -> {
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
            String timestamp = sdf.format(new Date());
            logTextArea.append("[" + timestamp + "] " + message + "\n");
            logTextArea.setCaretPosition(logTextArea.getDocument().getLength());
        });
    }

    private void cleanup() {
        syncManager.stopListening();
        serialPort.close();
    }

    // SyncEventListener implementation

    @Override
    public void onSyncStarted() {
        SwingUtilities.invokeLater(() -> {
            syncButton.setEnabled(false);
            progressBar.setValue(0);
            progressBar.setString("Starting sync...");
        });
    }

    @Override
    public void onSyncComplete() {
        SwingUtilities.invokeLater(() -> {
            progressBar.setValue(100);
            progressBar.setString("Sync complete");
            updateSyncButtonState();
        });
    }

    @Override
    public void onFileProgress(int currentFile, int totalFiles, String fileName) {
        SwingUtilities.invokeLater(() -> {
            int percent = (int) ((double) currentFile / totalFiles * 100);
            progressBar.setValue(percent);
            progressBar.setString("File " + currentFile + "/" + totalFiles + ": " + fileName);
        });
    }

    @Override
    public void onTransferProgress(int currentBlock, int totalBlocks, long bytesTransferred, double speedBytesPerSec) {
        SwingUtilities.invokeLater(() -> {
            String speedStr = formatSpeed(speedBytesPerSec);
            if (totalBlocks > 0) {
                int percent = (int) ((double) currentBlock / totalBlocks * 100);
                progressBar.setString("Block " + currentBlock + "/" + totalBlocks + " - " + speedStr);
            } else {
                progressBar.setString("Block " + currentBlock + " - " + speedStr);
            }
        });
    }

    /**
     * Format transfer speed to human-readable string (B/s, KB/s, MB/s)
     */
    private String formatSpeed(double bytesPerSec) {
        if (bytesPerSec < 1024) {
            return String.format("%.0f B/s", bytesPerSec);
        } else if (bytesPerSec < 1024 * 1024) {
            return String.format("%.1f KB/s", bytesPerSec / 1024);
        } else {
            return String.format("%.2f MB/s", bytesPerSec / (1024 * 1024));
        }
    }

    @Override
    public void onDirectionChanged(boolean isSender) {
        SwingUtilities.invokeLater(() -> {
            this.isSender = isSender;
            updateDirectionButton();
            updateSyncButtonState();
            log("Direction changed by remote: " + (isSender ? "Sender" : "Receiver"));
        });
    }

    @Override
    public void onConnectionStatusChanged(boolean isAlive) {
        SwingUtilities.invokeLater(() -> {
            if (isAlive) {
                statusLabel.setText("Connected");
                statusLabel.setForeground(new Color(0, 128, 0));
            } else {
                statusLabel.setText("Connection Lost");
                statusLabel.setForeground(Color.ORANGE);
            }
            updateSyncButtonState();
        });
    }

    @Override
    public void onLog(String message) {
        log(message);
    }

    @Override
    public void onError(String message) {
        SwingUtilities.invokeLater(() -> {
            log("ERROR: " + message);
            progressBar.setString("Error");
            updateSyncButtonState();
        });
    }
}

