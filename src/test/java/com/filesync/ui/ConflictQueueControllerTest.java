package com.filesync.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.filesync.sync.ConflictInfo;
import com.filesync.sync.ConflictInfo.ApplyTarget;
import com.filesync.sync.ConflictInfo.Resolution;
import com.filesync.sync.FileChangeDetector;
import java.awt.Component;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The queue drives the whole conflict window: which card is in front, when its panel is built, and
 * when a text conflict's remote version is fetched (a blocking serial round trip, one file at a
 * time, off the event dispatch thread). A real dialog is a modal window and cannot be built in this
 * headless JVM, so the queue is driven directly through its view.
 *
 * <p>The regression this class pins down: a card whose remote version was in flight stayed on its
 * "Loading remote version..." placeholder forever — the worker that finished the fetch only
 * attached the real panel when the card had none, and the placeholder already was one, so the card
 * could never be resolved and the only way out was Cancel.
 */
class ConflictQueueControllerTest {

    private static final long AWAIT_SECONDS = 5;

    private final RecordingQueueView view = new RecordingQueueView();

    /** Everything the queue published while the test ran, plus the outcome once it finished. */
    private static final class RecordingQueueView implements ConflictQueueController.View {

        private final List<ConflictQueueController.ViewState> states =
                Collections.synchronizedList(new ArrayList<>());
        private final AtomicReference<ConflictResolutionDialog.Result> finished =
                new AtomicReference<>();

        @Override
        public void onStateChanged(ConflictQueueController.ViewState state) {
            states.add(state);
        }

        @Override
        public void onFinished(ConflictResolutionDialog.Result result) {
            finished.set(result);
        }

        List<ConflictQueueController.ViewState> states() {
            synchronized (states) {
                return new ArrayList<>(states);
            }
        }

        ConflictQueueController.ViewState latestState() {
            List<ConflictQueueController.ViewState> published = states();
            return published.get(published.size() - 1);
        }

        boolean isFinished() {
            return finished.get() != null;
        }

        ConflictResolutionDialog.Result finishedResult() {
            return finished.get();
        }
    }

    private static ConflictInfo textConflict(String path, String local) {
        return new ConflictInfo(
                path,
                new FileChangeDetector.FileInfo(path, 12L, 1L, "md5-local"),
                new FileChangeDetector.FileInfo(path, 14L, 9L, "md5-remote"),
                false,
                local.getBytes());
    }

    private static ConflictInfo binaryConflict(String path) {
        return new ConflictInfo(
                path,
                new FileChangeDetector.FileInfo(path, 12L, 1L, null),
                new FileChangeDetector.FileInfo(path, 9L, 9L, null),
                true,
                null);
    }

    private static long countPanels(JPanel cardPanel, Class<? extends Component> type) {
        return List.of(cardPanel.getComponents()).stream().filter(type::isInstance).count();
    }

    private static boolean hasPlaceholder(JPanel cardPanel) {
        return countPanels(cardPanel, ConflictQueueController.PlaceholderPanel.class) > 0;
    }

