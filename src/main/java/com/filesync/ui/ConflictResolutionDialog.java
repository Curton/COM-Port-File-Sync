package com.filesync.ui;

import com.filesync.sync.ConflictInfo;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * Unified dialog for resolving multiple file conflicts in one window. Shows one conflict at a time
 * with Next/Previous navigation and progress (e.g. 2/5). User can cancel at any time to abort the
 * entire resolution.
 *
 * <p>The window is a thin shell: the card lifecycle — which card is in front, when its panel is
 * built, when the remote version of a text conflict is fetched, and what the resolutions amounted
 * to — lives in {@link ConflictQueueController}, which reports the state the header and the
 * controls render. That split is what keeps the queue testable: a modal dialog cannot be built in
 * the headless JVM the unit tests run in.
 */
public class ConflictResolutionDialog extends JDialog {

    public enum Result {
        /** All conflicts resolved successfully */
        COMPLETED,
        /** User cancelled */
        CANCELLED
    }

    /**
     * Fetches the remote version of a path. Contract-blocking: calls happen on a worker thread,
     * never on the event dispatch thread.
     */
    public interface RemoteContentFetcher {
        byte[] fetch(String path);
    }

    private Result result = Result.CANCELLED;

    private final ConflictQueueController queue;

    private final JLabel progressLabel;
    private final JLabel pathLabel;
    private final JButton previousButton;
    private final JButton nextButton;
    private final JButton applyToAllButton;

    public ConflictResolutionDialog(
            JFrame parent,
            List<ConflictInfo> conflicts,
            RemoteContentFetcher remoteContentFetcher) {
        super(parent, "Resolve Conflicts", true);

        setMinimumSize(new Dimension(920, 720));
        setLocationRelativeTo(parent);

        progressLabel = new JLabel();
        pathLabel = new JLabel();

        JPanel headerPanel = new JPanel(new BorderLayout(8, 4));
        headerPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
        headerPanel.add(progressLabel, BorderLayout.NORTH);
        headerPanel.add(pathLabel, BorderLayout.CENTER);

        previousButton = new JButton("Previous");

        nextButton = new JButton("Next");

        applyToAllButton = new JButton("Use this for all remaining");

        JButton cancelButton = new JButton("Cancel");

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttonPanel.add(DialogMaximizeButton.create(this));
        buttonPanel.add(cancelButton);
        buttonPanel.add(applyToAllButton);
        buttonPanel.add(previousButton);
        buttonPanel.add(nextButton);

        JPanel mainPanel = new JPanel(new BorderLayout(8, 8));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        mainPanel.add(headerPanel, BorderLayout.NORTH);
        mainPanel.add(buttonPanel, BorderLayout.SOUTH);

        // Built last: the queue publishes state as soon as it exists, so the controls it drives -
        // and their listeners - are only wired up once it is there, and its card panel goes
        // between them.
        queue = new ConflictQueueController(conflicts, remoteContentFetcher, new QueueView());
        previousButton.addActionListener(e -> queue.previous());
        nextButton.addActionListener(e -> queue.next());
        applyToAllButton.addActionListener(e -> queue.applyToAllRemaining());
        cancelButton.addActionListener(e -> queue.cancel());
        mainPanel.add(queue.getCardPanel(), BorderLayout.CENTER);
        setContentPane(mainPanel);
    }

    /**
     * Show the unified conflict resolution dialog.
     *
     * @param parent the parent frame
     * @param conflicts list of conflicts to resolve
     * @param remoteContentFetcher used to fetch the remote version of a text conflict when its card
     *     is shown; may be null, in which case panels are built from whatever content they already
     *     carry
     * @return COMPLETED if user resolved all, CANCELLED if user cancelled
     */
    public static Result showDialog(
            JFrame parent,
            List<ConflictInfo> conflicts,
            RemoteContentFetcher remoteContentFetcher) {
        if (conflicts == null || conflicts.isEmpty()) {
            return Result.COMPLETED;
        }
        ConflictResolutionDialog dialog =
                new ConflictResolutionDialog(parent, conflicts, remoteContentFetcher);
        dialog.setVisible(true);
        return dialog.getResult();
    }

    public Result getResult() {
        return result;
    }

    /** Mirrors the queue's state onto the header and the controls, and closes on the outcome. */
    private final class QueueView implements ConflictQueueController.View {

        @Override
        public void onStateChanged(ConflictQueueController.ViewState state) {
            progressLabel.setText(
                    "Conflict " + (state.getCurrentIndex() + 1) + "/" + state.getTotal());
            progressLabel.setFont(progressLabel.getFont().deriveFont(java.awt.Font.BOLD));
            pathLabel.setText(state.getPath());
            previousButton.setEnabled(state.isPreviousEnabled());
            nextButton.setText(state.isNextIsDone() ? "Done" : "Next");
            applyToAllButton.setEnabled(state.isApplyToAllEnabled());
        }

        @Override
        public void onFinished(Result result) {
            ConflictResolutionDialog.this.result = result;
            dispose();
        }
    }
}
