package com.filesync.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.filesync.sync.ConflictInfo;
import com.filesync.sync.ConflictInfo.ApplyTarget;
import com.filesync.sync.ConflictInfo.Resolution;
import com.filesync.sync.FileChangeDetector;
import com.filesync.sync.SyncPreviewPlan;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The remote version of a conflict is a blocking serial round trip (a 10 s timeout each, possibly a
 * whole XMODEM transfer). Text conflicts fetch it lazily, when their card is shown; the ones whose
 * chosen resolution writes the remote version locally fetch it after the dialog closes. Neither may
 * run on the event dispatch thread, or the UI freezes for as long as the slowest peer takes to
 * answer.
 */
class SyncPreviewRendererConflictFetchTest {

    /**
     * Stub the dialog out: a real one is a modal window, and no display exists in a unit test. The
     * stub models a resolution the user picked without ever opening the conflicts' cards.
     */
    private static final class StubbedDialogRenderer extends SyncPreviewRenderer {
        private final Resolution resolution;
        private final ApplyTarget applyTarget;

        private StubbedDialogRenderer(
                ConflictResolver resolver, Resolution resolution, ApplyTarget applyTarget) {
            super(null, resolver, msg -> {});
            this.resolution = resolution;
            this.applyTarget = applyTarget;
        }

        @Override
        protected ConflictResolutionDialog.Result showConflictDialog(
                List<ConflictInfo> conflicts, ConflictResolver resolver) {
            for (ConflictInfo conflict : conflicts) {
                conflict.setResolution(resolution);
                conflict.setApplyTarget(applyTarget);
            }
            return ConflictResolutionDialog.Result.COMPLETED;
        }
    }

