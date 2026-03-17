package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.filesync.protocol.SyncProtocol;
import com.filesync.serial.SerialPortManager;

class ReconnectRecoveryTest {

    @TempDir
    Path tempDir;

    @Test
    void connectionServiceTransitionsCallbacksOnlyOncePerStateChange() {
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicBoolean connectionAlive = new AtomicBoolean(true);
        AtomicInteger lostCallbacks = new AtomicInteger();
        AtomicInteger reconnectCallbacks = new AtomicInteger();

        List<Boolean> connectionStates = new ArrayList<>();
        SimpleSyncEventBus eventBus = new SimpleSyncEventBus();
        eventBus.register(event -> {
            if (event instanceof SyncEvent.ConnectionEvent connectionEvent) {
                connectionStates.add(connectionEvent.isConnected());
            }
        });

        ConnectionService service = new ConnectionService(
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
        assertEquals(2, lostCallbacks.get(), "Lost callback should fire once per true->false transition");
        assertEquals(1, reconnectCallbacks.get(), "Reconnect callback should fire once per false->true transition");
        assertEquals(List.of(false, true, false), connectionStates, "Connection events should reflect state transitions");
    }

    @Test
    void startSyncBlockedUntilRoleNegotiationCompletes() throws IOException {
        Files.writeString(tempDir.resolve("test.txt"), "payload");

        AtomicBoolean syncing = new AtomicBoolean(false);
        List<String> errors = new ArrayList<>();
        SimpleSyncEventBus eventBus = new SimpleSyncEventBus();
        eventBus.register(event -> {
            if (event instanceof SyncEvent.ErrorEvent errorEvent) {
                errors.add(errorEvent.getMessage());
            }
        });

        SyncCoordinator coordinator = new SyncCoordinator(
                new NoOpSyncProtocol(),
                eventBus,
                () -> tempDir.toFile(),
                () -> false,
                () -> false,
                () -> false,
                () -> true,
                () -> true,
                () -> false,
                syncing,
                () -> {
                },
                () -> {
                },
                () -> {
                });

        coordinator.startSync();

        assertFalse(syncing.get(), "Sync should not start before role negotiation");
        assertTrue(errors.stream().anyMatch(msg -> msg.contains("role negotiation")),
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
        eventBus.register(event -> {
            if (event instanceof SyncEvent.SyncStartedEvent) {
                syncStartedCount.incrementAndGet();
            } else if (event instanceof SyncEvent.ErrorEvent errorEvent) {
                errors.add(errorEvent.getMessage());
            }
        });

        SyncCoordinator coordinator = new SyncCoordinator(
                protocol,
                eventBus,
                () -> tempDir.toFile(),
                () -> false,
                () -> false,
                () -> false,
                () -> true,
                () -> true,
                () -> true,
                syncing,
                () -> {
                },
                () -> {
                },
                () -> {
                });

        ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
        coordinator.setExecutor(executor);
        try {
            protocol.setBlockAtManifestWait(true);
            coordinator.startSync();
            assertTrue(protocol.awaitFirstWaitEntered(Duration.ofSeconds(2)),
                    "First sync should reach protocol wait stage");

            coordinator.cancelOngoingSync();
            protocol.releaseBlockedWait();
            waitUntil(() -> !coordinator.isSyncing(), Duration.ofSeconds(2));

            protocol.setBlockAtManifestWait(false);
            coordinator.startSync();
            waitUntil(() -> syncStartedCount.get() >= 2, Duration.ofSeconds(2));
            waitUntil(() -> !coordinator.isSyncing(), Duration.ofSeconds(2));

            assertTrue(syncStartedCount.get() >= 2,
                    "A second sync attempt should be able to run after cancellation");
            assertTrue(errors.stream().noneMatch(msg -> msg.contains("Sync already in progress")),
                    "Second sync should not be rejected as already in progress");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void directionChangeMarksRoleNegotiated() {
        AtomicBoolean isSender = new AtomicBoolean(true);
        AtomicBoolean roleNegotiated = new AtomicBoolean(false);
        List<Boolean> directionStates = new ArrayList<>();
        SimpleSyncEventBus eventBus = new SimpleSyncEventBus();
        eventBus.register(event -> {
            if (event instanceof SyncEvent.DirectionEvent directionEvent) {
                directionStates.add(directionEvent.isSender());
            }
        });

        RoleNegotiationService service = new RoleNegotiationService(
                new NoOpSyncProtocol(),
                eventBus,
                isSender,
                roleNegotiated,
                () -> true);

        service.handleDirectionChange(true);

        assertFalse(service.isSender(), "Remote sender announcement should switch this side to receiver");
        assertTrue(service.isRoleNegotiated(), "Direction changes should finalize the local role");
        assertEquals(List.of(false), directionStates, "Direction change event should reflect the new local role");
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
        eventBus.register(event -> {
            if (event instanceof SyncEvent.SharedTextReceivedEvent sharedTextEvent) {
                receivedText.set(sharedTextEvent.getText());
            }
        });

        ResyncTestProtocol protocol = new ResyncTestProtocol(sentTexts);

        SharedTextService sharedTextService = new SharedTextService(
                protocol,
                eventBus,
                running::get,
                connectionAlive::get,
                syncing::get,
                transferBusy::get);

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

        assertTrue(sentTexts.contains("updated text after disconnect"),
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

        SharedTextService sharedTextService = new SharedTextService(
                protocol,
                eventBus,
                running::get,
                connectionAlive::get,
                syncing::get,
                transferBusy::get);

        sharedTextService.queueSharedText("should not send yet");
        sharedTextService.flushIfIdle();

        assertTrue(sentTexts.isEmpty(),
                "Flush should be held back while transfer is busy");

        // Transfer completes
        transferBusy.set(false);
        sharedTextService.onSyncIdle();

        assertTrue(sentTexts.contains("should not send yet"),
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
        eventBus.register(event -> {
            if (event instanceof SyncEvent.SyncCompleteEvent) {
                syncCompleted.set(true);
            }
        });

        BoundaryPrioritySyncProtocol protocol = new BoundaryPrioritySyncProtocol(syncing);
        SharedTextService sharedTextService = new SharedTextService(
                protocol,
                eventBus,
                running::get,
                connectionAlive::get,
                syncing::get,
                transferBusy::get);

        SyncCoordinator coordinator = new SyncCoordinator(
                protocol,
                eventBus,
                () -> tempDir.toFile(),
                () -> false,
                () -> false,
                () -> false,
                connectionAlive::get,
                () -> true,
                () -> true,
                syncing,
                sharedTextService::onSyncIdle,
                sharedTextService::onSyncBoundary,
                () -> {
                });

        SyncPreviewPlan longSyncPlan = new SyncPreviewPlan(
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
        assertEquals("shared while syncing", protocol.getLastSharedText(),
                "Shared text should be sent when queued during an active sync operation");
        assertTrue(protocol.wasSharedTextSentWhileSyncing(),
                "Shared text should be sent before sync completes");
        waitUntil(syncCompleted::get, Duration.ofSeconds(2));
    }

    @Test
    void startSyncWithPlanUsesProvidedPlanWithoutManifestRoundtrip() throws IOException, InterruptedException {
        Files.writeString(tempDir.resolve("test.txt"), "payload");

        AtomicBoolean syncing = new AtomicBoolean(false);
        AtomicBoolean requestManifestCalled = new AtomicBoolean(false);
        SimpleSyncEventBus eventBus = new SimpleSyncEventBus();

        SyncProtocol protocol = new NoOpSyncProtocol() {
            @Override
            public void requestManifest(boolean respectGitignore, boolean fastMode) {
                requestManifestCalled.set(true);
                throw new RuntimeException("Should not call requestManifest when plan is provided");
            }
        };

        SyncCoordinator coordinator = new SyncCoordinator(
                protocol,
                eventBus,
                () -> tempDir.toFile(),
                () -> false,
                () -> false,
                () -> false,
                () -> true,
                () -> true,
                () -> true,
                syncing,
                () -> {},
                () -> {},
                () -> {});

        SyncPreviewPlan plan = new SyncPreviewPlan(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                0L,
                false);

        coordinator.startSyncWithPlan(plan);
        waitUntil(() -> !coordinator.isSyncing(), Duration.ofSeconds(2));

        assertFalse(requestManifestCalled.get(),
                "requestManifest should not be called when using provided plan");
    }

    @Test
    void waitForCommandInvokesActivityCallbackWhenHeartbeatReceived() throws IOException {
        AtomicInteger activityCallbackCount = new AtomicInteger(0);
        SyncProtocol protocol = new HeartbeatSimulatingProtocol();
        protocol.setMessageActivityCallback(activityCallbackCount::incrementAndGet);

        SyncProtocol.Message result = protocol.waitForCommand(SyncProtocol.CMD_ACK);

        assertTrue(result != null && SyncProtocol.CMD_ACK.equals(result.getCommand()));
        assertEquals(1, activityCallbackCount.get(),
                "Activity callback should be invoked when HEARTBEAT is simulated before expected command");
    }

    @Test
    void sendFileFailurePreservesRealErrorDetail() throws IOException {
        File testFile = tempDir.resolve("test.txt").toFile();
        Files.writeString(testFile.toPath(), "content");

        SyncProtocol protocol = new AckTimeoutProtocol();

        IOException thrown = org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () ->
                protocol.sendFile(tempDir.toFile(), "test.txt"));

        assertTrue(thrown.getMessage().contains("Timeout waiting for command"),
                "Error message should preserve ACK timeout detail, not 'unknown XMODEM error'");
    }

    private static void waitUntil(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Timed out waiting for condition");
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
        private volatile boolean blockAtManifestWait;

        BlockingSyncProtocol() {
            super(new StubSerialPortManager(true));
        }

        void setBlockAtManifestWait(boolean blockAtManifestWait) {
            this.blockAtManifestWait = blockAtManifestWait;
        }

        boolean awaitFirstWaitEntered(Duration timeout) throws InterruptedException {
            return firstWaitEntered.await(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
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
            if (blockAtManifestWait && SyncProtocol.CMD_MANIFEST_DATA.equals(expectedCommand)) {
                firstWaitEntered.countDown();
                try {
                    releaseWait.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while simulating blocked manifest wait", e);
                }
            }
            return new Message(expectedCommand, new String[0]);
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
        private final java.util.concurrent.CountDownLatch continueLatch = new java.util.concurrent.CountDownLatch(1);
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

    private static class HeartbeatSimulatingProtocol extends SyncProtocol {
        HeartbeatSimulatingProtocol() {
            super(new StubSerialPortManager(true));
        }

        @Override
        public void sendHeartbeatAck() {
            // No-op to avoid serial port write
        }

        @Override
        public Message waitForCommand(String expectedCommand) throws IOException {
            notifyMessageActivity();
            return new Message(expectedCommand, new String[0]);
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
}
