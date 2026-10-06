package com.filesync.ui;

import com.filesync.sync.ConflictAnalyzer;
import com.filesync.sync.ConflictInfo;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingWorker;

/**
 * The conflict queue behind the unified conflict dialog, without the window: which card is in
 * front, building a card's panel the first time it is shown, and fetching a text conflict's remote
 * version at that moment.
 *
 * <p>Fetching is a blocking serial round trip per file (a 10 s timeout each, possibly a whole
 * XMODEM transfer), so a queue of fifty conflicts must not pay for all fifty up front — it pays for
 * the ones the user actually walks through, one at a time, off the event dispatch thread. Binary
 * conflicts need no content at all: their panels show what the manifests already carry (size,
 * modified time, MD5 prefix). Text conflicts that turn out to differ only in whitespace are
 * resolved locally and dropped from the queue without ever showing a card, which is what the
 * pre-fetch filter used to guarantee.
 *
 * <p>The queue owns no window on purpose. A modal {@link javax.swing.JDialog} cannot be built where
 * no display exists — the unit-test JVM runs headless, so surefire turns any window into a {@link
 * java.awt.HeadlessException} — and the card lifecycle is exactly the part that needs covering
 * against a real queue. It therefore reports through {@link View}, and {@link
 * ConflictResolutionDialog} is left as a thin shell that renders {@link ViewState} and closes on
 * {@link View#onFinished}.
 */
class ConflictQueueController {

    /**
     * What a card shows while its remote version is in flight. A distinct type rather than a plain
     * panel so the worker that finishes the fetch can tell "the real panel is not built yet" from
     * "the real panel replaced the placeholder" — both states have a panel attached.
     */
    static final class PlaceholderPanel extends JPanel {
        private final String message;

        PlaceholderPanel(String message) {
            super(new BorderLayout());
            this.message = message;
            add(new JLabel(message, JLabel.CENTER), BorderLayout.CENTER);
        }

        String getMessage() {
            return message;
        }
    }

    /** Everything the dialog's header and controls render. */
    static final class ViewState {
        private final int currentIndex;
        private final int total;
        private final String path;
        private final boolean previousEnabled;
        private final boolean nextIsDone;
        private final boolean applyToAllEnabled;

        ViewState(
                int currentIndex,
                int total,
                String path,
                boolean previousEnabled,
                boolean nextIsDone,
                boolean applyToAllEnabled) {
            this.currentIndex = currentIndex;
            this.total = total;
            this.path = path;
            this.previousEnabled = previousEnabled;
            this.nextIsDone = nextIsDone;
            this.applyToAllEnabled = applyToAllEnabled;
        }

        int getCurrentIndex() {
            return currentIndex;
        }

        int getTotal() {
            return total;
        }

        String getPath() {
            return path;
        }

        boolean isPreviousEnabled() {
            return previousEnabled;
        }

        boolean isNextIsDone() {
            return nextIsDone;
        }

        boolean isApplyToAllEnabled() {
            return applyToAllEnabled;
        }
    }

    /** The window that owns the queue. */
    interface View {
        void onStateChanged(ViewState state);

        void onFinished(ConflictResolutionDialog.Result result);
    }

    /** One queue entry: the conflict plus the state of its (possibly not yet built) card. */
    private static final class Card {
        private final ConflictInfo conflict;
        private final String layoutName;
        private JPanel panel;
        private boolean remoteContentRequested;

        private Card(ConflictInfo conflict, String layoutName) {
            this.conflict = conflict;
            this.layoutName = layoutName;
        }
    }

    private static final String LOADING_TEXT = "Loading remote version...";

    private final ConflictResolutionDialog.RemoteContentFetcher remoteContentFetcher;
    private final View view;
    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cardPanel = new JPanel(cardLayout);
    private final List<Card> cards = new ArrayList<>();
    private int currentIndex = 0;
    private int nextLayoutName = 0;

    ConflictQueueController(
            List<ConflictInfo> conflicts,
            ConflictResolutionDialog.RemoteContentFetcher remoteContentFetcher,
            View view) {
        this.remoteContentFetcher = remoteContentFetcher;
        this.view = view;
        for (int i = 0; i < conflicts.size(); i++) {
            cards.add(new Card(conflicts.get(i), "conflict_" + nextLayoutName++));
        }
        if (cards.isEmpty()) {
            view.onFinished(ConflictResolutionDialog.Result.COMPLETED);
            return;
        }
        showCard(0);
    }

