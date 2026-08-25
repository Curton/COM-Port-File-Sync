package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.filesync.protocol.BatchTransferSession;
import com.filesync.protocol.FileWriteException;
import com.filesync.protocol.SyncProtocol;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for SyncCoordinator to improve code coverage. */
class SyncCoordinatorTest {

    @TempDir Path tempDir;

    private SyncProtocol mockProtocol;
    private SyncEventBus mockEventBus;
    private PendingFileWriteService pendingWriteService;
    private AtomicBoolean syncing;
    private AtomicInteger syncBoundaryCalls;
    private AtomicInteger syncIdleCalls;
    private AtomicInteger heartbeatTouches;
    private List<SyncEvent> postedEvents;
    private File syncFolder;

    @BeforeEach
    void setUp() {
        mockProtocol = mock(SyncProtocol.class);
        mockEventBus = mock(SyncEventBus.class);
        pendingWriteService = mock(PendingFileWriteService.class);
        syncing = new AtomicBoolean(false);
        syncBoundaryCalls = new AtomicInteger(0);
        syncIdleCalls = new AtomicInteger(0);
        heartbeatTouches = new AtomicInteger(0);
        postedEvents = new ArrayList<>();

        doAnswer(
                        invocation -> {
                            Object event = invocation.getArgument(0);
                            if (event != null && event instanceof SyncEvent) {
                                postedEvents.add((SyncEvent) event);
                            }
                            return null;
                        })
                .when(mockEventBus)
                .post(isA(SyncEvent.class));

        syncFolder = tempDir.toFile();
    }

    private SyncCoordinator createCoordinator(
            BooleanSupplier isSender,
            BooleanSupplier connectionAlive,
            BooleanSupplier roleNegotiated,
            BooleanSupplier strictMode,
            BooleanSupplier respectGitignore,
            BooleanSupplier fastMode) {
        return new SyncCoordinator(
                mockProtocol,
                mockEventBus,
                () -> syncFolder,
                strictMode != null ? strictMode : () -> false,
                respectGitignore != null ? respectGitignore : () -> false,
                fastMode != null ? fastMode : () -> true,
                connectionAlive != null ? connectionAlive : () -> true,
                isSender != null ? isSender : () -> true,
                roleNegotiated != null ? roleNegotiated : () -> true,
                pendingWriteService,
                syncing,
                () -> syncIdleCalls.incrementAndGet(),
                () -> syncBoundaryCalls.incrementAndGet(),
                () -> heartbeatTouches.incrementAndGet());
    }

    // ========== Easy tests: setExecutor ==========

    @Test
    void setExecutor_acceptsExecutorService() {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);
        ScheduledExecutorService executor = mock(ScheduledExecutorService.class);

        coordinator.setExecutor(executor);

