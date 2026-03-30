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
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

/**
 * Panel for resolving text file conflicts with merge option. Extracted from TextMergeDialog for use
 * in unified ConflictResolutionDialog.
 */
public class TextMergePanel extends JPanel {

    public enum Resolution {
        KEEP_LOCAL,
        KEEP_REMOTE,
        MERGE
    }

    private final JRadioButton keepLocalRadio;
    private final JRadioButton keepRemoteRadio;
    private final JRadioButton mergeRadio;
    private final JTextArea mergeTextArea;
    private final JPanel mergePanel;

    public TextMergePanel(ConflictInfo conflict) {
        setLayout(new BorderLayout(8, 8));

        JLabel headerLabel =
                new JLabel(
                        "<html><b>Conflict Detected</b><br/>"
                                + "This file has been modified on both sides. Choose how to resolve:</html>");
        headerLabel.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 0, 8, 0));
        add(headerLabel, BorderLayout.NORTH);

        JPanel splitPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weightx = 0.5;
        gbc.weighty = 1.0;

        gbc.gridx = 0;
        gbc.gridy = 0;
        splitPanel.add(
                createVersionPanel("LOCAL VERSION", conflict.getLocalContentAsString()), gbc);

        gbc.gridx = 1;
        gbc.gridy = 0;
        splitPanel.add(
                createVersionPanel("REMOTE VERSION", conflict.getRemoteContentAsString()), gbc);

        add(splitPanel, BorderLayout.CENTER);

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

        JPanel southContainer = new JPanel(new BorderLayout(4, 4));
        southContainer.add(mergePanel, BorderLayout.NORTH);
        southContainer.add(choicePanel, BorderLayout.SOUTH);
        add(southContainer, BorderLayout.SOUTH);
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
        if (keepLocalRadio.isSelected()) {
            return Resolution.KEEP_LOCAL;
        } else if (keepRemoteRadio.isSelected()) {
            return Resolution.KEEP_REMOTE;
        } else if (mergeRadio.isSelected()) {
            return Resolution.MERGE;
        }
        return Resolution.KEEP_LOCAL;
    }

    public String getMergedContent() {
        if (mergeRadio.isSelected()) {
            return mergeTextArea.getText();
        }
        return null;
    }

    /**
     * Apply target fixed by resolution: keep local -> apply to remote; keep remote -> apply to
     * local; merge -> apply to both
     */
    public ConflictInfo.ApplyTarget getApplyTarget() {
        Resolution r = getResolution();
        if (r == Resolution.KEEP_LOCAL) {
            return ConflictInfo.ApplyTarget.REMOTE_ONLY;
        }
        return ConflictInfo.ApplyTarget.BOTH;
    }
}
