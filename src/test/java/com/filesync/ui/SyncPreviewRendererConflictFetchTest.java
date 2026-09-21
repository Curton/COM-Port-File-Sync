package com.filesync.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.filesync.sync.ConflictInfo;
import com.filesync.sync.FileChangeDetector;
import com.filesync.sync.SyncPreviewPlan;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Fetching the remote version of every conflicting file is a blocking serial round trip (a 10 s
 * timeout each, possibly a whole XMODEM transfer). It must therefore not run on the event dispatch
 * thread, or the UI freezes for as long as the slowest peer takes to answer.
 */
class SyncPreviewRendererConflictFetchTest {

    @Test
    @Timeout(30)
    void remoteContentIsFetchedOffTheEventDispatchThread() throws Exception {
        CountDownLatch fetchEntered = new CountDownLatch(1);
        CountDownLatch releaseFetch = new CountDownLatch(1);
        AtomicBoolean completed = new AtomicBoolean(false);
        AtomicReference<Boolean> callbackResult = new AtomicReference<>();

        SyncPreviewRenderer.ConflictResolver slowResolver =
                path -> {
                    fetchEntered.countDown();
                    // Model a peer that answers slowly, or not at all.
                    try {
                        releaseFetch.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                };

        SyncPreviewRenderer renderer = new SyncPreviewRenderer(null, slowResolver, msg -> {});
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
    }

    private static SyncPreviewPlan planWithOneUnresolvedConflict() {
        FileChangeDetector.FileInfo conflictFile =
                new FileChangeDetector.FileInfo("conflict.txt", 0L, 0L, "md5-local");
        ConflictInfo conflict =
                new ConflictInfo(
                        "conflict.txt",
                        conflictFile,
                        new FileChangeDetector.FileInfo("conflict.txt", 0L, 0L, "md5-remote"),
                        false,
                        new byte[0]);
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
