package com.filesync.ui;

import com.filesync.sync.ConflictInfo;
import com.filesync.sync.FileChangeDetector;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.text.SimpleDateFormat;
import java.util.Date;
import javax.swing.ButtonGroup;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;

/**
 * Panel for resolving binary file conflicts. Extracted from BinaryConflictDialog for use in unified
 * ConflictResolutionDialog.
 */
public class BinaryConflictPanel extends JPanel {

    public enum Resolution {
        KEEP_LOCAL,
        KEEP_REMOTE,
        SKIP
    }

    private final JRadioButton keepLocalRadio;
    private final JRadioButton keepRemoteRadio;
    private final JRadioButton skipRadio;

    public BinaryConflictPanel(ConflictInfo conflict) {
        setLayout(new BorderLayout(8, 8));

        JLabel headerLabel =
                new JLabel(
                        "<html><b>Binary File Conflict</b><br/>"
                                + "This binary file has been modified on both sides.<br/>"
                                + "Choose which version to keep:</html>");
        headerLabel.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 0, 8, 0));
        add(headerLabel, BorderLayout.NORTH);

        JPanel infoPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 8));
        infoPanel.add(createInfoPanel("LOCAL VERSION", conflict.getLocalInfo()));
        infoPanel.add(createInfoPanel("REMOTE VERSION", conflict.getRemoteInfo()));
        add(infoPanel, BorderLayout.CENTER);

        JPanel choicePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        keepLocalRadio = new JRadioButton("Local version");
        keepLocalRadio.setSelected(true);
        keepRemoteRadio = new JRadioButton("Remote version");
        skipRadio = new JRadioButton("Skip (Do Not Transfer)");

        ButtonGroup group = new ButtonGroup();
        group.add(keepLocalRadio);
        group.add(keepRemoteRadio);
        group.add(skipRadio);

        choicePanel.add(new JLabel("Use:"));
        choicePanel.add(keepLocalRadio);
        choicePanel.add(keepRemoteRadio);
        choicePanel.add(skipRadio);

        add(choicePanel, BorderLayout.SOUTH);
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
        if (keepLocalRadio.isSelected()) {
            return Resolution.KEEP_LOCAL;
        } else if (keepRemoteRadio.isSelected()) {
            return Resolution.KEEP_REMOTE;
        } else if (skipRadio.isSelected()) {
            return Resolution.SKIP;
        }
        return Resolution.KEEP_LOCAL;
    }

    /**
     * Apply target fixed by resolution: use local -> apply to remote; use remote -> apply to local;
     * skip -> BOTH
     */
    public ConflictInfo.ApplyTarget getApplyTarget() {
        Resolution r = getResolution();
        if (r == Resolution.KEEP_LOCAL) {
            return ConflictInfo.ApplyTarget.REMOTE_ONLY;
        }
        return ConflictInfo.ApplyTarget.BOTH;
    }
}