    /** The stack the cards are laid out in; the dialog places it in its content pane. */
    JPanel getCardPanel() {
        return cardPanel;
    }

    void previous() {
        applyCurrentResolution();
        currentIndex--;
        showCard(currentIndex);
    }

    /** "Next", or "Done" on the last card. */
    void next() {
        applyCurrentResolution();
        if (currentIndex >= cards.size() - 1) {
            finish(ConflictResolutionDialog.Result.COMPLETED);
            return;
        }
        currentIndex++;
        showCard(currentIndex);
    }

    void cancel() {
        finish(ConflictResolutionDialog.Result.CANCELLED);
    }

    /**
     * Hand the current resolution to every conflict after this one, without opening their cards.
     * Their content-dependent choices (a merge) are excluded by the button's enabled state.
     */
    void applyToAllRemaining() {
        applyCurrentResolution();
        ConflictInfo.Resolution resolution = currentResolution();
        ConflictInfo.ApplyTarget applyTarget = cards.get(currentIndex).conflict.getApplyTarget();
        for (int i = currentIndex + 1; i < cards.size(); i++) {
            ConflictInfo conflict = cards.get(i).conflict;
            conflict.setResolution(resolution);
            conflict.setApplyTarget(applyTarget);
        }
        finish(ConflictResolutionDialog.Result.COMPLETED);
    }

    private void finish(ConflictResolutionDialog.Result result) {
        view.onFinished(result);
    }

    // ========== card lifecycle ==========

    /**
     * Make the card at {@code index} the visible one, building it (and starting its remote fetch)
     * on first view.
     */
    private void showCard(int index) {
        if (index < 0 || index >= cards.size()) {
            return;
        }
        currentIndex = index;
        publishState();

        Card card = cards.get(index);
        if (card.panel != null) {
            cardLayout.show(cardPanel, card.layoutName);
            return;
        }
        if (card.conflict.isBinary()) {
            // A binary card shows manifest metadata only: no remote bytes are needed to choose.
            attachPanel(card, new BinaryConflictPanel(card.conflict));
            return;
        }
        if (card.remoteContentRequested) {
            // The fetch is in flight. The placeholder stays until the worker replaces it;
            // navigating back later finds the built panel.
            cardLayout.show(cardPanel, card.layoutName);
            return;
        }
        card.remoteContentRequested = true;
        if (card.conflict.getRemoteContent() != null || remoteContentFetcher == null) {
            attachPanel(card, new TextMergePanel(card.conflict));
        } else {
            attachPanel(card, new PlaceholderPanel(LOADING_TEXT));
            startRemoteFetch(card);
        }
    }

