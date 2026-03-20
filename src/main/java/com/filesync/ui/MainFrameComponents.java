package com.filesync.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.Insets;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;

/**
 * Shared Swing components and initial layout for MainFrame.
 */
public class MainFrameComponents {
    public static final int WINDOW_WIDTH = 700;
    public static final int WINDOW_HEIGHT = 600;

    private final JComboBox<String> portComboBox;
    private final JButton refreshPortsButton;
    private final JButton settingsButton;
    private final JButton connectButton;
    private final JComboBox<String> folderComboBox;
    private final JButton browseFolderButton;
    private final JButton directionButton;
    private final JButton syncButton;
    private final JButton previewSyncButton;
    private final JCheckBox respectGitignoreCheckBox;
    private final JCheckBox strictSyncCheckBox;
    private final JCheckBox fastModeCheckBox;
    private final JProgressBar progressBar;
    private final JTextArea sharedTextArea;
    private final JButton overwriteFromClipboardButton;
    private final JButton appendFromClipboardButton;
    private final JButton copyFromClipboardButton;
    private final JTextArea logTextArea;
    private final JLabel statusLabel;
    private final JLabel settingsLabel;

    public MainFrameComponents() {
        portComboBox = new JComboBox<>();
        portComboBox.setPreferredSize(new Dimension(200, 25));
        refreshPortsButton = new JButton("Refresh");
        settingsButton = new JButton("Settings");
        connectButton = new JButton("Connect");

        folderComboBox = new JComboBox<>();
        folderComboBox.setEditable(false);
        browseFolderButton = new JButton("Browse...");

        directionButton = new JButton("A -> B (Sender)");
        directionButton.setFont(directionButton.getFont().deriveFont(Font.BOLD));
        directionButton.setMargin(new Insets(2, 8, 2, 8));

        syncButton = new JButton("Start Sync");
        syncButton.setEnabled(false);
        syncButton.setMargin(new Insets(2, 8, 2, 8));

        previewSyncButton = new JButton("Sync Preview");
        previewSyncButton.setEnabled(false);
        previewSyncButton.setMargin(new Insets(2, 8, 2, 8));

        respectGitignoreCheckBox = new JCheckBox(".gitignore");
        respectGitignoreCheckBox.setToolTipText(
                "When enabled, files matching .gitignore patterns will be excluded from sync");
        respectGitignoreCheckBox.setMargin(new Insets(2, 4, 2, 4));

        strictSyncCheckBox = new JCheckBox("Mirror Mode");
        strictSyncCheckBox.setToolTipText(
                "When enabled, files that exist on the remote but not locally will be deleted");
        strictSyncCheckBox.setMargin(new Insets(2, 4, 2, 4));

        fastModeCheckBox = new JCheckBox("Fast Mode");
        fastModeCheckBox.setToolTipText(
                "When enabled, uses quick hashing for faster manifest generation (may miss some changes)");
        fastModeCheckBox.setMargin(new Insets(2, 4, 2, 4));

        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);

        sharedTextArea = new JTextArea();
        sharedTextArea.setLineWrap(true);
        sharedTextArea.setWrapStyleWord(true);
        sharedTextArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        overwriteFromClipboardButton = new JButton("Overwrite from Clipboard");
        appendFromClipboardButton = new JButton("Append from Clipboard");
        copyFromClipboardButton = new JButton("Copy to Clipboard");

        logTextArea = new JTextArea();
        logTextArea.setEditable(false);
        logTextArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        statusLabel = new JLabel("Disconnected");
        statusLabel.setForeground(Color.RED);