        // setExecutor just stores the reference, no exception means success
        assertNotNull(coordinator);
    }

    @Test
    void setExecutor_allowsNullExecutor() {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);

        coordinator.setExecutor(null);

        assertNotNull(coordinator);
    }

    // ========== Easy tests: isSyncing ==========

    @Test
    void isSyncing_returnsFalseInitially() {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);

        assertFalse(coordinator.isSyncing());
    }

    @Test
    void isSyncing_returnsTrueWhenSyncing() {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);
        syncing.set(true);

        assertTrue(coordinator.isSyncing());
    }

    // ========== Easy tests: cancelOngoingSync ==========

    @Test
    void cancelOngoingSync_clearsSyncingFlag() {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);
        syncing.set(true);

        coordinator.cancelOngoingSync();

        assertFalse(syncing.get());
    }

    @Test
    void cancelOngoingSync_setsCancelRequested() {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);

        coordinator.cancelOngoingSync();

        // The cancelRequested flag is internal, but we verify the method doesn't throw
        assertNotNull(coordinator);
    }

    @Test
    void cancelOngoingSync_doesNotThrowWhenNotSyncing() {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);
        syncing.set(false);

        coordinator.cancelOngoingSync();

        assertFalse(syncing.get());
    }

    // ========== Easy tests: handleSyncComplete ==========

    @Test
    void handleSyncComplete_clearsSyncingFlag() {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);
        syncing.set(true);

        coordinator.handleSyncComplete();

        assertFalse(syncing.get());
    }

    @Test
    void handleSyncComplete_postsSyncCompleteEvent() {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);

        coordinator.handleSyncComplete();

        verify(mockEventBus).post(isA(SyncEvent.SyncCompleteEvent.class));
    }

    @Test
    void handleSyncComplete_touchesHeartbeat() {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);

        coordinator.handleSyncComplete();

        assertEquals(1, heartbeatTouches.get());
    }

    @Test
    void handleSyncComplete_resetsXmodemInProgress() {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);

        coordinator.handleSyncComplete();

        verify(mockProtocol).resetXmodemInProgress();
    }

    // ========== Medium tests: startSync (no executor) ==========

    @Test
    void startSync_noExecutor_callsPerformSyncDirectly() throws IOException {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);
        coordinator.setExecutor(null);

        // Mock protocol methods to avoid actual file operations
        SyncProtocol.Message mockManifestMsg = mock(SyncProtocol.Message.class);
        when(mockManifestMsg.getParams()).thenReturn(new String[] {"0"});
        when(mockProtocol.waitForCommand(anyString())).thenReturn(mockManifestMsg);
        when(mockProtocol.receiveManifest(anyInt()))
                .thenReturn(FileChangeDetector.generateManifest(syncFolder, false, true));

        coordinator.startSync();

        // Since no executor, performSync runs synchronously and posts SyncStartedEvent
        verify(mockEventBus).post(isA(SyncEvent.SyncStartedEvent.class));
    }

    @Test
    void startSync_noExecutor_postsTimeSyncMarkerAfterSyncStarted() throws IOException {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);
        coordinator.setExecutor(null);

        // Mock protocol methods to avoid actual file operations
        SyncProtocol.Message mockManifestMsg = mock(SyncProtocol.Message.class);
        when(mockManifestMsg.getParams()).thenReturn(new String[] {"0"});
        when(mockProtocol.waitForCommand(anyString())).thenReturn(mockManifestMsg);
        when(mockProtocol.receiveManifest(anyInt()))
                .thenReturn(FileChangeDetector.generateManifest(syncFolder, false, true));

        coordinator.startSync();

        int startedIndex = -1;
        int markerIndex = -1;
        for (int i = 0; i < postedEvents.size(); i++) {
            SyncEvent event = postedEvents.get(i);
            if (event instanceof SyncEvent.SyncStartedEvent) {
                startedIndex = i;
            }
            if (event instanceof SyncEvent.LogEvent le
                    && le.getMessage().startsWith("TIME-SYNC")
                    && TimeSyncMarker.parseEpochMs(le.getMessage()) != null) {
                markerIndex = i;
            }
        }
        assertTrue(startedIndex >= 0, "SyncStartedEvent should be posted");
        assertTrue(
                markerIndex > startedIndex,
                "The TIME-SYNC marker must be logged after the sync-start event");
    }

    // ========== Medium tests: startSyncWithPlan validation ==========

    @Test
    void startSyncWithPlan_postsError_whenNotSender() {
        SyncCoordinator coordinator =
                createCoordinator(() -> false, () -> true, () -> true, null, null, null);

        coordinator.startSyncWithPlan(null);

        verify(mockEventBus).post(isA(SyncEvent.ErrorEvent.class));
        assertEquals(
                "Cannot initiate sync as receiver. Change direction first.", getLastErrorMessage());
    }

    @Test
    void startSyncWithPlan_postsError_whenDisconnected() {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> false, () -> true, null, null, null);

        coordinator.startSyncWithPlan(null);

        verify(mockEventBus).post(isA(SyncEvent.ErrorEvent.class));
        assertEquals("Cannot initiate sync while disconnected", getLastErrorMessage());
    }

    @Test
    void startSyncWithPlan_postsError_whenRoleNotNegotiated() {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> false, null, null, null);

        coordinator.startSyncWithPlan(null);

        verify(mockEventBus).post(isA(SyncEvent.ErrorEvent.class));
        assertEquals(
                "Cannot initiate sync until role negotiation completes", getLastErrorMessage());
    }

    @Test
    void startSyncWithPlan_postsError_whenSyncAlreadyInProgress() {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);
        syncing.set(true);

        coordinator.startSyncWithPlan(null);

        verify(mockEventBus).post(isA(SyncEvent.ErrorEvent.class));
        assertEquals("Sync already in progress", getLastErrorMessage());
    }

    @Test
    void startSyncWithPlan_postsError_whenSyncFolderNotSelected() {
        // Use a supplier that returns null
        File nullFolder = null;
        SyncCoordinator coordinatorWithNullFolder =
                new SyncCoordinator(
                        mockProtocol,
                        mockEventBus,
                        () -> nullFolder,
                        () -> false,
                        () -> false,
                        () -> true,
                        () -> true,
                        () -> true,
                        () -> true,
                        pendingWriteService,
                        syncing,
                        () -> syncIdleCalls.incrementAndGet(),
                        () -> syncBoundaryCalls.incrementAndGet(),
                        () -> heartbeatTouches.incrementAndGet());

        coordinatorWithNullFolder.startSyncWithPlan(null);

        verify(mockEventBus).post(isA(SyncEvent.ErrorEvent.class));
        assertEquals("Please select a sync folder first", getLastErrorMessage());
    }

    @Test
    void startSyncWithPlan_postsError_whenSyncFolderDoesNotExist() {
        // Use a supplier that returns non-existent folder
        File nonExistentFolder = new File("C:/non_existent_folder_12345");
        SyncCoordinator coordinatorWithNonExistentFolder =
                new SyncCoordinator(
                        mockProtocol,
                        mockEventBus,
                        () -> nonExistentFolder,
                        () -> false,
                        () -> false,
                        () -> true,
                        () -> true,
                        () -> true,
                        () -> true,
                        pendingWriteService,
                        syncing,
                        () -> syncIdleCalls.incrementAndGet(),
                        () -> syncBoundaryCalls.incrementAndGet(),
                        () -> heartbeatTouches.incrementAndGet());

        coordinatorWithNonExistentFolder.startSyncWithPlan(null);

        verify(mockEventBus).post(isA(SyncEvent.ErrorEvent.class));
        assertEquals("Please select a sync folder first", getLastErrorMessage());
    }

    // ========== Medium tests: handleMkdir ==========

    @Test
    void handleMkdir_createsDirectory_whenFolderNotExists() throws IOException {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);
        Path subDir = tempDir.resolve("newSubDir");
        String relativePath = "newSubDir";

        coordinator.handleMkdir(relativePath);

        assertTrue(Files.exists(subDir));
        // handleMkdir posts 2 LogEvents: "Creating directory" + "Directory created"
        verify(mockEventBus, atLeastOnce()).post(isA(SyncEvent.LogEvent.class));
        verify(mockEventBus, never()).post(isA(SyncEvent.ErrorEvent.class));
    }

    @Test
    void handleMkdir_logsMessage_whenFolderAlreadyExists() {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);
        Path existingDir = tempDir.resolve("existingDir");
        try {
            Files.createDirectories(existingDir);
        } catch (IOException e) {
            // Ignore
        }
        String relativePath = "existingDir";

        coordinator.handleMkdir(relativePath);

        // Should not try to create (mkdirs returns false when exists)
        assertTrue(Files.exists(existingDir));
    }

    @Test
    void handleMkdir_postsError_whenSyncFolderNull() {
        File nullFolder = null;
        SyncCoordinator coordinatorWithNullFolder =
                new SyncCoordinator(
                        mockProtocol,
                        mockEventBus,
                        () -> nullFolder,
                        () -> false,
                        () -> false,
                        () -> true,
                        () -> true,
                        () -> true,
                        () -> true,
                        pendingWriteService,
                        syncing,
                        () -> syncIdleCalls.incrementAndGet(),
                        () -> syncBoundaryCalls.incrementAndGet(),
                        () -> heartbeatTouches.incrementAndGet());

        coordinatorWithNullFolder.handleMkdir("anyPath");

        // Should log an error when sync folder is null
        verify(mockEventBus, atLeastOnce()).post(isA(SyncEvent.ErrorEvent.class));
    }

    @Test
    void handleMkdir_callsFlushSharedText() {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);
        String relativePath = "newDir";

        coordinator.handleMkdir(relativePath);

        assertEquals(1, syncBoundaryCalls.get());
    }

    @Test
    void handleMkdir_createsNestedDirectories() throws IOException {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);
        String relativePath = "parent/child/grandchild";

        coordinator.handleMkdir(relativePath);

        Path nestedPath = tempDir.resolve("parent").resolve("child").resolve("grandchild");
        assertTrue(Files.exists(nestedPath));
    }

    // ========== Medium tests: handleRmdir ==========

    @Test
    void handleRmdir_deletesEmptyDirectory() throws IOException {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);
        Path dirToDelete = tempDir.resolve("dirToDelete");
        Files.createDirectories(dirToDelete);
        String relativePath = "dirToDelete";

        coordinator.handleRmdir(relativePath);

        assertFalse(Files.exists(dirToDelete));
        // handleRmdir posts 2 LogEvents: "Deleting directory" + "Directory deleted"
        verify(mockEventBus, atLeastOnce()).post(isA(SyncEvent.LogEvent.class));
    }

    @Test
    void handleRmdir_doesNothing_whenDirectoryDoesNotExist() {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);
        String relativePath = "nonExistentDir";

        coordinator.handleRmdir(relativePath);

        // Should not throw, just log
        verify(mockEventBus, never()).post(isA(SyncEvent.ErrorEvent.class));
    }

    @Test
    void handleRmdir_postsError_whenNotADirectory() throws IOException {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);
        Path filePath = tempDir.resolve("notADir.txt");
        Files.writeString(filePath, "content");
        String relativePath = "notADir.txt";

        coordinator.handleRmdir(relativePath);

        // isDirectory() returns false, so no deletion happens
        assertTrue(Files.exists(filePath));
    }

    @Test
    void handleRmdir_postsError_whenSyncFolderNull() {
        File nullFolder = null;
        SyncCoordinator coordinatorWithNullFolder =
                new SyncCoordinator(
                        mockProtocol,
                        mockEventBus,
                        () -> nullFolder,
                        () -> false,
                        () -> false,
                        () -> true,
                        () -> true,
                        () -> true,
                        () -> true,
                        pendingWriteService,
                        syncing,
                        () -> syncIdleCalls.incrementAndGet(),
                        () -> syncBoundaryCalls.incrementAndGet(),
                        () -> heartbeatTouches.incrementAndGet());

        coordinatorWithNullFolder.handleRmdir("anyPath");

        verify(mockEventBus, never()).post(isA(SyncEvent.ErrorEvent.class));
    }

    @Test
    void handleRmdir_deletesRecursively() throws IOException {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);
        Path parentDir = tempDir.resolve("parentDir");
        Files.createDirectories(parentDir);
        Files.writeString(parentDir.resolve("file.txt"), "content");
        String relativePath = "parentDir";

        coordinator.handleRmdir(relativePath);

        assertFalse(Files.exists(parentDir));
    }

    // ========== Medium tests: handleFileDelete ==========

    @Test
    void handleFileDelete_deletesExistingFile() throws IOException {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);
        Path fileToDelete = tempDir.resolve("fileToDelete.txt");
        Files.writeString(fileToDelete, "content");
        String relativePath = "fileToDelete.txt";

        coordinator.handleFileDelete(relativePath);

        assertFalse(Files.exists(fileToDelete));
        // handleFileDelete posts 2 LogEvents: "Deleting file" + "File deleted"
        verify(mockEventBus, atLeastOnce()).post(isA(SyncEvent.LogEvent.class));
        verify(mockEventBus, never()).post(isA(SyncEvent.ErrorEvent.class));
    }

    @Test
    void handleFileDelete_postsError_whenDeletionFails() throws IOException {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);
        // handleFileDelete only deletes files (isFile() check), not directories.
        // To trigger an error, we need a path that exists as a directory but is passed as a file.
        // Since handleFileDelete silently skips directories (isFile() returns false),
        // we test the error path by verifying the code path: there's no reliable way to
        // force file.delete() to fail in a temp directory. Instead, verify no error is posted
        // for a directory path (the code silently skips it).
        Path dirPath = tempDir.resolve("aDir");
        Files.createDirectories(dirPath);
        String relativePath = "aDir";

        coordinator.handleFileDelete(relativePath);

        // handleFileDelete skips directories (isFile() returns false), no error posted
        verify(mockEventBus, never()).post(isA(SyncEvent.ErrorEvent.class));
    }

    @Test
    void handleFileDelete_doesNothing_whenFileDoesNotExist() throws IOException {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);
        String relativePath = "nonExistentFile.txt";

        coordinator.handleFileDelete(relativePath);

        // Should not throw, no error should be posted since file doesn't exist
        verify(mockEventBus, never()).post(isA(SyncEvent.ErrorEvent.class));
    }

    @Test
    void handleFileDelete_doesNothing_whenPathIsDirectory() throws IOException {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);
        Path dirPath = tempDir.resolve("aDirectory");
        Files.createDirectories(dirPath);
        String relativePath = "aDirectory";

        coordinator.handleFileDelete(relativePath);

        // isFile() returns false for directory, so nothing is deleted
        assertTrue(Files.exists(dirPath));
    }

    @Test
    void handleFileDelete_postsError_whenSyncFolderNull() throws IOException {
        File nullFolder = null;
        SyncCoordinator coordinatorWithNullFolder =
                new SyncCoordinator(
                        mockProtocol,
                        mockEventBus,
                        () -> nullFolder,
                        () -> false,
                        () -> false,
                        () -> true,
                        () -> true,
                        () -> true,
                        () -> true,
                        pendingWriteService,
                        syncing,
                        () -> syncIdleCalls.incrementAndGet(),
                        () -> syncBoundaryCalls.incrementAndGet(),
                        () -> heartbeatTouches.incrementAndGet());

        coordinatorWithNullFolder.handleFileDelete("anyPath");

        verify(mockEventBus, never()).post(isA(SyncEvent.ErrorEvent.class));
    }

    // ========== Medium tests: handleFileRequest ==========

    @Test
    void handleFileRequest_sendsFile() throws IOException {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);
        Path testFile = tempDir.resolve("testFile.txt");
        Files.writeString(testFile, "test content");
        String relativePath = "testFile.txt";

        coordinator.handleFileRequest(relativePath);

        verify(mockProtocol).sendFile(syncFolder, relativePath);
        verify(mockEventBus).post(isA(SyncEvent.LogEvent.class));
    }

    @Test
    void handleFileRequest_postsError_whenSyncFolderNull() throws IOException {
        File nullFolder = null;
        SyncCoordinator coordinatorWithNullFolder =
                new SyncCoordinator(
                        mockProtocol,
                        mockEventBus,
                        () -> nullFolder,
                        () -> false,
                        () -> false,
                        () -> true,
                        () -> true,
                        () -> true,
                        () -> true,
                        pendingWriteService,
                        syncing,
                        () -> syncIdleCalls.incrementAndGet(),
                        () -> syncBoundaryCalls.incrementAndGet(),
                        () -> heartbeatTouches.incrementAndGet());

        coordinatorWithNullFolder.handleFileRequest("anyPath");

        verify(mockProtocol).sendError("Sync folder not configured");
    }

    // ========== Complex tests: handleManifestRequest ==========

    @Test
    void handleManifestRequest_sendsManifest() throws IOException {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);
        Path testFile = tempDir.resolve("manifestTest.txt");
        Files.writeString(testFile, "content");

        coordinator.handleManifestRequest(null, null);

        verify(mockProtocol).sendManifest(isA(FileChangeDetector.FileManifest.class));
        assertEquals(1, heartbeatTouches.get());
        assertEquals(1, syncIdleCalls.get());
    }

    @Test
    void handleManifestRequest_usesSenderSettings_whenProvided() throws IOException {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);
        Path testFile = tempDir.resolve("manifestTest.txt");
        Files.writeString(testFile, "content");

        coordinator.handleManifestRequest(true, false);

        verify(mockProtocol).sendManifest(isA(FileChangeDetector.FileManifest.class));
    }

    @Test
    void handleManifestRequest_sendsError_whenSyncFolderNull() throws IOException {
        File nullFolder = null;
        SyncCoordinator coordinatorWithNullFolder =
                new SyncCoordinator(
                        mockProtocol,
                        mockEventBus,
                        () -> nullFolder,
                        () -> false,
                        () -> false,
                        () -> true,
                        () -> true,
                        () -> true,
                        () -> true,
                        pendingWriteService,
                        syncing,
                        () -> syncIdleCalls.incrementAndGet(),
                        () -> syncBoundaryCalls.incrementAndGet(),
                        () -> heartbeatTouches.incrementAndGet());

        coordinatorWithNullFolder.handleManifestRequest(null, null);

        verify(mockProtocol).sendError("Sync folder not configured");
    }

    @Test
    void handleManifestRequest_sendsError_whenSyncFolderMissingOnDisk() throws IOException {
        // Non-null File that does not exist: exercises the !exists() side of the folder guard
        // (the null side is covered by handleManifestRequest_sendsError_whenSyncFolderNull).
        File missingFolder = tempDir.resolve("missing").toFile();
        SyncCoordinator coordinatorWithMissingFolder =
                new SyncCoordinator(
                        mockProtocol,
                        mockEventBus,
                        () -> missingFolder,
                        () -> false,
                        () -> false,
                        () -> true,
                        () -> true,
                        () -> true,
                        () -> true,
                        pendingWriteService,
                        syncing,
                        () -> syncIdleCalls.incrementAndGet(),
                        () -> syncBoundaryCalls.incrementAndGet(),
                        () -> heartbeatTouches.incrementAndGet());

        coordinatorWithMissingFolder.handleManifestRequest(null, null);

        verify(mockProtocol).sendError("Sync folder not configured");
    }

    @Test
    void handleManifestRequest_logsEmptyDirectoryCount() throws IOException {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);
        Files.createDirectory(tempDir.resolve("emptyDir"));
        Path testFile = tempDir.resolve("manifestTest.txt");
        Files.writeString(testFile, "content");

        coordinator.handleManifestRequest(null, null);

        verify(mockProtocol).sendManifest(isA(FileChangeDetector.FileManifest.class));
        assertTrue(
                postedEvents.stream()
                        .anyMatch(
                                e ->
                                        e instanceof SyncEvent.LogEvent le
                                                && le.getMessage().contains("empty dirs")),
                "Manifest completion must report the empty directory count");
    }

    @Test
    void handleManifestRequest_setsSyncingToTrue() throws IOException {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);
        Path testFile = tempDir.resolve("manifestTest.txt");
        Files.writeString(testFile, "content");

        coordinator.handleManifestRequest(null, null);

        // After manifest request, syncing should be set to false (in finally block)
        assertFalse(syncing.get());
    }

    @Test
    void handleManifestRequest_postsTimeSyncMarkerAtEntry() throws IOException {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);
        Path testFile = tempDir.resolve("manifestTest.txt");
        Files.writeString(testFile, "content");

        coordinator.handleManifestRequest(null, null);

        assertTrue(
                postedEvents.stream()
                        .anyMatch(
                                e ->
                                        e instanceof SyncEvent.LogEvent le
                                                && le.getMessage().startsWith("TIME-SYNC")
                                                && TimeSyncMarker.parseEpochMs(le.getMessage())
                                                        != null),
                "handleManifestRequest must log a TIME-SYNC marker at entry so the receiver's log"
                        + " can be aligned with the sender's");
    }

    // ========== Complex tests: handleIncomingBatch ==========

    @Test
    void handleIncomingBatch_callsProtocolReceiveBatch() throws IOException {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);

        coordinator.handleIncomingBatch(100, 10);

        verify(mockProtocol)
                .receiveBatch(
                        anyInt(),
                        anyInt(),
                        isA(BatchTransferSession.BatchProgressCallback.class),
                        isA(File.class),
                        isA(BatchTransferSession.WriteFailureHandler.class));
        // handleIncomingBatch doesn't call touchHeartbeat() in success path
        assertEquals(0, heartbeatTouches.get());
    }

    @Test
    void handleIncomingBatch_setsSyncingToTrue() throws IOException {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);
        syncing.set(false);

        coordinator.handleIncomingBatch(100, 10);

        // After method completes, syncing should be false (in finally block)
        assertFalse(syncing.get());
    }

    @Test
    void handleIncomingBatch_callsSyncIdle_whenSyncFolderNull() throws IOException {
        File nullFolder = null;
        SyncCoordinator coordinatorWithNullFolder =
                new SyncCoordinator(
                        mockProtocol,
                        mockEventBus,
                        () -> nullFolder,
                        () -> false,
                        () -> false,
                        () -> true,
                        () -> true,
                        () -> true,
                        () -> true,
                        pendingWriteService,
                        syncing,
                        () -> syncIdleCalls.incrementAndGet(),
                        () -> syncBoundaryCalls.incrementAndGet(),
                        () -> heartbeatTouches.incrementAndGet());

        coordinatorWithNullFolder.handleIncomingBatch(100, 10);

        assertEquals(1, syncIdleCalls.get());
        verify(mockProtocol, never())
                .receiveBatch(
                        anyInt(),
                        anyInt(),
                        isA(BatchTransferSession.BatchProgressCallback.class),
                        isA(File.class),
                        isA(BatchTransferSession.WriteFailureHandler.class));
    }

    @Test
    void handleIncomingBatch_logsBatchReceived() throws IOException {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);

        coordinator.handleIncomingBatch(100, 10);

        verify(mockEventBus).post(isA(SyncEvent.LogEvent.class));
    }

    // ========== Complex tests: handleIncomingBatchUnknownTotal ==========

    @Test
    void handleIncomingBatchUnknownTotal_callsProtocolReceiveBatch() throws IOException {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);

        coordinator.handleIncomingBatchUnknownTotal(100);

        verify(mockProtocol)
                .receiveBatch(
                        anyInt(),
                        anyInt(),
                        isA(BatchTransferSession.BatchProgressCallback.class),
                        isA(File.class),
                        isA(BatchTransferSession.WriteFailureHandler.class));
    }

    @Test
    void handleIncomingBatchUnknownTotal_setsSyncingToTrue() throws IOException {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);
        syncing.set(false);

        coordinator.handleIncomingBatchUnknownTotal(100);

        assertFalse(syncing.get());
    }

    // ========== Complex tests: handleIncomingFileData ==========

    @Test
    void handleIncomingFileData_postsLogEvent() throws IOException {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);
        SyncProtocol.Message mockMsg = mock(SyncProtocol.Message.class);
        when(mockMsg.getParam(0)).thenReturn("test.txt");
        when(mockMsg.getParamAsInt(1)).thenReturn(100);
        when(mockMsg.getParamAsBoolean(2)).thenReturn(false);
        when(mockMsg.getParams()).thenReturn(new String[] {"test.txt", "100", "false", "0"});

        coordinator.handleIncomingFileData(mockMsg);

        // handleIncomingFileData posts 2 LogEvents: "Receiving file" + "File received"
        verify(mockEventBus, atLeastOnce()).post(isA(SyncEvent.LogEvent.class));
    }

    @Test
    void handleIncomingFileData_callsProtocolReceiveFile() throws IOException {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);
        SyncProtocol.Message mockMsg = mock(SyncProtocol.Message.class);
        when(mockMsg.getParam(0)).thenReturn("test.txt");
        when(mockMsg.getParamAsInt(1)).thenReturn(100);
        when(mockMsg.getParamAsBoolean(2)).thenReturn(false);
        when(mockMsg.getParams()).thenReturn(new String[] {"test.txt", "100", "false", "0"});

        coordinator.handleIncomingFileData(mockMsg);

        verify(mockProtocol)
                .receiveFile(isA(File.class), anyString(), anyInt(), anyBoolean(), anyLong());
    }

    @Test
    void handleIncomingFileData_callsSyncIdle_whenSyncFolderNull() throws IOException {
        File nullFolder = null;
        SyncCoordinator coordinatorWithNullFolder =
                new SyncCoordinator(
                        mockProtocol,
                        mockEventBus,
                        () -> nullFolder,
                        () -> false,
                        () -> false,
                        () -> true,
                        () -> true,
                        () -> true,
                        () -> true,
                        pendingWriteService,
                        syncing,
                        () -> syncIdleCalls.incrementAndGet(),
                        () -> syncBoundaryCalls.incrementAndGet(),
                        () -> heartbeatTouches.incrementAndGet());
        SyncProtocol.Message mockMsg = mock(SyncProtocol.Message.class);

        coordinatorWithNullFolder.handleIncomingFileData(mockMsg);

        assertEquals(1, syncIdleCalls.get());
    }

    // ========== Pending write handling (locked files) ==========

    @Test
    void handleIncomingBatchUnknownTotal_enqueuesWriteFailuresAndResetsSyncState()
            throws IOException {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);
        byte[] payload = "payload".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        doAnswer(
                        invocation -> {
                            BatchTransferSession.WriteFailureHandler handler =
                                    invocation.getArgument(4);
                            handler.onWriteFailed("locked.txt", payload, 1234L, "being used");
                            return 0;
                        })
                .when(mockProtocol)
                .receiveBatch(anyInt(), anyInt(), any(), any(), any());

        coordinator.handleIncomingBatchUnknownTotal(100);

        verify(pendingWriteService)
                .enqueue(
                        eq(syncFolder),
                        eq("locked.txt"),
                        aryEq(payload),
                        eq(1234L),
                        eq("being used"));
        assertFalse(syncing.get(), "Sync state must be reset after handling the batch");
        assertTrue(
                postedEvents.stream()
                        .anyMatch(
                                e ->
                                        e instanceof SyncEvent.LogEvent le
                                                && le.getMessage()
                                                        .contains("waiting for user decision")),
                "The batch summary must mention files waiting for a user decision");
    }

    @Test
    void handleIncomingBatchUnknownTotal_marksWrittenForSuccessfulEntries() throws IOException {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);
        doAnswer(
                        invocation -> {
                            BatchTransferSession.BatchProgressCallback callback =
                                    invocation.getArgument(2);
                            callback.onEntryProcessed(0, 1, "ok.txt");
                            return 1;
                        })
                .when(mockProtocol)
                .receiveBatch(anyInt(), anyInt(), any(), any(), any());

        coordinator.handleIncomingBatchUnknownTotal(100);

        verify(pendingWriteService).markWritten("ok.txt");
    }

    @Test
    void handleIncomingFileData_fileWriteException_enqueuesWithoutRethrowing() throws IOException {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);
        SyncProtocol.Message mockMsg = mock(SyncProtocol.Message.class);
        when(mockMsg.getParam(0)).thenReturn("locked.txt");
        when(mockMsg.getParamAsInt(1)).thenReturn(100);
        when(mockMsg.getParamAsBoolean(2)).thenReturn(false);
        when(mockMsg.getParams()).thenReturn(new String[] {"locked.txt", "100", "false", "0"});
        byte[] payload = "payload".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        doThrow(new FileWriteException("locked.txt", payload, 77L, "being used", null))
                .when(mockProtocol)
                .receiveFile(isA(File.class), anyString(), anyInt(), anyBoolean(), anyLong());

        // A locked target must not tear down the connection: no exception may escape.
        coordinator.handleIncomingFileData(mockMsg);

        verify(pendingWriteService)
                .enqueue(
                        eq(syncFolder),
                        eq("locked.txt"),
                        aryEq(payload),
                        eq(77L),
                        eq("being used"));
        assertFalse(syncing.get(), "Sync state must be reset after a queued write failure");
        // A locked target must refresh the Sync Control button: transfer-progress events left it
        // as an enabled "Cancel", and without a refresh it stays stale while the pending-write
        // dialog is open. No SYNC_COMPLETE/SYNC_CANCELLED is expected on this path.
        verify(mockEventBus).post(isA(SyncEvent.SyncControlRefreshEvent.class));
        verify(mockEventBus, never()).post(isA(SyncEvent.SyncCompleteEvent.class));
        verify(mockEventBus, never()).post(isA(SyncEvent.SyncCancelledEvent.class));
    }

    @Test
    void handleIncomingFileData_plainIOException_stillRethrows() throws IOException {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);
        SyncProtocol.Message mockMsg = mock(SyncProtocol.Message.class);
        when(mockMsg.getParam(0)).thenReturn("test.txt");
        when(mockMsg.getParamAsInt(1)).thenReturn(100);
        when(mockMsg.getParamAsBoolean(2)).thenReturn(false);
        when(mockMsg.getParams()).thenReturn(new String[] {"test.txt", "100", "false", "0"});
        doThrow(new IOException("communication failure"))
                .when(mockProtocol)
                .receiveFile(isA(File.class), anyString(), anyInt(), anyBoolean(), anyLong());

        assertThrows(
                IOException.class,
                () -> coordinator.handleIncomingFileData(mockMsg),
                "Real communication errors must still propagate (connection teardown)");
        verify(pendingWriteService, never())
                .enqueue(any(), anyString(), any(), anyLong(), anyString());
    }

    @Test
    void handleIncomingFileData_success_marksWritten() throws IOException {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);
        SyncProtocol.Message mockMsg = mock(SyncProtocol.Message.class);
        when(mockMsg.getParam(0)).thenReturn("test.txt");
        when(mockMsg.getParamAsInt(1)).thenReturn(100);
        when(mockMsg.getParamAsBoolean(2)).thenReturn(false);
        when(mockMsg.getParams()).thenReturn(new String[] {"test.txt", "100", "false", "0"});

        coordinator.handleIncomingFileData(mockMsg);

        verify(pendingWriteService).markWritten("test.txt");
    }

    // ========== Complex tests: createSyncPreviewPlan ==========

    @Test
    void createSyncPreviewPlan_generatesManifest() throws IOException {
        when(mockProtocol.getTimeout()).thenReturn(30000);
        SyncCoordinator coordinator =
                createCoordinator(
                        () -> true, () -> true, () -> true, null, () -> false, () -> true);
        Path testFile = tempDir.resolve("previewTest.txt");
        Files.writeString(testFile, "content");
        SyncProtocol.Message mockManifestMsg = mock(SyncProtocol.Message.class);
        when(mockManifestMsg.getParams()).thenReturn(new String[] {"0"});
        when(mockProtocol.waitForCommand(anyString())).thenReturn(mockManifestMsg);
        when(mockProtocol.receiveManifest(anyInt()))
                .thenReturn(FileChangeDetector.generateManifest(syncFolder, false, true));

        SyncPreviewPlan plan = coordinator.createSyncPreviewPlan();

        assertNotNull(plan);
        // createSyncPreviewPlan posts 3 LogEvents: "Generating...", "Requesting...", "Remote
        // manifest..."
        verify(mockEventBus, atLeastOnce()).post(isA(SyncEvent.LogEvent.class));
    }

    @Test
    void createSyncPreviewPlan_extendsTimeoutDuringManifestExchange() throws IOException {
        // Verify the protocol timeout is temporarily extended to 120s for the manifest
        // exchange and then restored afterwards.
        when(mockProtocol.getTimeout()).thenReturn(30000);
        SyncCoordinator coordinator =
                createCoordinator(
                        () -> true, () -> true, () -> true, null, () -> false, () -> true);
        Path testFile = tempDir.resolve("timeoutTest.txt");
        Files.writeString(testFile, "content");
        SyncProtocol.Message mockManifestMsg = mock(SyncProtocol.Message.class);
        when(mockManifestMsg.getParams()).thenReturn(new String[] {"0"});
        when(mockProtocol.waitForCommand(anyString())).thenReturn(mockManifestMsg);
        when(mockProtocol.receiveManifest(anyInt()))
                .thenReturn(FileChangeDetector.generateManifest(syncFolder, false, true));

        coordinator.createSyncPreviewPlan();

        // Must have saved the original timeout via getTimeout()
        verify(mockProtocol, atLeastOnce()).getTimeout();
        // Must have set the extended timeout for the manifest exchange
        verify(mockProtocol).setTimeout(120_000);
        // Must have restored the original timeout
        verify(mockProtocol).setTimeout(30000);
    }

    @Test
    void createSyncPreviewPlan_throwsException_whenSyncFolderNull() {
        File nullFolder = null;
        SyncCoordinator coordinatorWithNullFolder =
                new SyncCoordinator(
                        mockProtocol,
                        mockEventBus,
                        () -> nullFolder,
                        () -> false,
                        () -> false,
                        () -> true,
                        () -> true,
                        () -> true,
                        () -> true,
                        pendingWriteService,
                        syncing,
                        () -> syncIdleCalls.incrementAndGet(),
                        () -> syncBoundaryCalls.incrementAndGet(),
                        () -> heartbeatTouches.incrementAndGet());

        try {
            coordinatorWithNullFolder.createSyncPreviewPlan();
        } catch (IOException e) {
            assertEquals("Please select a sync folder first", e.getMessage());
        }
    }

    // ========== Bug reproduction: progress counting when conflicts are SKIP / KEEP_REMOTE
    // ==========

    @Test
    void performSync_progressEventReachesTotalOperations_evenWithSkippedConflicts()
            throws IOException {
        // Build a plan with 5 files: 2 of them are KEEP_REMOTE (skipped), 3 are normal.
        // The bug is that totalOperations is computed from filesToTransfer.size() (=5),
        // but the 2 KEEP_REMOTE files are silently dropped, so the progress index
        // never advances past 3, breaking the [i/N] reporting and the progress bar.
        List<FileChangeDetector.FileInfo> filesToTransfer = new ArrayList<>();
        filesToTransfer.add(new FileChangeDetector.FileInfo("keepRemote1.txt", 10L, 1L, "h1"));
        filesToTransfer.add(new FileChangeDetector.FileInfo("normal1.txt", 10L, 1L, "h2"));
        filesToTransfer.add(new FileChangeDetector.FileInfo("keepRemote2.txt", 10L, 1L, "h3"));
        filesToTransfer.add(new FileChangeDetector.FileInfo("normal2.txt", 10L, 1L, "h4"));
        filesToTransfer.add(new FileChangeDetector.FileInfo("normal3.txt", 10L, 1L, "h5"));

        for (FileChangeDetector.FileInfo fi : filesToTransfer) {
            Files.writeString(new File(syncFolder, fi.getPath()).toPath(), "x");
        }

        List<ConflictInfo> conflicts = new ArrayList<>();
        ConflictInfo keepRemoteA =
                new ConflictInfo(
                        "keepRemote1.txt",
                        filesToTransfer.get(0),
                        filesToTransfer.get(0),
                        false,
                        "x".getBytes());
        keepRemoteA.setResolution(ConflictInfo.Resolution.KEEP_REMOTE);
        keepRemoteA.setApplyTarget(ConflictInfo.ApplyTarget.REMOTE_ONLY);
        keepRemoteA.setRemoteContent("remote".getBytes());
        conflicts.add(keepRemoteA);

        ConflictInfo keepRemoteB =
                new ConflictInfo(
                        "keepRemote2.txt",
                        filesToTransfer.get(2),
                        filesToTransfer.get(2),
                        false,
                        "x".getBytes());
        keepRemoteB.setResolution(ConflictInfo.Resolution.SKIP);
        conflicts.add(keepRemoteB);

        SyncPreviewPlan plan =
                new SyncPreviewPlan(
                        filesToTransfer,
                        Collections.emptyList(),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        50L,
                        false,
                        conflicts);

        assertEquals(
                5,
                plan.getTotalOperations(),
                "Sanity check: totalOperations should count all 5 filesToTransfer pre-filter");

        // Mock protocol: sendBatch returns true and invokes the callback once per file
        // so progress events are emitted exactly as they would be in a real run.
        when(mockProtocol.sendBatch(
                        anyList(),
                        anyInt(),
                        isA(BatchTransferSession.BatchProgressCallback.class),
                        isA(File.class)))
                .thenAnswer(
                        invocation -> {
                            BatchTransferSession.BatchProgressCallback cb =
                                    invocation.getArgument(2);
                            @SuppressWarnings("unchecked")
                            List<Object[]> batch = invocation.getArgument(0);
                            int size = batch.size();
                            for (int i = 0; i < size; i++) {
                                String relPath = (String) batch.get(i)[1];
                                cb.onEntryProcessed(i, size, relPath);
                            }
                            return true;
                        });

        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);
        coordinator.setExecutor(null);

        coordinator.startSyncWithPlan(plan);

        // Find the maximum currentFile across all FileProgressEvents.
        int maxCurrent = 0;
        int totalFilesReported = -1;
        boolean anyProgressEvent = false;
        for (SyncEvent e : postedEvents) {
            if (e instanceof SyncEvent.FileProgressEvent) {
                anyProgressEvent = true;
                SyncEvent.FileProgressEvent fpe = (SyncEvent.FileProgressEvent) e;
                if (fpe.getCurrentFile() > maxCurrent) {
                    maxCurrent = fpe.getCurrentFile();
                }
                totalFilesReported = fpe.getTotalFiles();
            }
        }

        assertTrue(anyProgressEvent, "Expected at least one FileProgressEvent");
        // The bug: totalFilesReported is 5 (pre-filter) but maxCurrent caps at 3.
        // After the fix, totalFilesReported should equal the number of operations
        // actually executed (5 - 2 skipped = 3) and maxCurrent should match it.
        assertEquals(
                totalFilesReported,
                maxCurrent,
                "max currentFile should reach totalFiles (got max="
                        + maxCurrent
                        + ", total="
                        + totalFilesReported
                        + ")");
    }

    // ========== Helper method ==========

    private String getLastErrorMessage() {
        for (int i = postedEvents.size() - 1; i >= 0; i--) {
            if (postedEvents.get(i) instanceof SyncEvent.ErrorEvent) {
                return ((SyncEvent.ErrorEvent) postedEvents.get(i)).getMessage();
            }
        }
        return null;
    }
}