    /**
     * Polls a condition on the event dispatch thread, where the queue does all of its work - both
     * so the card stack is never read from two threads and so every queued worker step gets its
     * turn to run.
     */
    private boolean awaitOnEdt(BooleanSupplier condition) throws Exception {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(AWAIT_SECONDS);
        while (System.currentTimeMillis() < deadline) {
            final boolean[] met = {false};
            SwingUtilities.invokeAndWait(() -> met[0] = condition.getAsBoolean());
            if (met[0]) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    /** Builds a queue on the event dispatch thread, the way the dialog does. */
    private ConflictQueueController queueOnEdt(
            List<ConflictInfo> conflicts, ConflictResolutionDialog.RemoteContentFetcher fetcher)
            throws Exception {
        ConflictQueueController[] created = new ConflictQueueController[1];
        SwingUtilities.invokeAndWait(
                () -> created[0] = new ConflictQueueController(conflicts, fetcher, view));
        return created[0];
    }

    @Test
    @Timeout(30)
    void aFetchedRemoteVersionReplacesTheLoadingPlaceholder() throws Exception {
        CountDownLatch fetchCalled = new CountDownLatch(1);
        ConflictInfo conflict = textConflict("a.txt", "local line\n");

        ConflictQueueController queue =
                queueOnEdt(
                        List.of(conflict),
                        path -> {
                            fetchCalled.countDown();
                            return "remote line\n".getBytes();
                        });

        assertTrue(fetchCalled.await(AWAIT_SECONDS, TimeUnit.SECONDS), "the fetch must have run");
        assertTrue(
                hasPlaceholder(queue.getCardPanel()), "the card shows the fetch placeholder first");

        assertTrue(
                awaitOnEdt(
                        () ->
                                !hasPlaceholder(queue.getCardPanel())
                                        && countPanels(queue.getCardPanel(), TextMergePanel.class)
                                                == 1),
                "the finished card must show its merge panel instead of the loading placeholder");

        assertEquals("remote line\n", conflict.getRemoteContentAsString());
        ConflictQueueController.ViewState state = view.latestState();
        assertEquals(0, state.getCurrentIndex());
        assertEquals(1, state.getTotal());
        assertEquals("a.txt", state.getPath());
        assertFalse(state.isApplyToAllEnabled(), "a single conflict has nothing left to apply to");
    }

    @Test
    @Timeout(30)
    void anUnavailableRemoteVersionStillOffersTheChoicePanel() throws Exception {
        CountDownLatch fetchCalled = new CountDownLatch(1);
        ConflictInfo conflict = textConflict("a.txt", "local line\n");

        ConflictQueueController queue =
                queueOnEdt(
                        List.of(conflict),
                        path -> {
                            fetchCalled.countDown();
                            return null; // the peer never answered
                        });

        assertTrue(fetchCalled.await(AWAIT_SECONDS, TimeUnit.SECONDS), "the fetch must have run");
        assertTrue(
                awaitOnEdt(
                        () ->
                                !hasPlaceholder(queue.getCardPanel())
                                        && countPanels(queue.getCardPanel(), TextMergePanel.class)
                                                == 1),
                "a card whose remote version never arrived must still offer its choices");

        assertNull(conflict.getRemoteContent(), "no content was fetched");
        assertTrue(queue.getCardPanel().getComponentCount() > 0, "a real card panel is in place");
    }

    @Test
    @Timeout(30)
    void aThrowingFetchStillOffersTheChoicePanel() throws Exception {
        CountDownLatch fetchCalled = new CountDownLatch(1);
        ConflictInfo conflict = textConflict("a.txt", "local line\n");

        ConflictQueueController queue =
                queueOnEdt(
                        List.of(conflict),
                        path -> {
                            fetchCalled.countDown();
                            throw new IllegalStateException("peer unreachable");
                        });

        assertTrue(fetchCalled.await(AWAIT_SECONDS, TimeUnit.SECONDS), "the fetch must have run");
        assertTrue(
                awaitOnEdt(
                        () ->
                                !hasPlaceholder(queue.getCardPanel())
                                        && countPanels(queue.getCardPanel(), TextMergePanel.class)
                                                == 1),
                "a fetch that blew up must still show the card's choices, not a dead placeholder");
        assertNull(conflict.getRemoteContent(), "nothing was fetched");
    }

    @Test
    @Timeout(30)
    void theFirstCardDisablesPreviousAndTheLastOneOffersDone() throws Exception {
        List<ConflictInfo> conflicts =
                List.of(textConflict("a.txt", "local a\n"), textConflict("b.txt", "local b\n"));
        for (ConflictInfo conflict : conflicts) {
            conflict.setRemoteContent(("remote " + conflict.getPath()).getBytes());
        }
        AtomicInteger fetchCalls = new AtomicInteger();

        ConflictQueueController queue =
                queueOnEdt(
                        conflicts,
                        path -> {
                            fetchCalls.incrementAndGet();
                            return null;
                        });

        assertEquals(0, fetchCalls.get(), "both remote versions are already to hand");
        conflictText(0, "a.txt", 2, false, false, true);

        queue.next();
        conflictText(1, "b.txt", 2, true, true, false);

        queue.next();
        assertTrue(view.isFinished(), "walking off the last card completes the resolution");
        assertEquals(ConflictResolutionDialog.Result.COMPLETED, view.finishedResult());
    }

    private void conflictText(
            int index,
            String path,
            int total,
            boolean previousEnabled,
            boolean nextIsDone,
            boolean applyToAllEnabled) {
        ConflictQueueController.ViewState state = view.latestState();
        assertEquals(index, state.getCurrentIndex(), "conflict " + path + " is in front");
        assertEquals(total, state.getTotal());
        assertEquals(path, state.getPath());
        assertEquals(previousEnabled, state.isPreviousEnabled(), "Previous on " + path);
        assertEquals(nextIsDone, state.isNextIsDone(), "Next/Done on " + path);
        assertEquals(
                applyToAllEnabled,
                state.isApplyToAllEnabled(),
                "Use this for all remaining on " + path);
    }

    @Test
    @Timeout(30)
    void navigatingToTheNextCardLeavesAnUnfinishedFetchAlone() throws Exception {
        CountDownLatch firstFetchEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstFetch = new CountDownLatch(1);
        ConflictInfo first = textConflict("a.txt", "local a\n");
        ConflictInfo second = textConflict("b.txt", "local b\n");

        ConflictQueueController queue =
                queueOnEdt(
                        List.of(first, second),
                        path -> {
                            if ("a.txt".equals(path)) {
                                firstFetchEntered.countDown();
                                awaitRelease(releaseFirstFetch);
                                return "remote a\n".getBytes();
                            }
                            return "remote b\n".getBytes();
                        });

        assertTrue(
                firstFetchEntered.await(AWAIT_SECONDS, TimeUnit.SECONDS), "the first fetch runs");
        assertTrue(
                hasPlaceholder(queue.getCardPanel()),
                "the first card waits for its remote version");

        queue.next();
        assertTrue(
                awaitOnEdt(
                        () ->
                                countPanels(queue.getCardPanel(), TextMergePanel.class) == 1
                                        && hasPlaceholder(queue.getCardPanel())),
                "the second card is ready while the first still fetches");
        assertEquals("b.txt", view.latestState().getPath(), "the second card is in front");

        releaseFirstFetch.countDown();
        assertTrue(
                awaitOnEdt(
                        () ->
                                hasPlaceholder(queue.getCardPanel()) == false
                                        && countPanels(queue.getCardPanel(), TextMergePanel.class)
                                                == 2),
                "the late fetch must still finish the card it belongs to");

        assertEquals(
                "b.txt",
                view.latestState().getPath(),
                "a fetch that completed for a card the user walked past must not pull it back");
        assertEquals("remote a\n", first.getRemoteContentAsString());
    }

    @Test
    @Timeout(30)
    void aBinaryCardNeedsNoRemoteVersion() throws Exception {
        AtomicInteger fetchCalls = new AtomicInteger();
        ConflictInfo conflict = binaryConflict("a.bin");

        ConflictQueueController queue =
                queueOnEdt(
                        List.of(conflict),
                        path -> {
                            fetchCalls.incrementAndGet();
                            return new byte[0];
                        });

        assertEquals(
                0,
                fetchCalls.get(),
                "a binary card is decided from the manifest metadata the manifests already carry");
        JPanel cardPanel = queue.getCardPanel();
        assertEquals(1, cardPanel.getComponentCount());
        assertTrue(cardPanel.getComponent(0) instanceof BinaryConflictPanel);
        assertFalse(view.latestState().isApplyToAllEnabled());
    }

    @Test
    @Timeout(30)
    void aPreloadedRemoteVersionSkipsTheFetch() throws Exception {
        AtomicInteger fetchCalls = new AtomicInteger();
        ConflictInfo conflict = textConflict("a.txt", "local line\n");
        conflict.setRemoteContent("remote line\n".getBytes());

        ConflictQueueController queue =
                queueOnEdt(
                        List.of(conflict),
                        path -> {
                            fetchCalls.incrementAndGet();
                            return null;
                        });

        assertEquals(0, fetchCalls.get(), "the local queue already has the remote version");
        assertFalse(hasPlaceholder(queue.getCardPanel()));
        assertEquals(1, countPanels(queue.getCardPanel(), TextMergePanel.class));
    }

    @Test
    @Timeout(30)
    void aWhitespaceOnlyDifferenceNeverShowsACardAndCompletes() throws Exception {
        ConflictInfo conflict = textConflict("a.txt", "one\n\ntwo\n");
        CountDownLatch fetchCalled = new CountDownLatch(1);

        ConflictQueueController queue =
                queueOnEdt(
                        List.of(conflict),
                        path -> {
                            fetchCalled.countDown();
                            return "one\ntwo\n".getBytes(); // the blank line is the only change
                        });

        assertTrue(fetchCalled.await(AWAIT_SECONDS, TimeUnit.SECONDS), "the fetch must have run");
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(AWAIT_SECONDS);
        while (!view.isFinished() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }

        assertTrue(view.isFinished(), "a queue of one trivial conflict runs dry on its own");
        assertEquals(ConflictResolutionDialog.Result.COMPLETED, view.finishedResult());
        assertEquals(Resolution.KEEP_LOCAL, conflict.getResolution());
        assertFalse(conflict.hasMeaningfulDifferences(), "the difference is whitespace only");
        assertEquals(0, queue.getCardPanel().getComponentCount(), "no card was left behind");
    }

    @Test
    @Timeout(30)
    void cancelRecordsNothingAndStopsTheResolution() throws Exception {
        ConflictInfo conflict = textConflict("a.txt", "local line\n");
        conflict.setRemoteContent("remote line\n".getBytes());

        ConflictQueueController queue = queueOnEdt(List.of(conflict), path -> null);
        assertFalse(view.isFinished());

        queue.cancel();
        assertTrue(view.isFinished());
        assertEquals(ConflictResolutionDialog.Result.CANCELLED, view.finishedResult());
        assertEquals(
                Resolution.UNRESOLVED,
                conflict.getResolution(),
                "cancelling abandons the conflict rather than resolving it");
    }

    @Test
    @Timeout(30)
    void leavingTheLastCardCompletesWithTheChoiceAlreadyMade() throws Exception {
        ConflictInfo conflict = textConflict("a.txt", "local line\n");
        conflict.setRemoteContent("remote line\n".getBytes());

        ConflictQueueController queue = queueOnEdt(List.of(conflict), path -> null);
        queue.next();

        assertEquals(ConflictResolutionDialog.Result.COMPLETED, view.finishedResult());
        assertEquals(
                Resolution.KEEP_LOCAL,
                conflict.getResolution(),
                "the card's current choice is what the sync is told to do");
        assertEquals(ApplyTarget.REMOTE_ONLY, conflict.getApplyTarget());
        assertNull(conflict.getMergedContent(), "no merge was built");
    }

    @Test
    @Timeout(30)
    void applyToAllRemainingHandsTheCurrentChoiceToTheConflictsBehindIt() throws Exception {
        List<ConflictInfo> conflicts =
                List.of(
                        textConflict("a.txt", "local a\n"),
                        textConflict("b.txt", "local b\n"),
                        textConflict("c.txt", "local c\n"));
        for (ConflictInfo conflict : conflicts) {
            conflict.setRemoteContent(("remote " + conflict.getPath()).getBytes());
        }

        ConflictQueueController queue = queueOnEdt(conflicts, path -> null);
        assertTrue(
                view.latestState().isApplyToAllEnabled(), "there are conflicts behind the first");

        TextMergePanel firstCard = (TextMergePanel) queue.getCardPanel().getComponent(0);
        firstCard.getKeepRemoteRadio().doClick();
        assertTrue(view.latestState().isApplyToAllEnabled(), "keep remote is content-free");

        queue.applyToAllRemaining();

        assertEquals(ConflictResolutionDialog.Result.COMPLETED, view.finishedResult());
        for (ConflictInfo conflict : conflicts) {
            assertEquals(Resolution.KEEP_REMOTE, conflict.getResolution(), conflict.getPath());
            assertEquals(ApplyTarget.BOTH, conflict.getApplyTarget(), conflict.getPath());
        }
    }

    @Test
    @Timeout(30)
    void aMergeChoiceDisablesApplyToAllRemaining() throws Exception {
        List<ConflictInfo> conflicts =
                List.of(textConflict("a.txt", "local a\n"), textConflict("b.txt", "local b\n"));
        for (ConflictInfo conflict : conflicts) {
            conflict.setRemoteContent(("remote " + conflict.getPath()).getBytes());
        }

        ConflictQueueController queue = queueOnEdt(conflicts, path -> null);
        assertTrue(view.latestState().isApplyToAllEnabled());

        TextMergePanel firstCard = (TextMergePanel) queue.getCardPanel().getComponent(0);
        firstCard.getMergeRadio().doClick();

        assertFalse(
                view.latestState().isApplyToAllEnabled(),
                "a merge is built per file, so there is nothing to hand to unseen cards");
    }

    @Test
    @Timeout(30)
    void aTrivialConflictBehindTheCurrentOneShiftsTheQueue() throws Exception {
        CountDownLatch releaseSecond = new CountDownLatch(1);
        ConflictInfo meaningful = textConflict("a.txt", "local a\n");
        meaningful.setRemoteContent("remote a\n".getBytes());
        ConflictInfo trivial = textConflict("b.txt", "one\n\ntwo\n");

        ConflictQueueController queue =
                queueOnEdt(
                        List.of(meaningful, trivial),
                        path -> {
                            awaitRelease(releaseSecond);
                            return "one\ntwo\n".getBytes();
                        });

        queue.next();
        assertEquals("b.txt", view.latestState().getPath(), "the trivial conflict is in front");

        releaseSecond.countDown();
        assertTrue(
                awaitOnEdt(
                        () ->
                                countPanels(queue.getCardPanel(), TextMergePanel.class) == 1
                                        && view.latestState().getTotal() == 1),
                "the dropped card leaves the queue with the conflict that still needs a decision");

        assertFalse(view.isFinished(), "the other conflict still has to be resolved");
        assertEquals("a.txt", view.latestState().getPath(), "the card in front fell back to a.txt");
        assertEquals(Resolution.KEEP_LOCAL, trivial.getResolution());
        assertEquals(
                1,
                queue.getCardPanel().getComponentCount(),
                "only the conflict that still needs a decision keeps its card");

        queue.next();
        assertEquals(ConflictResolutionDialog.Result.COMPLETED, view.finishedResult());
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            release.await(AWAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
