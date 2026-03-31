package com.filesync.ui;

import com.filesync.sync.ConflictAnalyzer;
import com.filesync.sync.ConflictInfo;
import com.filesync.sync.TextDiffUtil.DiffHunk;
import com.filesync.sync.TextDiffUtil.DiffLine;
import com.filesync.sync.TextDiffUtil.DiffLineType;
import com.filesync.sync.TextDiffUtil.DiffResult;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextPane;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;

/**
 * Dialog for resolving text file conflicts. Shows only the changed regions (diff hunks) instead of
 * the entire file, making it easier to spot and resolve conflicts.
 */
public class TextMergeDialog extends JDialog {

    // Colors for diff highlighting
    private static final Color ADDED_COLOR = new Color(200, 255, 200); // Light green
    private static final Color REMOVED_COLOR = new Color(255, 200, 200); // Light red
    private static final Color CONTEXT_COLOR = new Color(245, 245, 245); // Light gray
    private static final Color HEADER_BG_COLOR = new Color(230, 230, 230);

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

    // Diff-related UI components
    private final JLabel changeCountLabel;
    private final JButton prevChangeButton;
    private final JButton nextChangeButton;
    private final JTextPane localDiffPane;
    private final JTextPane remoteDiffPane;
    private int currentHunkIndex = 0;
    private java.util.List<DiffHunk> hunks;
    private ConflictInfo currentConflict;

