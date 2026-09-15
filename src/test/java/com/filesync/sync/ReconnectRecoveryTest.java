package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.filesync.protocol.BatchTransferSession;
import com.filesync.protocol.SyncProtocol;
import com.filesync.protocol.TransferCancelledException;
import com.filesync.serial.SerialPortManager;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReconnectRecoveryTest {

    @TempDir Path tempDir;

    // Coordinator constructions persist disk caches; keep them in the temp dir
    // instead of the user's ~/.filesync.
    @BeforeEach
    void redirectDiskCaches() {
        CacheLocations.setOverrideForTest(tempDir.resolve("cache-dir").toFile());
    }

    @AfterEach
    void restoreDiskCaches() {
        CacheLocations.clearOverrideForTest();
    }

    /** Shared for coordinator constructions; these tests never trigger pending writes. */
    private final PendingFileWriteService pendingWriteService =
            new PendingFileWriteService(new SimpleSyncEventBus());

    /**
     * Shut a coordinator executor down and wait for its workers to actually exit. {@link
     * ScheduledExecutorService#shutdownNow} returns immediately, and a worker still flushing a
     * cache when the {@code @TempDir} extension cleans up makes that deletion fail on Windows
     * ("Failed to close extension context") — the intermittent error this class used to throw.
     */
    private static void shutdownExecutor(ScheduledExecutorService executor) {
        executor.shutdownNow();
        try {
            // Best effort: the blocked waits are interruptible latches, so workers exit promptly.
            executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void connectionServiceTransitionsCallbacksOnlyOncePerStateChange() {
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicBoolean connectionAlive = new AtomicBoolean(true);
        AtomicInteger lostCallbacks = new AtomicInteger();
        AtomicInteger reconnectCallbacks = new AtomicInteger();

        List<Boolean> connectionStates = new ArrayList<>();
        SimpleSyncEventBus eventBus = new SimpleSyncEventBus();
        eventBus.register(
                event -> {
                    if (event instanceof SyncEvent.ConnectionEvent connectionEvent) {
                        connectionStates.add(connectionEvent.isConnected());
                    }
                });

        ConnectionService service =
                new ConnectionService(
                        new StubSerialPortManager(true),
                        new NoOpSyncProtocol(),
                        eventBus,
                        running,
                        connectionAlive,
                        () -> false,
                        () -> false,
                        lostCallbacks::incrementAndGet,
                        reconnectCallbacks::incrementAndGet);

        service.reportCommunicationFailure("first drop");
        service.reportCommunicationFailure("duplicate drop");
        service.handleHeartbeatAck();
        service.handleHeartbeatAck();
        service.reportCommunicationFailure("second drop");

        assertFalse(service.isConnectionAlive(), "Connection should be marked as lost");
        assertEquals(
                2,
                lostCallbacks.get(),
                "Lost callback should fire once per true->false transition");
        assertEquals(
                1,
                reconnectCallbacks.get(),
                "Reconnect callback should fire once per false->true transition");
        assertEquals(
                List.of(false, true, false),
                connectionStates,
                "Connection events should reflect state transitions");
    }

    @Test
    void startSyncBlockedUntilRoleNegotiationCompletes() throws IOException {
        Files.writeString(tempDir.resolve("test.txt"), "payload");

        AtomicBoolean syncing = new AtomicBoolean(false);
        List<String> errors = new ArrayList<>();
        SimpleSyncEventBus eventBus = new SimpleSyncEventBus();
        eventBus.register(
                event -> {
                    if (event instanceof SyncEvent.ErrorEvent errorEvent) {
                        errors.add(errorEvent.getMessage());
                    }
                });

        SyncCoordinator coordinator =
                new SyncCoordinator(
                        new NoOpSyncProtocol(),
                        eventBus,
                        () -> tempDir.toFile(),
                        () -> false,
                        () -> false,
                        () -> false,
                        () -> true,
                        () -> true,
                        () -> false,
                        pendingWriteService,
                        syncing,
                        () -> {},
                        () -> {},
                        () -> {});

        coordinator.startSync();

        assertFalse(syncing.get(), "Sync should not start before role negotiation");
        assertTrue(
                errors.stream().anyMatch(msg -> msg.contains("role negotiation")),
                "Expected a role negotiation error");
    }

    @Test
    void cancelOngoingSyncAllowsNextSyncAttemptWithoutRestart() throws Exception {
        Files.writeString(tempDir.resolve("test.txt"), "payload");

        AtomicBoolean syncing = new AtomicBoolean(false);
        BlockingSyncProtocol protocol = new BlockingSyncProtocol();
        SimpleSyncEventBus eventBus = new SimpleSyncEventBus();
        AtomicInteger syncStartedCount = new AtomicInteger();
        List<String> errors = new ArrayList<>();
        eventBus.register(
                event -> {
                    if (event instanceof SyncEvent.SyncStartedEvent) {
                        syncStartedCount.incrementAndGet();
                    } else if (event instanceof SyncEvent.ErrorEvent errorEvent) {
                        errors.add(errorEvent.getMessage());
                    }
                });

        SyncCoordinator coordinator = newBlockingSyncCoordinator(protocol, eventBus, syncing);

        ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
        coordinator.setExecutor(executor);
        try {
            protocol.setBlockAtManifestWait(true);
            coordinator.startSync();
            assertTrue(
                    protocol.awaitFirstWaitEntered(Duration.ofSeconds(2)),
                    "First sync should reach protocol wait stage");

            // Cancel clears the syncing flag without sending signals
            coordinator.cancelOngoingSync();
            assertFalse(coordinator.isSyncing(), "Syncing flag should be cleared after cancel");
            protocol.releaseBlockedWait();
            waitUntil(() -> !coordinator.isSyncing(), Duration.ofSeconds(2));

            // A second sync can start because cancel cleared the syncing state
            protocol.setBlockAtManifestWait(false);
            coordinator.startSync();
            waitUntil(() -> syncStartedCount.get() >= 2, Duration.ofSeconds(2));
            waitUntil(() -> !coordinator.isSyncing(), Duration.ofSeconds(2));

            assertTrue(
                    syncStartedCount.get() >= 2,
                    "A second sync attempt should be able to run after cancellation");
            assertTrue(
                    errors.stream().noneMatch(msg -> msg.contains("Sync already in progress")),
                    "Second sync should not be rejected as already in progress");
        } finally {
            shutdownExecutor(executor);
        }
    }

    @Test
    void restartAfterCancelWaitsForSupersededWorkerToExit() throws Exception {
        Files.writeString(tempDir.resolve("test.txt"), "payload");

        AtomicBoolean syncing = new AtomicBoolean(false);
        BlockingSyncProtocol protocol = new BlockingSyncProtocol();
        SimpleSyncEventBus eventBus = new SimpleSyncEventBus();
        AtomicInteger syncStartedCount = new AtomicInteger();
        List<String> errors = new ArrayList<>();
        eventBus.register(
                event -> {
                    if (event instanceof SyncEvent.SyncStartedEvent) {
                        syncStartedCount.incrementAndGet();
                    } else if (event instanceof SyncEvent.ErrorEvent errorEvent) {
                        errors.add(errorEvent.getMessage());
                    }
                });

        SyncCoordinator coordinator = newBlockingSyncCoordinator(protocol, eventBus, syncing);

        ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
        coordinator.setExecutor(executor);
        try {
            protocol.setBlockAtManifestWait(true);
            coordinator.startSync();
            assertTrue(
                    protocol.awaitFirstWaitEntered(Duration.ofSeconds(2)),
                    "First sync should reach the blocked manifest wait");

            // Cancel without interrupting models a worker stuck in an uninterruptible stage
            // (hashing, compression): it stays parked on the manifest wait. The restarted sync
            // must not let its worker touch the protocol until this superseded worker exits.
            coordinator.cancelOngoingSync();
            coordinator.startSync();
            Thread.sleep(400);
            assertEquals(
                    1,
                    syncStartedCount.get(),
                    "Restarted sync must not enter performSync while the first worker lives");
            assertEquals(
                    1,
                    protocol.manifestWaitEntries(),
                    "Restarted sync must not touch the protocol while the first worker lives");

            // Releasing the first worker lets it unwind through its cancellation path; only
            // then may the restart's worker proceed.
            protocol.releaseBlockedWait();
            waitUntil(() -> syncStartedCount.get() >= 2, Duration.ofSeconds(2));
            waitUntil(() -> !coordinator.isSyncing(), Duration.ofSeconds(5));
            assertEquals(
                    2,
                    protocol.manifestWaitEntries(),
                    "Restarted sync should reach the manifest wait after the first worker exited");
            assertTrue(
                    errors.stream().noneMatch(msg -> msg.contains("Sync already in progress")),
                    "Second sync should not be rejected as already in progress");
        } finally {
            shutdownExecutor(executor);
        }
    }

    @Test
    void cancellingTheWaitingRestartDoesNotDuplicateTheCancelNotice() throws Exception {
        Files.writeString(tempDir.resolve("test.txt"), "payload");

        AtomicBoolean syncing = new AtomicBoolean(false);
        BlockingSyncProtocol protocol = new BlockingSyncProtocol();
        SimpleSyncEventBus eventBus = new SimpleSyncEventBus();
        AtomicInteger cancelledEventCount = new AtomicInteger();
        List<String> logs = new ArrayList<>();
        eventBus.register(
                event -> {
                    if (event instanceof SyncEvent.SyncCancelledEvent) {
                        cancelledEventCount.incrementAndGet();
                    } else if (event instanceof SyncEvent.LogEvent logEvent) {
                        synchronized (logs) {
                            logs.add(logEvent.getMessage());
                        }
                    }
                });

        SyncCoordinator coordinator = newBlockingSyncCoordinator(protocol, eventBus, syncing);

        ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
        coordinator.setExecutor(executor);
        try {
            protocol.setBlockAtManifestWait(true);
            coordinator.startSync();
            assertTrue(
                    protocol.awaitFirstWaitEntered(Duration.ofSeconds(2)),
                    "First sync should reach the blocked manifest wait");

            // Cancel the first sync and restart after it; the restarted worker parks on the
            // still-blocked predecessor. Cancelling the restart too (cancel + interrupt, as
            // FileSyncManager.cancelSync does) is where a duplicate cancellation notice used to
            // be posted: the superseded worker reports its own cancellation when it unwinds.
            coordinator.cancelOngoingSync();
            coordinator.startSync();
            Thread.sleep(400); // let the restarted worker reach the await on the first worker
            coordinator.cancelOngoingSync();
            coordinator.interruptOngoingSync();
            protocol.releaseBlockedWait();

            waitUntil(() -> !coordinator.isSyncing(), Duration.ofSeconds(5));
            waitUntil(() -> cancelledEventCount.get() >= 1, Duration.ofSeconds(2));

            assertEquals(
                    1,
                    cancelledEventCount.get(),
                    "The superseded worker reports the cancellation; the waiting restart must not repeat it");
            synchronized (logs) {
                assertEquals(
                        1,
                        logs.stream().filter("Sync cancelled"::equals).count(),
                        "Expected exactly one 'Sync cancelled' log line, got: " + logs);
            }
        } finally {
            shutdownExecutor(executor);
        }
    }

    @Test
    void cancelOngoingSyncClearsStateWithoutPostingError() throws Exception {
        Files.writeString(tempDir.resolve("test.txt"), "payload");

        AtomicBoolean syncing = new AtomicBoolean(false);
        List<String> logs = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        SimpleSyncEventBus eventBus = new SimpleSyncEventBus();
        eventBus.register(
                event -> {
                    if (event instanceof SyncEvent.LogEvent logEvent) {
                        logs.add(logEvent.getMessage());
                    } else if (event instanceof SyncEvent.ErrorEvent errorEvent) {
                        errors.add(errorEvent.getMessage());
                    }
                });

        BlockingSyncProtocol protocol = new BlockingSyncProtocol();
        SyncCoordinator coordinator = newBlockingSyncCoordinator(protocol, eventBus, syncing);

        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
        coordinator.setExecutor(executor);
        try {
            protocol.setBlockAtManifestWait(true);
            coordinator.startSync();
            assertTrue(
                    protocol.awaitFirstWaitEntered(Duration.ofSeconds(2)),
                    "Sync should reach manifest wait while starting");

            coordinator.cancelOngoingSync();
            waitUntil(() -> !syncing.get(), Duration.ofSeconds(2));

            assertFalse(syncing.get(), "Syncing flag should be cleared after cancel");
            assertTrue(
                    errors.stream().noneMatch(msg -> msg.contains("Sync failed")),
                    "Cancel should not emit an error event");
        } finally {
            shutdownExecutor(executor);
        }
    }

    @Test
    void senderCancellationClearsSyncingFlagWithoutSignals() {
        AtomicBoolean syncing = new AtomicBoolean(true);
        CancelSignalProtocol transportCancellationProtocol = new CancelSignalProtocol(false);
        SyncCoordinator coordinator =
                new SyncCoordinator(
                        transportCancellationProtocol,
                        new SimpleSyncEventBus(),
                        () -> tempDir.toFile(),
                        () -> false,
                        () -> false,
                        () -> false,
                        () -> true,
                        () -> true,
                        () -> true,
                        pendingWriteService,
                        syncing,
                        () -> {},
                        () -> {},
                        () -> {});

        coordinator.cancelOngoingSync();

        assertFalse(
                transportCancellationProtocol.cancelCommandSent.get(),
                "Cancel should not send a cancel command");
        assertFalse(
                transportCancellationProtocol.transferCancelSent.get(),
                "Cancel should not send an XMODEM cancel");
        assertFalse(syncing.get(), "Cancelling should clear syncing flag");
    }

    @Test
    void interruptOngoingSyncUnblocksWorkerAsBenignCancellation() throws Exception {
        Files.writeString(tempDir.resolve("test.txt"), "payload");

        AtomicBoolean syncing = new AtomicBoolean(false);
        BlockingSyncProtocol protocol = new BlockingSyncProtocol();
        SimpleSyncEventBus eventBus = new SimpleSyncEventBus();
        List<String> logs = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        AtomicInteger cancelEvents = new AtomicInteger();
        eventBus.register(
                event -> {
                    if (event instanceof SyncEvent.LogEvent logEvent) {
                        logs.add(logEvent.getMessage());
                    } else if (event instanceof SyncEvent.ErrorEvent errorEvent) {
                        errors.add(errorEvent.getMessage());
                    } else if (event instanceof SyncEvent.SyncCancelledEvent) {
                        cancelEvents.incrementAndGet();
                    }
                });

        SyncCoordinator coordinator = newBlockingSyncCoordinator(protocol, eventBus, syncing);
        coordinator.setCommunicationFailureReporter(failures::add);

        ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
        coordinator.setExecutor(executor);
        try {
            protocol.setBlockAtManifestWait(true);
            coordinator.startSync();
            assertTrue(
                    protocol.awaitFirstWaitEntered(Duration.ofSeconds(2)),
                    "Sync should reach the blocked manifest wait");

            // The user's cancel: stop the worker by interrupt, keeping the link state intact.
            coordinator.cancelOngoingSync();
            coordinator.interruptOngoingSync();

            waitUntil(() -> cancelEvents.get() >= 1, Duration.ofSeconds(2));

            assertTrue(errors.isEmpty(), "A local cancel must not surface as an error");
            assertTrue(failures.isEmpty(), "A local cancel must not report a link failure");
            assertEquals(1, cancelEvents.get(), "Exactly one cancellation event should be posted");
            assertEquals(
                    1,
                    logs.stream().filter("Sync cancelled"::equals).count(),
                    "Cancellation should be logged exactly once");
            assertFalse(syncing.get(), "Syncing flag should be cleared after cancel");
        } finally {
            shutdownExecutor(executor);
        }
    }

    @Test
    void peerCancelledTransferAbortsSyncWithoutErrorOrFallback() throws Exception {
        File dir = tempDir.resolve("input").toFile();
        dir.mkdirs();
        Files.writeString(dir.toPath().resolve("file_0.txt"), "content 0");

        AtomicBoolean syncing = new AtomicBoolean(false);
        PeerCancellingProtocol protocol = new PeerCancellingProtocol();
        SimpleSyncEventBus eventBus = new SimpleSyncEventBus();
        List<String> logs = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        AtomicInteger cancelEvents = new AtomicInteger();
        eventBus.register(
                event -> {
                    if (event instanceof SyncEvent.LogEvent logEvent) {
                        logs.add(logEvent.getMessage());
                    } else if (event instanceof SyncEvent.ErrorEvent errorEvent) {
                        errors.add(errorEvent.getMessage());
                    } else if (event instanceof SyncEvent.SyncCancelledEvent) {
                        cancelEvents.incrementAndGet();
                    }
                });

        SyncCoordinator coordinator =
                new SyncCoordinator(
                        protocol,
                        eventBus,
                        () -> dir,
                        () -> false,
                        () -> false,
                        () -> false,
                        () -> true,
                        () -> true,
                        () -> true,
                        pendingWriteService,
                        syncing,
                        () -> {},
                        () -> {},
                        () -> {});
        coordinator.setCommunicationFailureReporter(failures::add);

        SyncPreviewPlan plan =
                new SyncPreviewPlan(
                        List.of(new FileChangeDetector.FileInfo("file_0.txt", 9L, 0L, "md5")),
                        List.of(),
                        List.of(),
                        List.of(),
                        9L,
                        false);

        coordinator.startSyncWithPlan(plan);
        waitUntil(() -> !coordinator.isSyncing(), Duration.ofSeconds(5));

        assertTrue(errors.isEmpty(), "A peer cancel must not surface as an error");
        assertTrue(failures.isEmpty(), "A peer cancel must not report a link failure");
        assertEquals(1, cancelEvents.get(), "Exactly one cancellation event should be posted");
        assertTrue(
                logs.stream().anyMatch("Sync cancelled by remote"::equals),
                "The peer cancel should be logged benignly");
        assertEquals(
                0,
                protocol.perFileSendCount.get(),
                "A file the peer cancelled must not be re-sent via the per-file fallback");
    }

    @Test
    void sharedTextResyncedOnReconnect() {
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicBoolean connectionAlive = new AtomicBoolean(false);
        AtomicBoolean syncing = new AtomicBoolean(false);
        AtomicBoolean transferBusy = new AtomicBoolean(false);

        List<String> sentTexts = new ArrayList<>();
        AtomicReference<String> receivedText = new AtomicReference<>();
        SimpleSyncEventBus eventBus = new SimpleSyncEventBus();
        eventBus.register(
                event -> {
                    if (event instanceof SyncEvent.SharedTextReceivedEvent sharedTextEvent) {
                        receivedText.set(sharedTextEvent.getText());
                    }
                });

        ResyncTestProtocol protocol = new ResyncTestProtocol(sentTexts);

        SharedTextService sharedTextService =
                new SharedTextService(
                        protocol,
                        eventBus,
                        running::get,
                        connectionAlive::get,
                        syncing::get,
                        transferBusy::get,
                        () -> true);

        // Simulate: text was previously queued while connected, connection lost, then restored
        connectionAlive.set(true);
        sharedTextService.queueSharedText("hello from peer A");
        assertTrue(sentTexts.contains("hello from peer A"), "Text should be sent when connected");

        // Connection drops
        connectionAlive.set(false);

        // New text queued while disconnected -- should stay pending
        sentTexts.clear();
        sharedTextService.queueSharedText("updated text after disconnect");

        // Reconnect
        connectionAlive.set(true);

        // requestSharedTextResync should queue and flush the latest text
        sharedTextService.queueSharedText("updated text after disconnect");
        sharedTextService.flushIfIdle();

        assertTrue(
                sentTexts.contains("updated text after disconnect"),
                "Pending shared text should be flushed on reconnect");
    }

    @Test
    void sharedTextResyncHeldBackDuringActiveTransfer() {
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicBoolean connectionAlive = new AtomicBoolean(true);
        AtomicBoolean syncing = new AtomicBoolean(false);
        AtomicBoolean transferBusy = new AtomicBoolean(true);

        List<String> sentTexts = new ArrayList<>();
        SimpleSyncEventBus eventBus = new SimpleSyncEventBus();

        ResyncTestProtocol protocol = new ResyncTestProtocol(sentTexts);

        SharedTextService sharedTextService =
                new SharedTextService(
                        protocol,
                        eventBus,
                        running::get,
                        connectionAlive::get,
                        syncing::get,
                        transferBusy::get,
                        () -> true);

        sharedTextService.queueSharedText("should not send yet");
        sharedTextService.flushIfIdle();

        assertTrue(sentTexts.isEmpty(), "Flush should be held back while transfer is busy");

        // Transfer completes
        transferBusy.set(false);
        sharedTextService.onSyncIdle();

        assertTrue(
                sentTexts.contains("should not send yet"),
                "Pending shared text should be sent once transfer completes");
    }

    @Test
    void sharedTextSentAtSyncBoundaryBeforeCompletion() throws InterruptedException {
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicBoolean connectionAlive = new AtomicBoolean(true);
        AtomicBoolean syncing = new AtomicBoolean(false);
        AtomicBoolean transferBusy = new AtomicBoolean(false);
        AtomicBoolean syncCompleted = new AtomicBoolean(false);

        SimpleSyncEventBus eventBus = new SimpleSyncEventBus();
        eventBus.register(
                event -> {
                    if (event instanceof SyncEvent.SyncCompleteEvent) {
                        syncCompleted.set(true);
                    }
                });

        BoundaryPrioritySyncProtocol protocol = new BoundaryPrioritySyncProtocol(syncing);
        SharedTextService sharedTextService =
                new SharedTextService(
                        protocol,
                        eventBus,
                        running::get,
                        connectionAlive::get,
                        syncing::get,
                        transferBusy::get,
                        () -> true);

        SyncCoordinator coordinator =
                new SyncCoordinator(
                        protocol,
                        eventBus,
                        () -> tempDir.toFile(),
                        () -> false,
                        () -> false,
                        () -> false,
                        connectionAlive::get,
                        () -> true,
                        () -> true,
                        pendingWriteService,
                        syncing,
                        sharedTextService::onSyncIdle,
                        sharedTextService::onSyncBoundary,
                        () -> {});

        SyncPreviewPlan longSyncPlan =
                new SyncPreviewPlan(
                        List.of(new FileChangeDetector.FileInfo("long.txt", 10L, 0L, "md5")),
                        List.of(),
                        List.of(),
                        List.of(),
                        10L,
                        false);

        new Thread(() -> coordinator.startSyncWithPlan(longSyncPlan), "sync-boundary-test").start();

        waitUntil(protocol::isSendFileStarted, Duration.ofSeconds(2));
        sharedTextService.queueSharedText("shared while syncing");
        protocol.allowFileSendToContinue();

        waitUntil(protocol::wasSharedTextSent, Duration.ofSeconds(2));
        assertEquals(
                "shared while syncing",
                protocol.getLastSharedText(),
                "Shared text should be sent when queued during an active sync operation");
        assertTrue(
                protocol.wasSharedTextSentWhileSyncing(),
                "Shared text should be sent before sync completes");
        waitUntil(syncCompleted::get, Duration.ofSeconds(2));
    }

    @Test
    void startSyncWithPlanUsesProvidedPlanWithoutManifestRoundtrip()
            throws IOException, InterruptedException {
        Files.writeString(tempDir.resolve("test.txt"), "payload");

        AtomicBoolean syncing = new AtomicBoolean(false);
        AtomicBoolean requestManifestCalled = new AtomicBoolean(false);
        SimpleSyncEventBus eventBus = new SimpleSyncEventBus();

        SyncProtocol protocol =
                new NoOpSyncProtocol() {
                    @Override
                    public void requestManifest(boolean respectGitignore, boolean fastMode) {
                        requestManifestCalled.set(true);
                        throw new RuntimeException(
                                "Should not call requestManifest when plan is provided");
                    }
                };

        SyncCoordinator coordinator =
                new SyncCoordinator(
                        protocol,
                        eventBus,
                        () -> tempDir.toFile(),
                        () -> false,
                        () -> false,
                        () -> false,
                        () -> true,
                        () -> true,
                        () -> true,
                        pendingWriteService,
                        syncing,
                        () -> {},
                        () -> {},
                        () -> {});

        SyncPreviewPlan plan =
                new SyncPreviewPlan(List.of(), List.of(), List.of(), List.of(), 0L, false);

        coordinator.startSyncWithPlan(plan);
        waitUntil(() -> !coordinator.isSyncing(), Duration.ofSeconds(2));

        assertFalse(
                requestManifestCalled.get(),
                "requestManifest should not be called when using provided plan");
    }

    @Test
    void sendFileFailurePreservesRealErrorDetail() throws IOException {
        File testFile = tempDir.resolve("test.txt").toFile();
        Files.writeString(testFile.toPath(), "content");

        SyncProtocol protocol = new AckTimeoutProtocol();

        IOException thrown =
                org.junit.jupiter.api.Assertions.assertThrows(
                        IOException.class, () -> protocol.sendFile(tempDir.toFile(), "test.txt"));

        assertTrue(
                thrown.getMessage().contains("Timeout waiting for command"),
                "Error message should preserve ACK timeout detail, not 'unknown XMODEM error'");
    }

    private static void waitUntil(BooleanSupplier condition, Duration timeout)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Timed out waiting for condition");
    }

    /**
     * Builds the coordinator shared by the worker-lifecycle tests: a blocking protocol over the
     * temp folder with sender defaults, no-op idle/boundary/heartbeat callbacks, and no executor
     * attached (each test sets up its own so it can shut it down).
     */
    private SyncCoordinator newBlockingSyncCoordinator(
            BlockingSyncProtocol protocol, SimpleSyncEventBus eventBus, AtomicBoolean syncing) {
        return new SyncCoordinator(
                protocol,
                eventBus,
                () -> tempDir.toFile(),
                () -> false,
                () -> false,
                () -> false,
                () -> true,
                () -> true,
                () -> true,
                pendingWriteService,
                syncing,
                () -> {},
                () -> {},
                () -> {});
    }

    private static class StubSerialPortManager extends SerialPortManager {
        private final boolean open;

        StubSerialPortManager(boolean open) {
            this.open = open;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void clearInputBuffer() {
            // No-op for tests that do not use real serial I/O
        }
    }

    private static class NoOpSyncProtocol extends SyncProtocol {
        NoOpSyncProtocol() {
            super(new StubSerialPortManager(true));
        }

        @Override
        public void sendHeartbeatAck() {
            // No-op for state-machine tests.
        }

        @Override
        public void requestManifest(boolean respectGitignore, boolean fastMode) {
            // No-op for sync-coordinator tests that should fail before protocol use.
        }
    }

    private static final class BlockingSyncProtocol extends SyncProtocol {
        private final CountDownLatch firstWaitEntered = new CountDownLatch(1);
        private final CountDownLatch releaseWait = new CountDownLatch(1);
        private final AtomicInteger manifestWaitEntries = new AtomicInteger();
        private volatile boolean blockAtManifestWait;

        BlockingSyncProtocol() {
            super(new StubSerialPortManager(true));
        }

        void setBlockAtManifestWait(boolean blockAtManifestWait) {
            this.blockAtManifestWait = blockAtManifestWait;
        }

        boolean awaitFirstWaitEntered(Duration timeout) throws InterruptedException {
            return firstWaitEntered.await(
                    timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        }

        int manifestWaitEntries() {
            return manifestWaitEntries.get();
        }

        void releaseBlockedWait() {
            releaseWait.countDown();
        }

        @Override
        public void requestManifest(boolean respectGitignore, boolean fastMode) {
            // No-op
        }

        @Override
        public Message waitForCommand(String expectedCommand) throws IOException {
            if (SyncProtocol.CMD_MANIFEST_DATA.equals(expectedCommand)) {
                // Count every entry: a restarted sync must not reach this point while the
                // superseded worker is still alive.
                manifestWaitEntries.incrementAndGet();
                if (blockAtManifestWait) {
                    firstWaitEntered.countDown();
                    try {
                        releaseWait.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException(
                                "Interrupted while simulating blocked manifest wait", e);
                    }
                }
            }
            return new Message(expectedCommand, new String[0]);
        }

        @Override
        public Message waitForCommand(String expectedCommand, long maxIdleMs) throws IOException {
            // The sender's manifest wait goes through the idle-bounded overload; route it back so
            // the blocking simulation covers both entry points.
            return waitForCommand(expectedCommand);
        }

        @Override
        public void sendAck() {
            // No-op
        }

        @Override
        public FileChangeDetector.FileManifest receiveManifest() {
            return new FileChangeDetector.FileManifest();
        }

        @Override
        public boolean sendFile(File baseDir, String relativePath) throws IOException {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("Interrupted before completing simulated send");
            }
            return false;
        }

        @Override
        public boolean sendBatch(
                List<Object[]> files,
                int maxBatchSizeBytes,
                BatchTransferSession.BatchProgressCallback batchProgressCallback,
                File baseDirForReceive) {
            // Simulate a successful batch: syncs completing in these tests need no real serial
            // I/O, and a real sendBatch here would fail against the stubbed port.
            return true;
        }

        @Override
        public void sendMkdir(String relativePath) {
            // No-op
        }

        @Override
        public void sendFileDelete(String relativePath) {
            // No-op
        }

        @Override
        public void sendRmdir(String relativePath) {
            // No-op
        }

        @Override
        public void sendSyncComplete() {
            // No-op
        }
    }

    private static final class BoundaryPrioritySyncProtocol extends SyncProtocol {
        private final AtomicBoolean sendFileStarted = new AtomicBoolean(false);
        private final AtomicBoolean syncingState;
        private final AtomicBoolean sharedTextSent = new AtomicBoolean(false);
        private final AtomicBoolean sharedTextSentWhileSyncing = new AtomicBoolean(false);
        private final java.util.concurrent.CountDownLatch continueLatch =
                new java.util.concurrent.CountDownLatch(1);
        private volatile String lastSharedText;

        BoundaryPrioritySyncProtocol(AtomicBoolean syncingState) {
            super(new StubSerialPortManager(true));
            this.syncingState = syncingState;
        }

        void allowFileSendToContinue() {
            continueLatch.countDown();
        }

        boolean isSendFileStarted() {
            return sendFileStarted.get();
        }

        boolean wasSharedTextSent() {
            return sharedTextSent.get();
        }

        boolean wasSharedTextSentWhileSyncing() {
            return sharedTextSentWhileSyncing.get();
        }

        String getLastSharedText() {
            return lastSharedText;
        }

        @Override
        public boolean sendFile(File baseDir, String relativePath) throws IOException {
            sendFileStarted.set(true);
            try {
                continueLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while simulating long file send", e);
            }
            return true;
        }

        @Override
        public void sendSharedText(long timestamp, String text) {
            lastSharedText = text;
            sharedTextSent.set(true);
            sharedTextSentWhileSyncing.set(syncingState.get());
        }

        @Override
        public void sendSharedText(String text) {
            sendSharedText(System.currentTimeMillis(), text);
        }

        @Override
        public void sendMkdir(String relativePath) {
            // No-op
        }

        @Override
        public void sendFileDelete(String relativePath) {
            // No-op
        }

        @Override
        public boolean sendBatch(
                List<Object[]> files,
                int maxBatchSizeBytes,
                com.filesync.protocol.BatchTransferSession.BatchProgressCallback callback,
                File baseDir)
                throws IOException {
            // No-op: batch not supported in this stub
            return false;
        }

        @Override
        public void sendRmdir(String relativePath) {
            // No-op
        }

        @Override
        public void sendSyncComplete() {
            // No-op
        }
    }

    private static class ResyncTestProtocol extends SyncProtocol {
        private final List<String> sentTexts;

        ResyncTestProtocol(List<String> sentTexts) {
            super(new StubSerialPortManager(true));
            this.sentTexts = sentTexts;
        }

        @Override
        public void sendSharedText(String text) throws IOException {
            sendSharedText(System.currentTimeMillis(), text);
        }

        @Override
        public void sendSharedText(long timestamp, String text) {
            sentTexts.add(text);
        }

        @Override
        public String decodeSharedText(String encodedPayload) {
            return encodedPayload;
        }
    }

    private static class AckTimeoutProtocol extends SyncProtocol {
        AckTimeoutProtocol() {
            super(new StubSerialPortManager(true));
        }

        @Override
        public void sendCommand(String command, String... params) {
            // No-op to avoid serial port write
        }

        @Override
        public Message waitForCommand(String expectedCommand) throws IOException {
            throw new IOException("Timeout waiting for command: " + expectedCommand);
        }
    }

    private static final class CancelSignalProtocol extends SyncProtocol {
        private final AtomicBoolean xmodemInProgress;
        private final AtomicBoolean cancelCommandSent = new AtomicBoolean();
        private final AtomicBoolean transferCancelSent = new AtomicBoolean();

        CancelSignalProtocol(boolean xmodemInProgress) {
            super(new StubSerialPortManager(true));
            this.xmodemInProgress = new AtomicBoolean(xmodemInProgress);
        }

        @Override
        public boolean isXmodemInProgress() {
            return xmodemInProgress.get();
        }

        @Override
        public void sendCancelCommand() {
            cancelCommandSent.set(true);
        }

        @Override
        public void sendTransferCancel() {
            transferCancelSent.set(true);
        }
    }

    /** Simulates the peer aborting the batch mid-transfer with an XMODEM CAN signal. */
    private static final class PeerCancellingProtocol extends SyncProtocol {
        private final AtomicInteger perFileSendCount = new AtomicInteger();

        PeerCancellingProtocol() {
            super(new StubSerialPortManager(true));
        }

        @Override
        public boolean sendBatch(
                List<Object[]> files,
                int maxBatchSizeBytes,
                BatchTransferSession.BatchProgressCallback callback,
                File baseDir)
                throws IOException {
            throw new TransferCancelledException("Batch transfer cancelled by remote");
        }

        @Override
        public boolean sendFile(File baseDir, String relativePath) throws IOException {
            perFileSendCount.incrementAndGet();
            return true;
        }

        @Override
        public void sendMkdir(String relativePath) {}

        @Override
        public void sendFileDelete(String relativePath) {}

        @Override
        public void sendRmdir(String relativePath) {}

        @Override
        public void sendSyncComplete() {}

        @Override
        public void sendAck() {}
    }

    // ----- Batch Transfer Tests -----

    @Test
    void batchTransferSendsRegularFilesInBatches() throws Exception {
        File dir = tempDir.resolve("input").toFile();
        dir.mkdirs();

        for (int i = 0; i < 5; i++) {
            Files.writeString(dir.toPath().resolve("file_" + i + ".txt"), "content " + i);
        }

        AtomicBoolean syncing = new AtomicBoolean(false);
        List<String> batchedPaths = new ArrayList<>();
        BatchCaptureProtocol protocol = new BatchCaptureProtocol(batchedPaths);
        SimpleSyncEventBus eventBus = new SimpleSyncEventBus();

        SyncCoordinator coordinator =
                new SyncCoordinator(
                        protocol,
                        eventBus,
                        () -> dir,
                        () -> false,
                        () -> false,
                        () -> false,
                        () -> true,
                        () -> true,
                        () -> true,
                        pendingWriteService,
                        syncing,
                        () -> {},
                        () -> {},
                        () -> {});

        SyncPreviewPlan plan =
                new SyncPreviewPlan(
                        List.of(
                                new FileChangeDetector.FileInfo("file_0.txt", 9L, 0L, "md5"),
                                new FileChangeDetector.FileInfo("file_1.txt", 9L, 0L, "md5"),
                                new FileChangeDetector.FileInfo("file_2.txt", 9L, 0L, "md5"),
                                new FileChangeDetector.FileInfo("file_3.txt", 9L, 0L, "md5"),
                                new FileChangeDetector.FileInfo("file_4.txt", 9L, 0L, "md5")),
                        List.of(),
                        List.of(),
                        List.of(),
                        45L,
                        false);

        coordinator.startSyncWithPlan(plan);
        waitUntil(() -> !coordinator.isSyncing(), Duration.ofSeconds(5));

        assertEquals(
                1,
                protocol.batchSendCount.get(),
                "Should send exactly one batch for regular files");
        assertEquals(5, batchedPaths.size(), "Batch should contain all 5 files");
        assertTrue(protocol.sendBatchCalled.get(), "sendBatch should have been called");
        assertFalse(
                protocol.perFileFallbackUsed.get(),
                "Should not fall back to per-file when batch succeeds");
    }

    @Test
    void batchTransferFallsBackToPerFileOnFailure() throws Exception {
        File dir = tempDir.resolve("input").toFile();
        dir.mkdirs();

        for (int i = 0; i < 3; i++) {
            Files.writeString(dir.toPath().resolve("file_" + i + ".txt"), "content " + i);
        }

        AtomicBoolean syncing = new AtomicBoolean(false);
        List<String> individuallySentPaths = new ArrayList<>();
        FailingBatchProtocol protocol = new FailingBatchProtocol(individuallySentPaths);
        SimpleSyncEventBus eventBus = new SimpleSyncEventBus();

        SyncCoordinator coordinator =
                new SyncCoordinator(
                        protocol,
                        eventBus,
                        () -> dir,
                        () -> false,
                        () -> false,
                        () -> false,
                        () -> true,
                        () -> true,
                        () -> true,
                        pendingWriteService,
                        syncing,
                        () -> {},
                        () -> {},
                        () -> {});

        SyncPreviewPlan plan =
                new SyncPreviewPlan(
                        List.of(
                                new FileChangeDetector.FileInfo("file_0.txt", 9L, 0L, "md5"),
                                new FileChangeDetector.FileInfo("file_1.txt", 9L, 0L, "md5"),
                                new FileChangeDetector.FileInfo("file_2.txt", 9L, 0L, "md5")),
                        List.of(),
                        List.of(),
                        List.of(),
                        27L,
                        false);

        coordinator.startSyncWithPlan(plan);
        waitUntil(() -> !coordinator.isSyncing(), Duration.ofSeconds(5));

        assertTrue(protocol.batchFailed.get(), "Batch should have been attempted and failed");
        assertEquals(
                3,
                individuallySentPaths.size(),
                "All files should be sent individually as fallback");
        assertTrue(protocol.perFileFallbackUsed.get(), "Should have used per-file fallback");
    }

    // ----- Batch Transfer Protocol Stubs -----

    private static class BatchCaptureProtocol extends SyncProtocol {
        private final List<String> batchedPaths;
        private final AtomicBoolean sendBatchCalled = new AtomicBoolean(false);
        private final AtomicBoolean perFileFallbackUsed = new AtomicBoolean(false);
        private final AtomicInteger batchSendCount = new AtomicInteger(0);
        private volatile java.util.function.Consumer<String> individualSender = null;

        BatchCaptureProtocol(List<String> batchedPaths) {
            super(new StubSerialPortManager(true));
            this.batchedPaths = batchedPaths;
        }

        @Override
        public boolean sendBatch(
                List<Object[]> files,
                int maxBatchSizeBytes,
                BatchTransferSession.BatchProgressCallback callback,
                File baseDir)
                throws IOException {
            sendBatchCalled.set(true);
            batchSendCount.incrementAndGet();
            for (Object[] entry : files) {
                String path = (String) entry[1];
                batchedPaths.add(path);
            }
            return true;
        }

        @Override
        public boolean sendFile(File baseDir, String relativePath) throws IOException {
            if (individualSender != null) {
                individualSender.accept(relativePath);
            }
            perFileFallbackUsed.set(true);
            return true;
        }

        @Override
        public void sendMkdir(String relativePath) {}

        @Override
        public void sendFileDelete(String relativePath) {}

        @Override
        public void sendRmdir(String relativePath) {}

        @Override
        public void sendSyncComplete() {}

        @Override
        public void sendAck() {}
    }

    private static class FailingBatchProtocol extends SyncProtocol {
        private final List<String> individuallySentPaths;
        private final AtomicBoolean batchFailed = new AtomicBoolean(false);
        private final AtomicBoolean perFileFallbackUsed = new AtomicBoolean(false);

        FailingBatchProtocol(List<String> individuallySentPaths) {
            super(new StubSerialPortManager(true));
            this.individuallySentPaths = individuallySentPaths;
        }

        @Override
        public boolean sendBatch(
                List<Object[]> files,
                int maxBatchSizeBytes,
                BatchTransferSession.BatchProgressCallback callback,
                File baseDir)
                throws IOException {
            batchFailed.set(true);
            return false;
        }

        @Override
        public boolean sendFile(File baseDir, String relativePath) throws IOException {
            individuallySentPaths.add(relativePath);
            perFileFallbackUsed.set(true);
            return true;
        }

        @Override
        public void sendMkdir(String relativePath) {}

        @Override
        public void sendFileDelete(String relativePath) {}

        @Override
        public void sendRmdir(String relativePath) {}

        @Override
        public void sendSyncComplete() {}

        @Override
        public void sendAck() {}
    }
}