    @Test
    @Timeout(30)
    void adoptedRemoteVersionsAreSettledOffTheEventDispatchThread() throws Exception {
        CountDownLatch fetchEntered = new CountDownLatch(1);
        CountDownLatch releaseFetch = new CountDownLatch(1);
        AtomicBoolean completed = new AtomicBoolean(false);
        AtomicReference<Boolean> callbackResult = new AtomicReference<>();
        AtomicBoolean fetchedOnEdt = new AtomicBoolean(false);

        SyncPreviewRenderer.ConflictResolver slowResolver =
                path -> {
                    fetchedOnEdt.set(SwingUtilities.isEventDispatchThread());
                    fetchEntered.countDown();
                    // Model a peer that answers slowly, or not at all.
                    try {
                        releaseFetch.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "remote content".getBytes();
                };

        StubbedDialogRenderer renderer =
                new StubbedDialogRenderer(slowResolver, Resolution.KEEP_REMOTE, ApplyTarget.BOTH);
        DefaultTableModel model = new DefaultTableModel();
        List<SyncPreviewRow> rows = List.of();

        AtomicBoolean callReturned = new AtomicBoolean(false);
        SwingUtilities.invokeAndWait(
                () -> {
                    renderer.resolveConflictsForSelectedFiles(
                            planWithOneUnresolvedConflict(),
                            slowResolver,
                            model,
                            rows,
                            result -> {
                                callbackResult.set(result);
                                completed.set(true);
                            });
                    callReturned.set(true);
                });

        assertTrue(fetchEntered.await(5, TimeUnit.SECONDS), "the fetch must actually start");
        assertTrue(callReturned.get(), "the call must return without waiting for the fetch");
        assertFalse(completed.get(), "the callback must not have run while the fetch is blocked");

        releaseFetch.countDown();
        assertTrue(awaitCompletion(completed), "resolution must finish once the fetch completes");
        assertEquals(Boolean.TRUE, callbackResult.get(), "a resolved conflict set completes");
        assertFalse(fetchedOnEdt.get(), "the fetch must not run on the event dispatch thread");
    }

    @Test
    @Timeout(30)
    void adoptedRemoteVersionIsRecordedOnTheConflict() throws Exception {
        CountDownLatch fetchEntered = new CountDownLatch(1);
        SyncPreviewPlan plan = planWithOneUnresolvedConflict();
        SyncPreviewRenderer.ConflictResolver resolver =
                path -> {
                    fetchEntered.countDown();
                    return "remote content".getBytes();
                };

        StubbedDialogRenderer renderer =
                new StubbedDialogRenderer(resolver, Resolution.KEEP_REMOTE, ApplyTarget.BOTH);

        AtomicBoolean completed = new AtomicBoolean(false);
        SwingUtilities.invokeAndWait(
                () ->
                        renderer.resolveConflictsForSelectedFiles(
                                plan,
                                resolver,
                                new DefaultTableModel(),
                                List.of(),
                                result -> completed.set(result)));

        assertTrue(fetchEntered.await(5, TimeUnit.SECONDS), "the fetch must start");
        assertTrue(awaitCompletion(completed), "resolution must finish");
        ConflictInfo conflict = plan.getConflicts().get(0);
        assertNotNull(
                conflict.getRemoteContent(),
                "the bytes the local write needs are fetched after the dialog closes");
        assertEquals("remote content", new String(conflict.getRemoteContent()));
    }

    @Test
    @Timeout(30)
    void localOnlyResolutionNeedsNoRemoteContent() throws Exception {
        AtomicInteger fetchCalls = new AtomicInteger();
        AtomicBoolean completed = new AtomicBoolean(false);
        SyncPreviewRenderer.ConflictResolver resolver =
                path -> {
                    fetchCalls.incrementAndGet();
                    return "remote content".getBytes();
                };

        StubbedDialogRenderer renderer =
                new StubbedDialogRenderer(resolver, Resolution.KEEP_LOCAL, ApplyTarget.REMOTE_ONLY);

        SwingUtilities.invokeAndWait(
                () ->
                        renderer.resolveConflictsForSelectedFiles(
                                planWithOneUnresolvedConflict(),
                                resolver,
                                new DefaultTableModel(),
                                List.of(),
                                result -> completed.set(result)));

        assertTrue(awaitCompletion(completed), "a local-only resolution settles immediately");
        assertEquals(0, fetchCalls.get(), "keeping the local version needs no remote bytes");
    }

    @Test
    @Timeout(30)
    void unavailableRemoteVersionDegradesTheResolutionToSkip() throws Exception {
        CountDownLatch fetchEntered = new CountDownLatch(1);
        SyncPreviewPlan plan = planWithOneUnresolvedConflict();
        SyncPreviewRenderer.ConflictResolver failingResolver =
                path -> {
                    fetchEntered.countDown();
                    return null;
                };

        StubbedDialogRenderer renderer =
                new StubbedDialogRenderer(
                        failingResolver, Resolution.KEEP_REMOTE, ApplyTarget.BOTH);

        AtomicBoolean completed = new AtomicBoolean(false);
        SwingUtilities.invokeAndWait(
                () ->
                        renderer.resolveConflictsForSelectedFiles(
                                plan,
                                failingResolver,
                                new DefaultTableModel(),
                                List.of(),
                                result -> completed.set(result)));

        assertTrue(fetchEntered.await(5, TimeUnit.SECONDS), "the fetch must start");
        assertTrue(awaitCompletion(completed), "resolution must finish");
        ConflictInfo conflict = plan.getConflicts().get(0);
        assertEquals(
                Resolution.SKIP,
                conflict.getResolution(),
                "without the remote bytes the resolution cannot be honored, so nothing is"
                        + " transferred or adopted");
        assertNull(conflict.getRemoteContent(), "no content was fetched");
    }

    private static SyncPreviewPlan planWithOneUnresolvedConflict() {
        FileChangeDetector.FileInfo conflictFile =
                new FileChangeDetector.FileInfo("conflict.txt", 13L, 1L, "md5-local");
        ConflictInfo conflict =
                new ConflictInfo(
                        "conflict.txt",
                        conflictFile,
                        new FileChangeDetector.FileInfo("conflict.txt", 14L, 9L, "md5-remote"),
                        false,
                        "local content".getBytes());
        assertNotNull(conflict.getLocalContent(), "the local side must be readable for the merge");
        return new SyncPreviewPlan(
                List.of(conflictFile),
                List.of(),
                List.of(),
                List.of(),
                0L,
                false,
                List.of(conflict));
    }

    private static boolean awaitCompletion(AtomicBoolean completed) throws InterruptedException {
        for (int i = 0; i < 100 && !completed.get(); i++) {
            Thread.sleep(50);
        }
        return completed.get();
    }
}