    public TextMergeDialog(JFrame parent, ConflictInfo conflict) {
        super(parent, "Resolve Conflict: " + conflict.getPath(), true);
        setMinimumSize(new Dimension(900, 700));
        setLocationRelativeTo(parent);

        // Compute diff if not already done
        ConflictAnalyzer.computeConflictDiff(conflict);
        DiffResult diffResult = conflict.getDiffResult();
        this.hunks = diffResult != null ? diffResult.getHunks() : java.util.Collections.emptyList();
        this.currentConflict = conflict;

        // Main panel
        JPanel mainPanel = new JPanel(new BorderLayout(8, 8));
        mainPanel.setBorder(javax.swing.BorderFactory.createEmptyBorder(16, 16, 16, 16));

        // Header with change count
        JPanel headerPanel = new JPanel(new BorderLayout(4, 4));
        JLabel headerLabel =
                new JLabel(
                        "<html><b>Conflict Detected</b><br/>"
                                + "This file has been modified on both sides. Choose how to resolve:</html>");
        headerLabel.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 0, 4, 0));
        headerPanel.add(headerLabel, BorderLayout.NORTH);

        changeCountLabel = new JLabel();
        updateChangeCountLabel();
        headerPanel.add(changeCountLabel, BorderLayout.SOUTH);
        mainPanel.add(headerPanel, BorderLayout.NORTH);

        // Navigation buttons
        JPanel navPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        prevChangeButton = new JButton("◀ Previous Change");
        prevChangeButton.addActionListener(e -> navigateHunk(-1));
        nextChangeButton = new JButton("Next Change ▶");
        nextChangeButton.addActionListener(e -> navigateHunk(1));
        navPanel.add(prevChangeButton);
        navPanel.add(nextChangeButton);
        updateNavButtons();

        // Diff display panel
        JPanel diffPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 2, 2, 2);
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weightx = 0.5;
        gbc.weighty = 1.0;

        localDiffPane = new JTextPane();
        localDiffPane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        localDiffPane.setEditable(false);
        JScrollPane localScroll = new JScrollPane(localDiffPane);

        remoteDiffPane = new JTextPane();
        remoteDiffPane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        remoteDiffPane.setEditable(false);
        JScrollPane remoteScroll = new JScrollPane(remoteDiffPane);

        gbc.gridx = 0;
        gbc.gridy = 0;
        diffPanel.add(createLabeledComponent("LOCAL VERSION", localScroll), gbc);

        gbc.gridx = 1;
        gbc.gridy = 0;
        diffPanel.add(createLabeledComponent("REMOTE VERSION", remoteScroll), gbc);

        // Display the first hunk (or all if no hunks)
        displayCurrentHunk(conflict);

        // Center panel with nav and diff
        JPanel centerPanel = new JPanel(new BorderLayout(4, 4));
        centerPanel.add(navPanel, BorderLayout.NORTH);
        centerPanel.add(diffPanel, BorderLayout.CENTER);
        mainPanel.add(centerPanel, BorderLayout.CENTER);

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
                    // Show full file with git-merge-style conflict markers
                    mergeTextArea.setText(buildFullFileGitStyleMergeContent(conflict));
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

        // Stack merge panel above resolution choices
        JPanel southContainer = new JPanel(new BorderLayout(4, 4));
        southContainer.add(mergePanel, BorderLayout.NORTH);
        southContainer.add(bottomPanel, BorderLayout.SOUTH);
        mainPanel.add(southContainer, BorderLayout.SOUTH);

        setContentPane(mainPanel);
    }

    private void updateChangeCountLabel() {
        int totalHunks = hunks != null ? hunks.size() : 0;
        if (totalHunks == 0) {
            changeCountLabel.setText(
                    "No changes detected (files may be identical after whitespace normalization)");
        } else if (totalHunks == 1) {
            changeCountLabel.setText("1 change region");
        } else {
            changeCountLabel.setText(totalHunks + " change regions");
        }
    }

    private void updateNavButtons() {
        int totalHunks = hunks != null ? hunks.size() : 0;
        prevChangeButton.setEnabled(currentHunkIndex > 0 && totalHunks > 1);
        nextChangeButton.setEnabled(currentHunkIndex < totalHunks - 1 && totalHunks > 1);

        if (totalHunks > 1) {
            changeCountLabel.setText(
                    "Change " + (currentHunkIndex + 1) + " of " + totalHunks + " regions");
        }
    }

    private void navigateHunk(int direction) {
        currentHunkIndex += direction;
        currentHunkIndex = Math.max(0, Math.min(currentHunkIndex, hunks.size() - 1));
        updateNavButtons();
        displayCurrentHunk(currentConflict);
    }

    private void displayCurrentHunk(ConflictInfo conflict) {
        DiffResult diffResult = conflict.getDiffResult();
        if (diffResult == null || diffResult.getHunks().isEmpty()) {
            // No changes - show full files
            displayFullFiles(conflict);
            return;
        }

        if (currentHunkIndex >= diffResult.getHunks().size()) {
            currentHunkIndex = diffResult.getHunks().size() - 1;
        }

        DiffHunk hunk = diffResult.getHunks().get(currentHunkIndex);

        // Build local and remote views from the hunk
        StringBuilder localText = new StringBuilder();
        StringBuilder remoteText = new StringBuilder();

        for (DiffLine line : hunk.getLines()) {
            switch (line.getType()) {
                case UNCHANGED:
                    localText.append("  ").append(line.getContent()).append("\n");
                    remoteText.append("  ").append(line.getContent()).append("\n");
                    break;
                case REMOVED:
                    localText.append("- ").append(line.getContent()).append("\n");
                    break;
                case ADDED:
                    remoteText.append("+ ").append(line.getContent()).append("\n");
                    break;
            }
        }

        displayColoredDiff(localText.toString(), remoteText.toString(), hunk);
    }

    private void displayColoredDiff(String localText, String remoteText, DiffHunk hunk) {
        localDiffPane.setText("");
        remoteDiffPane.setText("");

        try {
            // Build local pane with colors
            SimpleAttributeSet removedAttr = new SimpleAttributeSet();
            StyleConstants.setBackground(removedAttr, REMOVED_COLOR);
            SimpleAttributeSet contextAttr = new SimpleAttributeSet();
            StyleConstants.setBackground(contextAttr, CONTEXT_COLOR);

            for (DiffLine line : hunk.getLines()) {
                if (line.getType() == DiffLineType.UNCHANGED
                        || line.getType() == DiffLineType.REMOVED) {
                    String prefix = line.getType() == DiffLineType.REMOVED ? "- " : "  ";
                    String displayLine = prefix + line.getContent() + "\n";
                    localDiffPane
                            .getDocument()
                            .insertString(
                                    localDiffPane.getDocument().getLength(),
                                    displayLine,
                                    line.getType() == DiffLineType.REMOVED
                                            ? removedAttr
                                            : contextAttr);
                }
            }

            // Build remote pane with colors
            SimpleAttributeSet addedAttr = new SimpleAttributeSet();
            StyleConstants.setBackground(addedAttr, ADDED_COLOR);

            for (DiffLine line : hunk.getLines()) {
                if (line.getType() == DiffLineType.UNCHANGED
                        || line.getType() == DiffLineType.ADDED) {
                    String prefix = line.getType() == DiffLineType.ADDED ? "+ " : "  ";
                    String displayLine = prefix + line.getContent() + "\n";
                    remoteDiffPane
                            .getDocument()
                            .insertString(
                                    remoteDiffPane.getDocument().getLength(),
                                    displayLine,
                                    line.getType() == DiffLineType.ADDED ? addedAttr : contextAttr);
                }
            }
        } catch (BadLocationException e) {
            // Fallback to plain text
            localDiffPane.setText(localText);
            remoteDiffPane.setText(remoteText);
        }

        // Scroll to top
        localDiffPane.setCaretPosition(0);
        remoteDiffPane.setCaretPosition(0);
    }

    private void displayFullFiles(ConflictInfo conflict) {
        localDiffPane.setText(conflict.getLocalContentAsString());
        remoteDiffPane.setText(conflict.getRemoteContentAsString());
    }

    /**
     * Build full file content with git-merge-style conflict markers at change points. Shows the
     * entire file with &lt;&lt;&lt;&lt;&lt;&lt;&lt; LOCAL / ======= / &gt;&gt;&gt;&gt;&gt;&gt;&gt;
     * REMOTE markers.
     */
    private String buildFullFileGitStyleMergeContent(ConflictInfo conflict) {
        String localContent = conflict.getLocalContentAsString();
        String remoteContent = conflict.getRemoteContentAsString();
        String[] localLines = localContent.split("\n", -1);
        String[] remoteLines = remoteContent.split("\n", -1);

        int i = 0, j = 0;
        StringBuilder sb = new StringBuilder();

        while (i < localLines.length || j < remoteLines.length) {
            if (i < localLines.length
                    && j < remoteLines.length
                    && localLines[i].equals(remoteLines[j])) {
                sb.append(localLines[i]).append("\n");
                i++;
                j++;
            } else {
                List<String> localDiffLines = new ArrayList<>();
                List<String> remoteDiffLines = new ArrayList<>();

                int nextMatchI = -1, nextMatchJ = -1;
                for (int lookAhead = 1; lookAhead <= 10; lookAhead++) {
                    if (i + lookAhead < localLines.length
                            && j < remoteLines.length
                            && localLines[i + lookAhead].equals(remoteLines[j])) {
                        nextMatchI = i + lookAhead;
                        nextMatchJ = j;
                        break;
                    }
                    if (i < localLines.length
                            && j + lookAhead < remoteLines.length
                            && localLines[i].equals(remoteLines[j + lookAhead])) {
                        nextMatchI = i;
                        nextMatchJ = j + lookAhead;
                        break;
                    }
                }

                int endI = (nextMatchI >= 0) ? nextMatchI : localLines.length;
                while (i < endI) {
                    localDiffLines.add(localLines[i]);
                    i++;
                }

                int endJ = (nextMatchJ >= 0) ? nextMatchJ : remoteLines.length;
                while (j < endJ) {
                    remoteDiffLines.add(remoteLines[j]);
                    j++;
                }

                sb.append("<<<<<<< LOCAL\n");
                for (String line : localDiffLines) {
                    sb.append(line).append("\n");
                }
                sb.append("=======\n");
                for (String line : remoteDiffLines) {
                    sb.append(line).append("\n");
                }
                sb.append(">>>>>>> REMOTE\n");
            }
        }

        return sb.toString();
    }

    private JPanel createLabeledComponent(String title, JScrollPane scrollPane) {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        JLabel label = new JLabel(title);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        label.setBackground(HEADER_BG_COLOR);
        label.setOpaque(true);
        panel.add(label, BorderLayout.NORTH);
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
