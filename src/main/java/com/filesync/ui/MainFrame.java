package com.filesync.ui;

import com.filesync.serial.SerialPortManager;
import com.filesync.sync.FileSyncManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * Main application window for COM Port File Sync.
 * Provides UI for COM port selection, folder selection, sync direction, and sync control.
 */
public class MainFrame extends JFrame implements FileSyncManager.SyncEventListener {

    private static final int WINDOW_WIDTH = 600;
    private static final int WINDOW_HEIGHT = 500;

    // UI Components
    private JComboBox<String> portComboBox;
    private JButton refreshPortsButton;
    private JButton connectButton;
    private JTextField folderTextField;
    private JButton browseFolderButton;
    private JButton directionButton;
    private JButton syncButton;
    private JProgressBar progressBar;
    private JTextArea logTextArea;
    private JLabel statusLabel;

    // Application state
    private SerialPortManager serialPort;
    private FileSyncManager syncManager;
    private boolean isSender = true;
    private boolean isConnected = false;

    public MainFrame() {
        initComponents();
        initSerialPort();
        layoutComponents();
        setupEventHandlers();
        refreshPorts();
    }

    private void initComponents() {
        setTitle("COM Port File Sync");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(WINDOW_WIDTH, WINDOW_HEIGHT);
        setMinimumSize(new Dimension(500, 400));
        setLocationRelativeTo(null);

        // COM Port components
        portComboBox = new JComboBox<>();
        portComboBox.setPreferredSize(new Dimension(200, 25));

        refreshPortsButton = new JButton("Refresh");
        connectButton = new JButton("Connect");

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
        serialPort = new SerialPortManager();
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
        connectionPanel.add(connectButton, gbc);

        gbc.gridx = 4;
        connectionPanel.add(statusLabel, gbc);

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

    private void connect() {
        String selectedPort = (String) portComboBox.getSelectedItem();
        if (selectedPort == null || selectedPort.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please select a COM port", 
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        if (serialPort.open(selectedPort)) {
            isConnected = true;
            connectButton.setText("Disconnect");
            statusLabel.setText("Connected");
            statusLabel.setForeground(new Color(0, 128, 0));
            portComboBox.setEnabled(false);
            refreshPortsButton.setEnabled(false);
            updateSyncButtonState();
            syncManager.startListening();
            log("Connected to " + selectedPort);
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
        updateSyncButtonState();
        log("Disconnected");
    }

    private void browseFolder() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fileChooser.setDialogTitle("Select Sync Folder");

        if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File selectedFolder = fileChooser.getSelectedFile();
            folderTextField.setText(selectedFolder.getAbsolutePath());
            syncManager.setSyncFolder(selectedFolder);
            updateSyncButtonState();
            log("Selected folder: " + selectedFolder.getAbsolutePath());
        }
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
        boolean canSync = isConnected && syncManager.getSyncFolder() != null && isSender;
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
    public void onTransferProgress(int currentBlock, int totalBlocks) {
        SwingUtilities.invokeLater(() -> {
            if (totalBlocks > 0) {
                int percent = (int) ((double) currentBlock / totalBlocks * 100);
                progressBar.setString("Block " + currentBlock + "/" + totalBlocks);
            } else {
                progressBar.setString("Block " + currentBlock);
            }
        });
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

