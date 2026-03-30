package com.filesync.ui;

import com.filesync.sync.ConflictInfo;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

/**
 * Dialog for resolving text file conflicts. Shows both versions and allows user to choose which to
 * keep or to merge manually.
 */
public class TextMergeDialog extends JDialog {

    public enum Resolution {
        KEEP_LOCAL,
        KEEP_REMOTE,
        MERGE,
        CANCEL
    }

    private Resolution resolution = Resolution.CANCEL;
    private String mergedContent;

    private final JRadioButton keepLocalRadio;
    private final JRadioButton keepRemoteRadio;
    private final JRadioButton mergeRadio;
    private final JTextArea mergeTextArea;
    private final JPanel mergePanel;

    public TextMergeDialog(JFrame parent, ConflictInfo conflict) {
        super(parent, "Resolve Conflict: " + conflict.getPath(), true);
        setMinimumSize(new Dimension(900, 700));
        setLocationRelativeTo(parent);

        // Main panel
        JPanel mainPanel = new JPanel(new BorderLayout(8, 8));
        mainPanel.setBorder(javax.swing.BorderFactory.createEmptyBorder(16, 16, 16, 16));

        // Header
        JLabel headerLabel =
                new JLabel(
                        "<html><b>Conflict Detected</b><br/>"
                                + "This file has been modified on both sides. Choose how to resolve:</html>");
        headerLabel.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 0, 8, 0));
        mainPanel.add(headerLabel, BorderLayout.NORTH);

        // Split pane for local and remote
        JPanel splitPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weightx = 0.5;
        gbc.weighty = 1.0;

        // Local version
        gbc.gridx = 0;
        gbc.gridy = 0;
        JPanel localPanel = createVersionPanel("LOCAL VERSION", conflict.getLocalContentAsString());
        splitPanel.add(localPanel, gbc);

        // Remote version
        gbc.gridx = 1;
        gbc.gridy = 0;
        JPanel remotePanel =
                createVersionPanel("REMOTE VERSION", conflict.getRemoteContentAsString());
        splitPanel.add(remotePanel, gbc);

        mainPanel.add(splitPanel, BorderLayout.CENTER);

        // Merge panel (initially hidden)
        mergePanel = new JPanel(new BorderLayout(4, 4));
        JLabel mergeLabel = new JLabel("EDIT MERGED VERSION:");
        mergeLabel.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 0, 4, 0));
        mergePanel.add(mergeLabel, BorderLayout.NORTH);

        mergeTextArea = new JTextArea();
        mergeTextArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        mergeTextArea.setLineWrap(true);
        mergeTextArea.setWrapStyleWord(true);
        JScrollPane mergeScroll = new JScrollPane(mergeTextArea);
        mergeScroll.setPreferredSize(new Dimension(0, 150));
        mergePanel.add(mergeScroll, BorderLayout.CENTER);
        mergePanel.setVisible(false);

        // Resolution choices
        JPanel choicePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        keepLocalRadio = new JRadioButton("Keep Local");
        keepLocalRadio.setSelected(true);
        keepRemoteRadio = new JRadioButton("Keep Remote");
        mergeRadio = new JRadioButton("Merge (Manual Edit)");

        ButtonGroup group = new ButtonGroup();
        group.add(keepLocalRadio);
        group.add(keepRemoteRadio);
        group.add(mergeRadio);

        mergeRadio.addActionListener(
                e -> {
                    mergePanel.setVisible(true);
                    // Pre-populate merge with both versions for easier manual merging
                    String localContent = conflict.getLocalContentAsString();
                    String remoteContent = conflict.getRemoteContentAsString();
                    String combined =
                            "<<<<<<< LOCAL\n"
                                    + localContent
                                    + "\n=======\n"
                                    + remoteContent
                                    + "\n>>>>>>> REMOTE\n";
                    mergeTextArea.setText(combined);
                    revalidate();
                    repaint();
                });

        keepLocalRadio.addActionListener(e -> mergePanel.setVisible(false));
        keepRemoteRadio.addActionListener(e -> mergePanel.setVisible(false));

        choicePanel.add(new JLabel("Resolution:"));
        choicePanel.add(keepLocalRadio);
        choicePanel.add(keepRemoteRadio);
        choicePanel.add(mergeRadio);

        JPanel bottomPanel = new JPanel(new BorderLayout(4, 4));
        bottomPanel.add(choicePanel, BorderLayout.NORTH);

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
                    } else if (mergeRadio.isSelected()) {
                        resolution = Resolution.MERGE;
                        mergedContent = mergeTextArea.getText();
                        conflict.setMergedContent(mergedContent);
                    } else {
                        resolution = Resolution.CANCEL;
                    }
                    dispose();
                });

        buttonPanel.add(cancelButton);
        buttonPanel.add(okButton);
        bottomPanel.add(buttonPanel, BorderLayout.SOUTH);

        // Stack merge panel above resolution choices; mergePanel was being overwritten by
        // bottomPanel
        JPanel southContainer = new JPanel(new BorderLayout(4, 4));
        southContainer.add(mergePanel, BorderLayout.NORTH);
        southContainer.add(bottomPanel, BorderLayout.SOUTH);
        mainPanel.add(southContainer, BorderLayout.SOUTH);

        setContentPane(mainPanel);
    }

    private JPanel createVersionPanel(String title, String content) {
        JPanel panel = new JPanel(new BorderLayout(4, 4));

        JLabel label = new JLabel(title);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        panel.add(label, BorderLayout.NORTH);

        JTextArea textArea = new JTextArea(content);
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        textArea.setEditable(false);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);

        JScrollPane scrollPane = new JScrollPane(textArea);
        scrollPane.setPreferredSize(new Dimension(400, 400));
        panel.add(scrollPane, BorderLayout.CENTER);

        return panel;
    }

    public Resolution getResolution() {
        return resolution;
    }

    public String getMergedContent() {
        return mergedContent;
    }

    public static Resolution showDialog(JFrame parent, ConflictInfo conflict) {
        TextMergeDialog dialog = new TextMergeDialog(parent, conflict);
        dialog.setVisible(true);
        return dialog.getResolution();
    }
}
