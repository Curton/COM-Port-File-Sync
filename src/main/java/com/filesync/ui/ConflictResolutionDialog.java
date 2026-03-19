package com.filesync.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;

import com.filesync.sync.ConflictInfo;

/**
 * Unified dialog for resolving multiple file conflicts in one window.
 * Shows one conflict at a time with Next/Previous navigation and progress (e.g. 2/5).
 * User can cancel at any time to abort the entire resolution.
 */
public class ConflictResolutionDialog extends JDialog {

    public enum Result {
        /** All conflicts resolved successfully */
        COMPLETED,
        /** User cancelled */
        CANCELLED
    }

    private Result result = Result.CANCELLED;
    private final java.util.List<ConflictInfo> conflicts;
    private final java.util.List<JPanel> conflictPanels;
    private int currentIndex = 0;

    private final JLabel progressLabel;
    private final JLabel pathLabel;
    private final JPanel cardPanel;
    private final CardLayout cardLayout;
    private final JButton previousButton;
    private final JButton nextButton;

    public ConflictResolutionDialog(JFrame parent, java.util.List<ConflictInfo> conflicts) {
        super(parent, "Resolve Conflicts", true);
        this.conflicts = conflicts;
        this.conflictPanels = new java.util.ArrayList<>(conflicts.size());

        setMinimumSize(new Dimension(920, 720));
        setLocationRelativeTo(parent);

        cardLayout = new CardLayout();
        cardPanel = new JPanel(cardLayout);

        for (int i = 0; i < conflicts.size(); i++) {
            ConflictInfo c = conflicts.get(i);
            JPanel panel = c.isBinary()
                    ? new BinaryConflictPanel(c)
                    : new TextMergePanel(c);
            conflictPanels.add(panel);
            cardPanel.add(panel, "conflict_" + i);
        }

        progressLabel = new JLabel();
        pathLabel = new JLabel();
        updateLabels();

        JPanel headerPanel = new JPanel(new BorderLayout(8, 4));
        headerPanel.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 0, 8, 0));
        headerPanel.add(progressLabel, BorderLayout.NORTH);
        headerPanel.add(pathLabel, BorderLayout.CENTER);

        previousButton = new JButton("Previous");
        previousButton.addActionListener(e -> goPrevious());

        nextButton = new JButton("Next");
        nextButton.addActionListener(e -> goNext());

        JButton cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(e -> {
            result = Result.CANCELLED;
            dispose();
        });

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttonPanel.add(cancelButton);
        buttonPanel.add(previousButton);
        buttonPanel.add(nextButton);

        JPanel mainPanel = new JPanel(new BorderLayout(8, 8));
        mainPanel.setBorder(javax.swing.BorderFactory.createEmptyBorder(16, 16, 16, 16));
        mainPanel.add(headerPanel, BorderLayout.NORTH);
        mainPanel.add(cardPanel, BorderLayout.CENTER);
        mainPanel.add(buttonPanel, BorderLayout.SOUTH);

        setContentPane(mainPanel);
        updateButtonStates();
    }

    private void updateLabels() {
        int total = conflicts.size();
        int current = currentIndex + 1;
        progressLabel.setText("Conflict " + current + "/" + total);
        progressLabel.setFont(progressLabel.getFont().deriveFont(java.awt.Font.BOLD));
        if (currentIndex < conflicts.size()) {
            pathLabel.setText(conflicts.get(currentIndex).getPath());
        }
    }

    private void updateButtonStates() {
        previousButton.setEnabled(currentIndex > 0);
        if (currentIndex < conflicts.size() - 1) {
            nextButton.setText("Next");
        } else {
            nextButton.setText("Done");
        }
    }

    private void applyCurrentResolution() {
        ConflictInfo conflict = conflicts.get(currentIndex);
        JPanel panel = conflictPanels.get(currentIndex);

        if (conflict.isBinary()) {
            BinaryConflictPanel bp = (BinaryConflictPanel) panel;
            conflict.setResolution(toConflictInfoResolution(bp.getResolution()));
            conflict.setApplyTarget(bp.getApplyTarget());
        } else {
            TextMergePanel tp = (TextMergePanel) panel;
            TextMergePanel.Resolution r = tp.getResolution();
            conflict.setResolution(toConflictInfoResolution(r));
            conflict.setApplyTarget(tp.getApplyTarget());
            if (r == TextMergePanel.Resolution.MERGE) {
                String merged = tp.getMergedContent();
                if (merged != null) {
                    conflict.setMergedContent(merged);
                }
            }
        }
    }

    private ConflictInfo.Resolution toConflictInfoResolution(BinaryConflictPanel.Resolution r) {
        return switch (r) {
            case KEEP_LOCAL -> ConflictInfo.Resolution.KEEP_LOCAL;
            case KEEP_REMOTE -> ConflictInfo.Resolution.KEEP_REMOTE;
            case SKIP -> ConflictInfo.Resolution.SKIP;
        };
    }

    private ConflictInfo.Resolution toConflictInfoResolution(TextMergePanel.Resolution r) {
        return switch (r) {
            case KEEP_LOCAL -> ConflictInfo.Resolution.KEEP_LOCAL;
            case KEEP_REMOTE -> ConflictInfo.Resolution.KEEP_REMOTE;
            case MERGE -> ConflictInfo.Resolution.MERGE;
        };
    }

    private void goPrevious() {
        applyCurrentResolution();
        currentIndex--;
        cardLayout.show(cardPanel, "conflict_" + currentIndex);
        updateLabels();
        updateButtonStates();
    }

    private void goNext() {
        applyCurrentResolution();
        if (currentIndex >= conflicts.size() - 1) {
            result = Result.COMPLETED;
            dispose();
            return;
        }
        currentIndex++;
        cardLayout.show(cardPanel, "conflict_" + currentIndex);
        updateLabels();
        updateButtonStates();
    }

    public Result getResult() {
        return result;
    }

    /**
     * Show the unified conflict resolution dialog.
     *
     * @param parent the parent frame
     * @param conflicts list of conflicts to resolve (remote content must be fetched for text files)
     * @return COMPLETED if user resolved all, CANCELLED if user cancelled
     */
    public static Result showDialog(JFrame parent, java.util.List<ConflictInfo> conflicts) {
        if (conflicts == null || conflicts.isEmpty()) {
            return Result.COMPLETED;
        }
        ConflictResolutionDialog dialog = new ConflictResolutionDialog(parent, conflicts);
        dialog.setVisible(true);
        return dialog.getResult();
    }
}
