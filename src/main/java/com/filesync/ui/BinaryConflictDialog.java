package com.filesync.ui;

import com.filesync.sync.ConflictInfo;
import com.filesync.sync.FileChangeDetector;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.text.SimpleDateFormat;
import java.util.Date;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;

/**
 * Dialog for resolving binary file conflicts. Shows file info for both versions and allows user to
 * choose which to keep. No content preview is shown since binary files cannot be meaningfully
 * displayed.
 */
public class BinaryConflictDialog extends JDialog {

    public enum Resolution {
        KEEP_LOCAL,
        KEEP_REMOTE,
        SKIP,
        CANCEL
    }

    private Resolution resolution = Resolution.CANCEL;

    private final JRadioButton keepLocalRadio;
    private final JRadioButton keepRemoteRadio;
    private final JRadioButton skipRadio;

    public BinaryConflictDialog(JFrame parent, ConflictInfo conflict) {
        super(parent, "Resolve Binary Conflict: " + conflict.getPath(), true);
        setMinimumSize(new Dimension(500, 350));
        setLocationRelativeTo(parent);

        JPanel mainPanel = new JPanel(new BorderLayout(8, 8));
        mainPanel.setBorder(javax.swing.BorderFactory.createEmptyBorder(16, 16, 16, 16));

        // Header
        JLabel headerLabel =
                new JLabel(
                        "<html><b>Binary File Conflict</b><br/>"
                                + "This binary file has been modified on both sides.<br/>"
                                + "Choose which version to keep:</html>");
        headerLabel.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 0, 8, 0));
        mainPanel.add(headerLabel, BorderLayout.NORTH);

        // Info panel
        JPanel infoPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 8));

        // Local info
        JPanel localInfoPanel = createInfoPanel("LOCAL VERSION", conflict.getLocalInfo());
        infoPanel.add(localInfoPanel);

        // Remote info
        JPanel remoteInfoPanel = createInfoPanel("REMOTE VERSION", conflict.getRemoteInfo());
        infoPanel.add(remoteInfoPanel);

        mainPanel.add(infoPanel, BorderLayout.CENTER);

        // Resolution choices
        JPanel choicePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        keepLocalRadio = new JRadioButton("Keep Local");
        keepLocalRadio.setSelected(true);
        keepRemoteRadio = new JRadioButton("Keep Remote");
        skipRadio = new JRadioButton("Skip (Do Not Transfer)");

        ButtonGroup group = new ButtonGroup();
        group.add(keepLocalRadio);
        group.add(keepRemoteRadio);
        group.add(skipRadio);

        choicePanel.add(new JLabel("Resolution:"));
        choicePanel.add(keepLocalRadio);
        choicePanel.add(keepRemoteRadio);
        choicePanel.add(skipRadio);

        mainPanel.add(choicePanel, BorderLayout.SOUTH);

        // Buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(
                e -> {
                    resolution = Resolution.CANCEL;
                    dispose();
                });

        JButton okButton = new JButton("OK");
        okButton.addActionListener(
                e -> {
                    if (keepLocalRadio.isSelected()) {
                        resolution = Resolution.KEEP_LOCAL;
                    } else if (keepRemoteRadio.isSelected()) {
                        resolution = Resolution.KEEP_REMOTE;
                    } else if (skipRadio.isSelected()) {
                        resolution = Resolution.SKIP;
                    } else {
                        resolution = Resolution.CANCEL;
                    }
                    dispose();
                });

        buttonPanel.add(cancelButton);
        buttonPanel.add(okButton);

        JPanel bottomPanel = new JPanel(new BorderLayout(4, 4));
        bottomPanel.add(choicePanel, BorderLayout.NORTH);
        bottomPanel.add(buttonPanel, BorderLayout.SOUTH);
        mainPanel.add(bottomPanel, BorderLayout.SOUTH);

        setContentPane(mainPanel);
    }

    private JPanel createInfoPanel(String title, FileChangeDetector.FileInfo info) {
        JPanel panel = new JPanel();
        panel.setLayout(new BorderLayout(4, 4));
        panel.setBorder(javax.swing.BorderFactory.createTitledBorder(title));

        JPanel infoGrid = new JPanel(new java.awt.GridLayout(3, 2, 8, 4));
        infoGrid.add(new JLabel("Size:"));
        infoGrid.add(new JLabel(formatBytes(info.getSize())));

        infoGrid.add(new JLabel("Modified:"));
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String modTime =
                info.getLastModified() > 0
                        ? sdf.format(new Date(info.getLastModified()))
                        : "Unknown";
        infoGrid.add(new JLabel(modTime));

        infoGrid.add(new JLabel("MD5:"));
        String md5 =
                info.getMd5() != null
                        ? info.getMd5().substring(0, Math.min(8, info.getMd5().length())) + "..."
                        : "N/A (fast mode)";
        infoGrid.add(new JLabel(md5));

        panel.add(infoGrid, BorderLayout.NORTH);

        return panel;
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        } else {
            return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }

    public Resolution getResolution() {
        return resolution;
    }

    public static Resolution showDialog(JFrame parent, ConflictInfo conflict) {
        BinaryConflictDialog dialog = new BinaryConflictDialog(parent, conflict);
        dialog.setVisible(true);
        return dialog.getResolution();
    }
}