        settingsLabel = new JLabel();
        settingsLabel.setFont(settingsLabel.getFont().deriveFont(Font.PLAIN, 11f));
        settingsLabel.setForeground(Color.GRAY);
    }

    public JPanel createMainPanel() {
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

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

        gbc.gridx = 0;
        gbc.gridy = 1;
        connectionPanel.add(new JLabel("Config:"), gbc);

        gbc.gridx = 1;
        gbc.gridwidth = 5;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        connectionPanel.add(settingsLabel, gbc);
        gbc.gridwidth = 1;

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
        folderPanel.add(folderComboBox, gbc);

        gbc.gridx = 2;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        folderPanel.add(browseFolderButton, gbc);

        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 6));
        controlPanel.setBorder(new TitledBorder("Sync Control"));
        controlPanel.add(directionButton);
        controlPanel.add(syncButton);
        controlPanel.add(previewSyncButton);
        controlPanel.add(respectGitignoreCheckBox);
        controlPanel.add(strictSyncCheckBox);
        controlPanel.add(fastModeCheckBox);

        JPanel topSection = new JPanel();
        topSection.setLayout(new BoxLayout(topSection, BoxLayout.Y_AXIS));
        topSection.add(connectionPanel);
        topSection.add(Box.createVerticalStrut(5));
        topSection.add(folderPanel);
        topSection.add(Box.createVerticalStrut(5));
        topSection.add(controlPanel);

        JPanel progressPanel = new JPanel(new BorderLayout(5, 5));
        progressPanel.setBorder(new TitledBorder("Progress"));
        progressPanel.add(progressBar, BorderLayout.CENTER);
        Dimension progressPreferred = progressPanel.getPreferredSize();
        progressPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, progressPreferred.height));

        JPanel sharedPanel = new JPanel(new BorderLayout());
        sharedPanel.setBorder(new TitledBorder("Shared Text"));
        JScrollPane sharedScroll = new JScrollPane(sharedTextArea);
        sharedScroll.setPreferredSize(new Dimension(0, 120));
        sharedPanel.add(sharedScroll, BorderLayout.CENTER);

        JPanel clipboardButtonsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 5));
        clipboardButtonsPanel.add(overwriteFromClipboardButton);
        clipboardButtonsPanel.add(appendFromClipboardButton);
        clipboardButtonsPanel.add(copyFromClipboardButton);
        sharedPanel.add(clipboardButtonsPanel, BorderLayout.SOUTH);
        sharedPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        JPanel logPanel = new JPanel(new BorderLayout());
        logPanel.setBorder(new TitledBorder("Log"));
        JScrollPane scrollPane = new JScrollPane(logTextArea);
        scrollPane.setPreferredSize(new Dimension(0, 150));
        logPanel.add(scrollPane, BorderLayout.CENTER);
        Dimension logPreferred = logPanel.getPreferredSize();
        logPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, logPreferred.height));

        JPanel bottomSection = new JPanel();
        bottomSection.setLayout(new BoxLayout(bottomSection, BoxLayout.Y_AXIS));
        bottomSection.add(progressPanel);
        bottomSection.add(Box.createVerticalStrut(5));
        bottomSection.add(sharedPanel);
        bottomSection.add(Box.createVerticalStrut(5));
        bottomSection.add(logPanel);

        mainPanel.add(topSection, BorderLayout.NORTH);
        mainPanel.add(bottomSection, BorderLayout.CENTER);
        return mainPanel;
    }

    public void updateDirectionButton(boolean isSender) {
        if (isSender) {
            directionButton.setText("A -> B (Sender)");
            directionButton.setBackground(new Color(144, 238, 144));
        } else {
            directionButton.setText("B <- A (Receiver)");
            directionButton.setBackground(new Color(173, 216, 230));
        }
    }

    public void configureFrame(JFrame frame) {
        frame.setSize(WINDOW_WIDTH, WINDOW_HEIGHT);
        frame.setMinimumSize(new Dimension(650, 450));
        frame.setLocationRelativeTo(null);

        Image iconImage = loadIconImage();
        if (iconImage != null) {
            frame.setIconImage(iconImage);
        }
    }

    private Image loadIconImage() {
        try (java.io.InputStream is = getClass().getClassLoader().getResourceAsStream("icon.jpg")) {
            if (is != null) {
                java.awt.image.BufferedImage bufferedImage = javax.imageio.ImageIO.read(is);
                if (bufferedImage != null) {
                    return bufferedImage;
                }
            }
        } catch (java.io.IOException e) {
            System.err.println("Failed to load icon: " + e.getMessage());
        }
        return null;
    }

    public JComboBox<String> getPortComboBox() {
        return portComboBox;
    }

    public JButton getRefreshPortsButton() {
        return refreshPortsButton;
    }

    public JButton getSettingsButton() {
        return settingsButton;
    }

    public JButton getConnectButton() {
        return connectButton;
    }

    public JComboBox<String> getFolderComboBox() {
        return folderComboBox;
    }

    public JButton getBrowseFolderButton() {
        return browseFolderButton;
    }

    public JButton getDirectionButton() {
        return directionButton;
    }

    public JButton getSyncButton() {
        return syncButton;
    }

    public JButton getPreviewSyncButton() {
        return previewSyncButton;
    }

    public JCheckBox getRespectGitignoreCheckBox() {
        return respectGitignoreCheckBox;
    }

    public JCheckBox getStrictSyncCheckBox() {
        return strictSyncCheckBox;
    }

    public JCheckBox getFastModeCheckBox() {
        return fastModeCheckBox;
    }

    public JProgressBar getProgressBar() {
        return progressBar;
    }

    public JTextArea getSharedTextArea() {
        return sharedTextArea;
    }

    public JButton getOverwriteFromClipboardButton() {
        return overwriteFromClipboardButton;
    }

    public JButton getAppendFromClipboardButton() {
        return appendFromClipboardButton;
    }

    public JButton getCopyFromClipboardButton() {
        return copyFromClipboardButton;
    }

    public JTextArea getLogTextArea() {
        return logTextArea;
    }

    public JLabel getStatusLabel() {
        return statusLabel;
    }

    public JLabel getSettingsLabel() {
        return settingsLabel;
    }

    public void setSettingsLabel(String text) {
        settingsLabel.setText(text);
    }
}
