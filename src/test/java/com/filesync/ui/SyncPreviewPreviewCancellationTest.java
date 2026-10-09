package com.filesync.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A row's Pre fetch is a blocking serial round trip (a 10 s timeout, possibly a whole XMODEM
 * transfer) that must not outlive the preview dialog that started it: once the dialog is dismissed
 * the fetch has to stop locally and at the peer, and it must not open its diff window afterwards.
 */
class SyncPreviewPreviewCancellationTest {

    private static SyncPreviewRow modifiedRow(String path) {
        return new SyncPreviewRow(SyncPreviewOperationType.MODIFIED, path, "1 B", 1L);
    }

    /** Waits for a condition the worker threads set, so a test never races its own assertions. */
    private static void await(String what, java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        for (int i = 0; i < 200 && !condition.getAsBoolean(); i++) {
            Thread.sleep(25);
        }
        assertTrue(condition.getAsBoolean(), what);
    }

    @Test
    @Timeout(30)
    void dismissingThePreviewStopsTheFetchAndThePeer() throws Exception {
        CountDownLatch fetchEntered = new CountDownLatch(1);
        CountDownLatch releaseFetch = new CountDownLatch(1);
        List<String> events = new CopyOnWriteArrayList<>();
        AtomicBoolean peerToldToStop = new AtomicBoolean();

        SyncPreviewRenderer renderer =
                new SyncPreviewRenderer(
                        null,
                        new SyncPreviewRenderer.ConflictResolver() {
                            @Override
                            public byte[] fetchRemoteContent(String path) {
                                throw new AssertionError(
                                        "The progress-aware overload must be used");
                            }

                            @Override
                            public byte[] fetchRemoteContent(
                                    String path, java.util.function.IntConsumer progress) {
                                events.add("fetch-started");
                                fetchEntered.countDown();
                                try {
                                    releaseFetch.await(10, TimeUnit.SECONDS);
                                } catch (InterruptedException e) {
                                    // What the worker's cancellation delivers.
                                    events.add("fetch-interrupted");
                                    Thread.currentThread().interrupt();
                                }
                                events.add("fetch-returned");
                                return "peer".getBytes();
                            }

                            @Override
                            public void cancelInFlightFetch() {
                                events.add("peer-told-to-stop");
                                peerToldToStop.set(true);
                            }
                        });

        SyncPreviewRow row = modifiedRow("m.txt");
        renderer.openChangePreview(row);
        assertTrue(fetchEntered.await(5, TimeUnit.SECONDS), "the fetch must actually start");

        // What the preview dialog's finally block does once the user cancels it.
        renderer.cancelInFlightPreviewFetches();
        releaseFetch.countDown();

        await("the worker must unwind once cancelled", () -> events.contains("fetch-returned"));
        await("the row's claim must be released", () -> !row.isPreviewInProgress());

        assertTrue(peerToldToStop.get(), "the peer must be told to stop streaming");
        assertTrue(
                events.indexOf("peer-told-to-stop") < events.indexOf("fetch-interrupted"),
                "the cancel signal has to leave while the worker is still inside its receive loop");
        assertFalse(
                row.isBaseFetched(),
                "a cancelled fetch must not cache the bytes or open the diff window");
    }

    @Test
    @Timeout(30)
    void theRowStaysClaimedForTheWholeRoundTrip() throws Exception {
        CountDownLatch fetchEntered = new CountDownLatch(1);
        CountDownLatch releaseFetch = new CountDownLatch(1);
        SyncPreviewRenderer renderer =
                new SyncPreviewRenderer(
                        null,
                        path -> {
                            fetchEntered.countDown();
                            try {
                                releaseFetch.await(10, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return null;
                        });

        SyncPreviewRow row = modifiedRow("m.txt");
        renderer.openChangePreview(row);
        assertTrue(fetchEntered.await(5, TimeUnit.SECONDS), "the fetch must start");
        assertTrue(row.isPreviewInProgress(), "the claim is held while the transfer runs");

        renderer.cancelInFlightPreviewFetches();
        releaseFetch.countDown();

        await(
                "the claim is released once the fetch is cancelled",
                () -> !row.isPreviewInProgress());
    }

    @Test
    void cancellingWithNoFetchRunningIsANoOp() {
        // The dialog's finally block runs on every exit route, including the ones where no row was
        // ever previewed, and the renderer is built without a resolver in several tests.
        new SyncPreviewRenderer(null).cancelInFlightPreviewFetches();
    }

    @Test
    void aResolverThatCannotCancelStillFetches() {
        SyncPreviewRow row = modifiedRow("m.txt");
        SyncPreviewRenderer renderer = new SyncPreviewRenderer(null, path -> "peer".getBytes());

        byte[] fetched = renderer.fetchBaseContent(row);

        assertEquals("peer", new String(fetched));
    }
}