    private void startRemoteFetch(Card card) {
        new SwingWorker<byte[], Void>() {
            @Override
            protected byte[] doInBackground() {
                return remoteContentFetcher.fetch(card.conflict.getPath());
            }

            @Override
            protected void done() {
                byte[] content = null;
                try {
                    content = get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (java.util.concurrent.ExecutionException e) {
                    // An unreachable peer leaves the conflict with the metadata the manifests
                    // already carry; the panel is built from that so the user can still choose.
                }
                onRemoteContentArrived(card, content);
            }
        }.execute();
    }

    private void onRemoteContentArrived(Card card, byte[] content) {
        // The card may have moved while the fetch ran (another one was dropped), so look its
        // position up rather than trusting the one captured when the fetch started.
        int index = cards.indexOf(card);
        if (index < 0) {
            return;
        }
        if (content != null) {
            card.conflict.setRemoteContent(content);
            // With both sides in hand, a difference that is pure whitespace or blank lines never
            // deserved a page of its own: resolve it for the sender and drop the card.
            List<ConflictInfo> single = new ArrayList<>();
            single.add(card.conflict);
            ConflictAnalyzer.filterTrivialConflicts(single);
            if (card.conflict.getResolution() == ConflictInfo.Resolution.KEEP_LOCAL
                    && !card.conflict.hasMeaningfulDifferences()) {
                dropCard(index);
                return;
            }
        }
        if (card.panel instanceof PlaceholderPanel) {
            // The fetch is over, whether it succeeded, came back empty or never answered. Either
            // way the placeholder has to go: leaving it up strands the user on a card that can
            // never be resolved, and a panel built from what the manifests carry still allows every
            // choice the sync offers.
            attachPanel(card, new TextMergePanel(card.conflict));
        }
    }

    /**
     * Remove a card from the queue (a trivial conflict resolved without the user). Shows the card
     * that takes its place, or the previous one when it was the last, or finishes when the queue
     * ran dry.
     */
    private void dropCard(int index) {
        if (index < 0 || index >= cards.size()) {
            return;
        }
        Card removed = cards.remove(index);
        if (removed.panel != null) {
            cardPanel.remove(removed.panel);
        }
        if (cards.isEmpty()) {
            finish(ConflictResolutionDialog.Result.COMPLETED);
            return;
        }
        if (index < currentIndex) {
            currentIndex--; // the current card shifted down
        }
        // When the current card itself was dropped, the one that took its place now sits at the
        // same index; when it was the last card, clamp back to the new last.
        currentIndex = Math.min(currentIndex, cards.size() - 1);
        showCard(currentIndex);
    }

    private void attachPanel(Card card, JPanel panel) {
        if (card.panel != null) {
            cardPanel.remove(card.panel);
        }
        card.panel = panel;
        if (panel instanceof ConflictChoicePanel choicePanel) {
            choicePanel.addSelectionChangeListener(this::publishState);
        }
        cardPanel.add(panel, card.layoutName);
        cardPanel.revalidate();
        cardPanel.repaint();
        if (cards.indexOf(card) == currentIndex) {
            cardLayout.show(cardPanel, card.layoutName);
        }
        publishState();
    }

    // ========== header and buttons ==========

    private void publishState() {
        if (cards.isEmpty()) {
            return;
        }
        Card card = cards.get(currentIndex);
        boolean last = currentIndex >= cards.size() - 1;
        view.onStateChanged(
                new ViewState(
                        currentIndex,
                        cards.size(),
                        card.conflict.getPath(),
                        currentIndex > 0,
                        last,
                        canApplyCurrentResolutionToAllRemaining()));
    }

    /**
     * "Use this for all remaining" only makes sense when the current choice is content-free: a
     * merge is built per file, so there is no merged text to hand to the conflicts whose merge view
     * was never opened. Nothing to apply to is another reason to stay disabled.
     */
    private boolean canApplyCurrentResolutionToAllRemaining() {
        if (currentIndex >= cards.size() - 1) {
            return false;
        }
        Card card = cards.get(currentIndex);
        return card.panel instanceof ConflictChoicePanel choicePanel
                ? choicePanel.getConflictResolution() != ConflictInfo.Resolution.MERGE
                : false;
    }

    private ConflictInfo.Resolution currentResolution() {
        JPanel panel = cards.get(currentIndex).panel;
        return panel instanceof ConflictChoicePanel choicePanel
                ? choicePanel.getConflictResolution()
                : ConflictInfo.Resolution.UNRESOLVED;
    }

    // ========== resolution bookkeeping ==========

    private void applyCurrentResolution() {
        if (currentIndex >= cards.size()) {
            return;
        }
        Card card = cards.get(currentIndex);
        JPanel panel = card.panel;

        if (panel instanceof ConflictChoicePanel choicePanel) {
            ConflictInfo.Resolution resolution = choicePanel.getConflictResolution();
            card.conflict.setResolution(resolution);
            card.conflict.setApplyTarget(choicePanel.getApplyTarget());
            // A merge is built per file from the panel's edit area; every other choice is
            // content-free and fully described by resolution and apply target.
            if (panel instanceof TextMergePanel textPanel
                    && resolution == ConflictInfo.Resolution.MERGE) {
                String merged = textPanel.getMergedContent();
                if (merged != null) {
                    card.conflict.setMergedContent(merged);
                }
            }
        } else {
            // The card's remote version is still in flight. Leaving it records the default the
            // sync would apply anyway, so every conflict carries an explicit resolution once the
            // dialog closes.
            card.conflict.setResolution(ConflictInfo.Resolution.KEEP_LOCAL);
            card.conflict.setApplyTarget(ConflictInfo.ApplyTarget.REMOTE_ONLY);
        }
    }
}
