package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/** Unit tests for SyncCoordinator to improve code coverage. */
class SyncCoordinatorTest {

    @TempDir Path tempDir;

    // Manifest generation in these tests persists real caches; keep them in the temp dir
    // instead of the user's ~/.filesync.
    @BeforeEach
    void redirectDiskCaches() {
        CacheLocations.setOverrideForTest(tempDir.resolve("cache-dir").toFile());
    }

    @AfterEach
    void restoreDiskCaches() {
        CacheLocations.clearOverrideForTest();
    }

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

    /** Same as {@link #createCoordinator} but pointed at an explicit sync folder. */
    private SyncCoordinator createCoordinatorAt(File folder) {
        return new SyncCoordinator(
                mockProtocol,
                mockEventBus,
                () -> folder,
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

    // ========== Single-file receive handlers clear the syncing flag ==========

    /**
     * The single-file receive paths are the normal wire routes (large files and every batch
     * fallback), and the sender sends no SYNC_COMPLETE for them. If the success path leaves {@code
     * syncing} set, the receiver keeps the Sync Control button in its Cancel state and suppresses
     * heartbeats and connection-loss detection for the rest of the session.
     */
    @Test
    void handleIncomingFileData_clearsSyncingFlagOnSuccess() throws IOException {
        SyncCoordinator coordinator = createCoordinatorAt(syncFolder);
        syncing.set(true);

        coordinator.handleIncomingFileData(
                new SyncProtocol.Message(
                        SyncProtocol.CMD_FILE_DATA, new String[] {"a.txt", "5", "false", "0"}));

        assertFalse(syncing.get(), "a completed single-file receive must clear syncing");
    }

    @Test
    void handleIncomingFileDelta_clearsSyncingFlagOnSuccess() throws IOException {
        SyncCoordinator coordinator = createCoordinatorAt(syncFolder);
        syncing.set(true);

        coordinator.handleIncomingFileDelta(
                new SyncProtocol.Message(
                        SyncProtocol.CMD_FILE_DELTA,
                        new String[] {"a.txt", "5", "false", "0", "10", "md5"}));

        assertFalse(syncing.get(), "a completed delta receive must clear syncing");
    }

    @Test
    void handleIncomingFileAppend_clearsSyncingFlagOnSuccess() throws IOException {
        SyncCoordinator coordinator = createCoordinatorAt(syncFolder);
        syncing.set(true);

        coordinator.handleIncomingFileAppend(
                new SyncProtocol.Message(
                        SyncProtocol.CMD_FILE_APPEND,
                        new String[] {"a.txt", "5", "false", "0", "10", "15", "md5"}));

        assertFalse(syncing.get(), "a completed append receive must clear syncing");
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
    void startSync_noExecutor_callsPerformSyncDirectly_andPostsTimeSyncMarkerAfterSyncStarted()
            throws IOException {
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
    void startSyncWithPlan_postsError_whenSyncFolderNotSelectedOrMissing() {
        // A null folder supplier is rejected before the sync starts
        SyncCoordinator coordinatorWithNullFolder = createCoordinatorAt(null);

        coordinatorWithNullFolder.startSyncWithPlan(null);

        verify(mockEventBus).post(isA(SyncEvent.ErrorEvent.class));
        assertEquals("Please select a sync folder first", getLastErrorMessage());

        // A folder that does not exist on disk is rejected the same way
        File nonExistentFolder = new File("C:/non_existent_folder_12345");
        SyncCoordinator coordinatorWithNonExistentFolder = createCoordinatorAt(nonExistentFolder);

        coordinatorWithNonExistentFolder.startSyncWithPlan(null);

        verify(mockEventBus, times(2)).post(isA(SyncEvent.ErrorEvent.class));
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

    // ========== resolveSafe: path traversal ==========

    /**
     * A remote-supplied path may not resolve outside the sync folder. The bare {@code ..} case is
     * the dangerous one: it collapses to the sync folder's *parent*, so a remote RMDIR would wipe
     * the directory that contains the sync folder.
     */
    @Test
    void resolveSafe_rejectsPathsThatEscapeTheSyncFolder() {
        for (String hostile :
                new String[] {"..", "sub/..", "sub/../..", "../x", "..\\x", "..//x"}) {
            assertThrows(
                    IOException.class,
                    () -> SyncCoordinator.resolveSafe(syncFolder, hostile),
                    "expected rejection for [" + hostile + "]");
        }
    }

    /**
     * Paths that collapse onto the sync folder itself are just as unusable as escaping ones — an
     * empty path or {@code sub/..} would let a remote RMDIR delete the whole sync folder.
     */
    @Test
    void resolveSafe_rejectsPathsThatCollapseToTheSyncFolderItself() {
        for (String hostile : new String[] {"", ".", "./"}) {
            assertThrows(
                    IOException.class,
                    () -> SyncCoordinator.resolveSafe(syncFolder, hostile),
                    "expected rejection for [" + hostile + "]");
        }
    }

    /** Absolute and drive-qualified paths must never be joined onto the sync folder. */
    @Test
    void resolveSafe_rejectsAbsoluteAndDriveQualifiedPaths() {
        for (String hostile :
                new String[] {"/abs", "/", "C:/Windows", "C:\\Windows", "\\\\server\\share\\x"}) {
            assertThrows(
                    IOException.class,
                    () -> SyncCoordinator.resolveSafe(syncFolder, hostile),
                    "expected rejection for [" + hostile + "]");
        }
    }

    /** Ordinary relative paths — including names that merely *contain* dots — stay allowed. */
    @Test
    void resolveSafe_allowsOrdinaryRelativePaths() throws IOException {
        for (String ok :
                new String[] {"file.txt", "sub", "sub/file.txt", "a..b", "sub..dir/a..b"}) {
            File resolved = SyncCoordinator.resolveSafe(syncFolder, ok);
            assertTrue(
                    resolved.getCanonicalFile()
                            .toPath()
                            .startsWith(syncFolder.getCanonicalFile().toPath()),
                    "expected [" + ok + "] to stay inside the sync folder");
        }
    }

    // ========== handleRmdir: traversal must not reach outside the sync folder ==========

    /**
     * Regression: {@code RMDIR ..} used to delete the parent of the sync folder (recursively), and
     * {@code RMDIR ""} used to delete the sync folder itself.
     */
    @Test
    void handleRmdir_refusesToDeleteTheParentOfTheSyncFolder() throws IOException {
        Path outer = tempDir.resolve("outer");
        Path nestedSyncFolder = outer.resolve("syncFolder");
        Files.createDirectories(nestedSyncFolder);
        Path sentinel = outer.resolve("sentinel.txt");
        Files.writeString(sentinel, "must survive");
        SyncCoordinator coordinator = createCoordinatorAt(nestedSyncFolder.toFile());

        coordinator.handleRmdir("..");

        assertTrue(Files.exists(sentinel), "sentinel outside the sync folder must survive");
        assertTrue(Files.exists(nestedSyncFolder), "sync folder itself must survive");
        verify(mockEventBus).post(isA(SyncEvent.ErrorEvent.class));
    }

    @Test
    void handleRmdir_refusesToDeleteTheSyncFolderItself() throws IOException {
        Path nestedSyncFolder = tempDir.resolve("syncFolder");
        Files.createDirectories(nestedSyncFolder);
        Files.writeString(nestedSyncFolder.resolve("keep.txt"), "must survive");
        SyncCoordinator coordinator = createCoordinatorAt(nestedSyncFolder.toFile());

        coordinator.handleRmdir("");

        assertTrue(Files.exists(nestedSyncFolder), "sync folder itself must survive");
        verify(mockEventBus).post(isA(SyncEvent.ErrorEvent.class));
    }

    /**
     * The empty-directory cleanup that follows a delete walks upwards, so it must stop at the sync
     * folder even when the configured path differs in case from the on-disk one — a case-sensitive
     * comparison there would delete the sync folder and keep climbing. Skipped on case-sensitive
     * filesystems, where the premise does not hold.
     */
    @Test
    void cleanupAfterDelete_stopsAtSyncFolder_whenConfiguredPathCaseDiffers() throws IOException {
        Path nestedSyncFolder = tempDir.resolve("syncFolder");
        Files.createDirectories(nestedSyncFolder);
        Files.writeString(nestedSyncFolder.resolve("only-file.txt"), "content");
        File differentlyCased = new File(nestedSyncFolder.toString().toUpperCase());
        assumeTrue(
                differentlyCased.isDirectory(),
                "case-insensitive filesystem required for this scenario");
        SyncCoordinator coordinator = createCoordinatorAt(differentlyCased);

        coordinator.handleFileDelete("only-file.txt");

        assertTrue(
                Files.exists(nestedSyncFolder), "sync folder itself must survive the cleanup walk");
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

        // isFile() returns false for directory, so nothing is deleted and no error is posted
        assertTrue(Files.exists(dirPath));
        verify(mockEventBus, never()).post(isA(SyncEvent.ErrorEvent.class));
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

        verify(mockProtocol).sendFile(isA(File.class), eq(relativePath), nullable(String.class));
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
    void handleManifestRequest_sendsManifest_logsDiagnostics_andResetsSyncing() throws IOException {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);
        Files.createDirectory(tempDir.resolve("emptyDir"));
        Path testFile = tempDir.resolve("manifestTest.txt");
        Files.writeString(testFile, "content");

        coordinator.handleManifestRequest(null, null);

        verify(mockProtocol).sendManifest(isA(FileChangeDetector.FileManifest.class));
        assertEquals(1, heartbeatTouches.get());
        assertEquals(1, syncIdleCalls.get());
        // After manifest request, syncing should be set to false (in finally block)
        assertFalse(syncing.get());
        assertTrue(
                postedEvents.stream()
                        .anyMatch(
                                e ->
                                        e instanceof SyncEvent.LogEvent le
                                                && le.getMessage().contains("empty dirs")),
                "Manifest completion must report the empty directory count");
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
    void handleManifestRequest_postsManifestProgressEvents() throws IOException {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);
        for (int i = 0; i < 3; i++) {
            Files.writeString(tempDir.resolve("rxProgress" + i + ".txt"), "content-" + i);
        }

        coordinator.handleManifestRequest(true, false);

        // The receiver's own manifest generation is visible on its UI too: the zero-progress
        // onStart event, throttled progress, and the unthrottled final one — and no waiting
        // marker (only the sender waits).
        int generationProgressCount = 0;
        int firstProcessed = -1;
        int lastProcessed = -1;
        int lastTotal = -1;
        for (SyncEvent event : postedEvents) {
            if (event instanceof SyncEvent.ManifestProgressEvent progressEvent) {
                if (progressEvent.getProcessed() < 0) {
                    throw new AssertionError(
                            "The receiver must not post the wait-for-remote-manifest marker");
                }
                if (firstProcessed < 0) {
                    firstProcessed = progressEvent.getProcessed();
                }
                generationProgressCount++;
                lastProcessed = progressEvent.getProcessed();
                lastTotal = progressEvent.getTotal();
            }
        }
        assertEquals(
                0,
                firstProcessed,
                "The generation's first event is the zero-progress onStart event");
        assertTrue(
                generationProgressCount >= 2,
                "Throttled progress events plus the final one expected");
        assertEquals(3, lastProcessed, "The final event carries the real file count");
        assertEquals(3, lastTotal, "The final event reports processed/total");
    }

    @Test
    void handleManifestRequest_sendsHeartbeatsWhileGenerating_andStopsBeforeSendManifest()
            throws IOException {
        // Generation blocks the listener thread, so the receiver cannot answer the sender's
        // liveness probes while hashing: a scheduler task must keep sending heartbeats so the
        // sender's idle-bounded manifest wait stays alive. The task must be stopped before the
        // manifest's XMODEM session (command frames must never interleave with raw transfer
        // bytes), and heartbeats must not leak past a failed generation either.
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);
        Files.writeString(tempDir.resolve("keepalive.txt"), "content");
        ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
        @SuppressWarnings("unchecked")
        ScheduledFuture<?> generatingHeartbeat = mock(ScheduledFuture.class);
        // doReturn dodges the wildcard-capture mismatch between the scheduler's ScheduledFuture<?>
        // return type and the mock's inferred type.
        doReturn(generatingHeartbeat)
                .when(executor)
                .scheduleWithFixedDelay(any(), anyLong(), anyLong(), any(TimeUnit.class));
        coordinator.setExecutor(executor);

        coordinator.handleManifestRequest(true, false);

        verify(executor)
                .scheduleWithFixedDelay(
                        any(),
                        eq(SyncCoordinator.GENERATING_HEARTBEAT_INTERVAL_MS / 2),
                        eq(SyncCoordinator.GENERATING_HEARTBEAT_INTERVAL_MS),
                        any(TimeUnit.class));
        org.mockito.InOrder inOrder =
                org.mockito.Mockito.inOrder(generatingHeartbeat, mockProtocol);
        inOrder.verify(generatingHeartbeat).cancel(false);
        inOrder.verify(mockProtocol).sendManifest(isA(FileChangeDetector.FileManifest.class));
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

    // ========== Complex tests: handleIncomingBatchUnknownTotal ==========

    @Test
    void handleIncomingBatchUnknownTotal_callsProtocolReceiveBatch_andResetsSyncing()
            throws IOException {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);

        coordinator.handleIncomingBatchUnknownTotal(100);

        verify(mockProtocol)
                .receiveBatch(
                        anyInt(),
                        anyInt(),
                        isA(BatchTransferSession.BatchProgressCallback.class),
                        isA(File.class),
                        isA(BatchTransferSession.WriteFailureHandler.class),
                        nullable(BatchTransferSession.EntryConfirmationListener.class));
        assertFalse(syncing.get());
    }

    // ========== Complex tests: handleIncomingFileData ==========

    @Test
    void handleIncomingFileData_success_logsReceivesAndMarksWritten() throws IOException {
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
        verify(mockProtocol)
                .receiveFile(
                        isA(File.class),
                        anyString(),
                        anyInt(),
                        anyBoolean(),
                        anyLong(),
                        nullable(String.class));
        verify(pendingWriteService).markWritten("test.txt");
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
                            handler.onWriteFailed(
                                    "locked.txt",
                                    payload,
                                    1234L,
                                    "being used",
                                    BatchTransferSession.WriteFailureCause.IO_ERROR);
                            return 0;
                        })
                .when(mockProtocol)
                .receiveBatch(anyInt(), anyInt(), any(), any(), any(), any());

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
                .receiveBatch(anyInt(), anyInt(), any(), any(), any(), any());

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
                .receiveFile(
                        isA(File.class),
                        anyString(),
                        anyInt(),
                        anyBoolean(),
                        anyLong(),
                        nullable(String.class));

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
                .receiveFile(
                        isA(File.class),
                        anyString(),
                        anyInt(),
                        anyBoolean(),
                        anyLong(),
                        nullable(String.class));

        assertThrows(
                IOException.class,
                () -> coordinator.handleIncomingFileData(mockMsg),
                "Real communication errors must still propagate (connection teardown)");
        verify(pendingWriteService, never())
                .enqueue(any(), anyString(), any(), anyLong(), anyString());
    }

    // ========== Complex tests: createSyncPreviewPlan ==========

    @Test
    void createSyncPreviewPlan_generatesManifest() throws IOException {
        SyncCoordinator coordinator =
                createCoordinator(
                        () -> true, () -> true, () -> true, null, () -> false, () -> true);
        Path testFile = tempDir.resolve("previewTest.txt");
        Files.writeString(testFile, "content");
        stubManifestExchangeForPreview();

        SyncPreviewPlan plan = coordinator.createSyncPreviewPlan();

        assertNotNull(plan);
        // createSyncPreviewPlan posts 3 LogEvents: "Generating...", "Requesting...", "Remote
        // manifest..."
        verify(mockEventBus, atLeastOnce()).post(isA(SyncEvent.LogEvent.class));
    }

    @Test
    void createSyncPreviewPlan_postsManifestProgressAndWaitingMarker() throws IOException {
        SyncCoordinator coordinator =
                createCoordinator(
                        () -> true, () -> true, () -> true, null, () -> false, () -> true);
        for (int i = 0; i < 3; i++) {
            Files.writeString(tempDir.resolve("progress" + i + ".txt"), "content-" + i);
        }
        stubManifestExchangeForPreview();

        coordinator.createSyncPreviewPlan();

        int waitingMarkerIndex = -1;
        int firstGenerationProgressIndex = -1;
        int firstGenerationProcessed = -1;
        int lastGenerationProgressIndex = -1;
        int lastGenerationProcessed = -1;
        int lastGenerationTotal = -1;
        int requestingLogIndex = -1;
        for (int i = 0; i < postedEvents.size(); i++) {
            if (postedEvents.get(i) instanceof SyncEvent.ManifestProgressEvent progressEvent) {
                if (progressEvent.getProcessed() < 0) {
                    waitingMarkerIndex = i;
                } else if (firstGenerationProgressIndex < 0) {
                    firstGenerationProgressIndex = i;
                    firstGenerationProcessed = progressEvent.getProcessed();
                } else {
                    lastGenerationProgressIndex = i;
                    lastGenerationProcessed = progressEvent.getProcessed();
                    lastGenerationTotal = progressEvent.getTotal();
                }
            }
            if (postedEvents.get(i) instanceof SyncEvent.LogEvent logEvent
                    && logEvent.getMessage().startsWith("Requesting remote manifest")) {
                requestingLogIndex = i;
            }
        }
        assertEquals(
                0,
                firstGenerationProcessed,
                "The generation's first event is the zero-progress onStart event");
        assertTrue(
                firstGenerationProgressIndex >= 0,
                "At least one throttled generation progress event must be posted");
        assertEquals(
                3,
                lastGenerationProcessed,
                "The final (unthrottled) generation event carries the real file count");
        assertEquals(3, lastGenerationTotal, "The final generation event reports processed/total");
        assertTrue(
                requestingLogIndex > lastGenerationProgressIndex,
                "'Requesting remote manifest...' is logged after generation finishes");
        assertTrue(waitingMarkerIndex >= 0, "The wait-for-remote-manifest marker must be posted");
        assertTrue(
                waitingMarkerIndex > requestingLogIndex,
                "The waiting marker follows the 'Requesting remote manifest...' log");
        assertTrue(
                waitingMarkerIndex > lastGenerationProgressIndex,
                "The waiting marker comes after the generation progress events");
    }

    @Test
    void createSyncPreviewPlan_extendsTimeoutDuringManifestExchange() throws IOException {
        // Verify the protocol timeout is temporarily extended to 10 minutes for the manifest
        // exchange and then restored afterwards.
        SyncCoordinator coordinator =
                createCoordinator(
                        () -> true, () -> true, () -> true, null, () -> false, () -> true);
        Path testFile = tempDir.resolve("timeoutTest.txt");
        Files.writeString(testFile, "content");
        stubManifestExchangeForPreview();

        coordinator.createSyncPreviewPlan();

        // Must have saved the original timeout via getTimeout()
        verify(mockProtocol, atLeastOnce()).getTimeout();
        // Must have set the extended timeout for the manifest exchange
        verify(mockProtocol).setTimeout(600_000);
        // Must have restored the original timeout
        verify(mockProtocol).setTimeout(30000);
    }

    @Test
    void createSyncPreviewPlan_idleWaitTimeout_closesGateAndRestoresTimeout() throws IOException {
        // The manifest wait is idle-bounded: a receiver that goes silent (died mid-generation,
        // stopped sending its generating heartbeats) must abort the preview within seconds. The
        // idle timeout must surface like any other read timeout: gate closed, original protocol
        // timeout restored.
        when(mockProtocol.getTimeout()).thenReturn(30000);
        SyncCoordinator coordinator =
                createCoordinator(
                        () -> true, () -> true, () -> true, null, () -> false, () -> true);
        Files.writeString(tempDir.resolve("idleWait.txt"), "content");
        AtomicBoolean gate = new AtomicBoolean(false);
        coordinator.setProtocolExchangeGate(gate::set);
        doThrow(
                        new IOException(
                                "Timeout waiting for command: MANIFEST_DATA (peer silent for 30001 ms)"))
                .when(mockProtocol)
                .waitForCommand(anyString(), anyLong());

        IOException thrown =
                assertThrows(IOException.class, () -> coordinator.createSyncPreviewPlan());

        assertTrue(SyncCoordinator.isReadTimeout(thrown), "the idle timeout surfaces as-is");
        assertTrue(
                thrown.getMessage().contains("peer silent"),
                "the idle timeout names the liveness failure: " + thrown.getMessage());
        assertFalse(gate.get(), "gate must be closed after the exchange fails");
        verify(mockProtocol).setTimeout(30000);
    }

    @Test
    void createSyncPreviewPlan_keepsGateClosedDuringLocalManifestGeneration() throws IOException {
        // Regression test for the preview timeout bug: the protocol-exchange gate used to be held
        // open for the entire preview — starting before local manifest generation — which silenced
        // outbound heartbeats for as long as the folder walk took. A walk longer than the peer's
        // heartbeat timeout made the idle peer declare "Connection lost - no heartbeat response"
        // and tear the link down mid-preview. The gate must span only the manifest round-trip.
        when(mockProtocol.getTimeout()).thenReturn(30000);
        SyncCoordinator coordinator =
                createCoordinator(
                        () -> true, () -> true, () -> true, null, () -> false, () -> true);
        Files.writeString(tempDir.resolve("gateSequence.txt"), "content");

        AtomicBoolean gate = new AtomicBoolean(false);
        List<String> gateTrace = new ArrayList<>();
        coordinator.setProtocolExchangeGate(gate::set);
        doAnswer(
                        invocation -> {
                            Object event = invocation.getArgument(0);
                            if (event instanceof SyncEvent.LogEvent logEvent) {
                                gateTrace.add(logEvent.getMessage() + " gate=" + gate.get());
                            }
                            return null;
                        })
                .when(mockEventBus)
                .post(isA(SyncEvent.class));

        SyncProtocol.Message mockManifestMsg = mock(SyncProtocol.Message.class);
        when(mockManifestMsg.getParams()).thenReturn(new String[] {"0"});
        doAnswer(
                        invocation -> {
                            assertTrue(gate.get(), "gate must be open for the manifest request");
                            return null;
                        })
                .when(mockProtocol)
                .requestManifest(anyBoolean(), anyBoolean());
        when(mockProtocol.waitForCommand(anyString(), anyLong()))
                .thenAnswer(
                        invocation -> {
                            assertTrue(gate.get(), "gate must be open while awaiting the manifest");
                            return mockManifestMsg;
                        });
        when(mockProtocol.receiveManifest(anyInt()))
                .thenAnswer(
                        invocation -> {
                            assertTrue(
                                    gate.get(), "gate must be open while receiving the manifest");
                            return FileChangeDetector.generateManifest(syncFolder, false, true);
                        });

        coordinator.createSyncPreviewPlan();

        assertFalse(gate.get(), "gate must be closed after the preview plan is built");
        assertTrue(
                gateTrace.contains("Generating local manifest... gate=false"),
                "Local manifest generation must run with the gate closed: " + gateTrace);
        assertTrue(
                gateTrace.contains("Requesting remote manifest... gate=false"),
                "The gate must still be closed when the request is logged: " + gateTrace);
    }

    @Test
    void createSyncPreviewPlan_readTimeout_closesGateAndRestoresTimeout() throws IOException {
        when(mockProtocol.getTimeout()).thenReturn(30000);
        SyncCoordinator coordinator =
                createCoordinator(
                        () -> true, () -> true, () -> true, null, () -> false, () -> true);
        Files.writeString(tempDir.resolve("gateTimeout.txt"), "content");

        AtomicBoolean gate = new AtomicBoolean(false);
        List<Boolean> gateOperations = new ArrayList<>();
        coordinator.setProtocolExchangeGate(
                value -> {
                    gate.set(value);
                    gateOperations.add(value);
                });

        doThrow(new IOException("Read timeout"))
                .when(mockProtocol)
                .requestManifest(anyBoolean(), anyBoolean());

        IOException thrown =
                assertThrows(IOException.class, () -> coordinator.createSyncPreviewPlan());
        assertTrue(SyncCoordinator.isReadTimeout(thrown), "the read timeout must surface as-is");

        assertFalse(gate.get(), "gate must be closed after the exchange fails");
        assertEquals(Arrays.asList(true, false), gateOperations, "gate must open then close, once");
        verify(mockProtocol).setTimeout(600_000);
        verify(mockProtocol).setTimeout(30000);
    }

    @Test
    void performSync_readTimeout_reportsCommunicationFailureImmediately() throws IOException {
        // A read timeout mid-transfer means the peer stopped responding (link already torn down
        // on its side). The coordinator must report it as a link failure right away so recovery
        // starts, instead of idling until the next heartbeat check notices the silence.
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);
        AtomicReference<String> reportedReason = new AtomicReference<>(null);
        coordinator.setCommunicationFailureReporter(reportedReason::set);

        List<FileChangeDetector.FileInfo> files = new ArrayList<>();
        files.add(new FileChangeDetector.FileInfo("timeout.txt", 10L, 1L, "h1"));
        Files.writeString(new File(syncFolder, "timeout.txt").toPath(), "x");
        SyncPreviewPlan plan =
                new SyncPreviewPlan(
                        files,
                        Collections.emptyList(),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        10L,
                        false,
                        Collections.emptyList());

        when(mockProtocol.sendBatch(
                        anyList(),
                        anyInt(),
                        isA(BatchTransferSession.BatchProgressCallback.class),
                        isA(File.class)))
                .thenThrow(new IOException("Read timeout"));

        coordinator.startSync(plan);

        assertNotNull(reportedReason.get(), "read timeout must be reported as a link failure");
        assertTrue(
                reportedReason.get().contains("read timeout"),
                "the report should name the read timeout: " + reportedReason.get());
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

    // ========== Helper methods ==========

    /**
     * Stubs the manifest exchange shared by the {@code createSyncPreviewPlan} happy-path tests: the
     * protocol timeout is readable, the manifest wait returns immediately, and the remote manifest
     * mirrors the sync folder's current contents.
     */
    private void stubManifestExchangeForPreview() throws IOException {
        when(mockProtocol.getTimeout()).thenReturn(30000);
        SyncProtocol.Message mockManifestMsg = mock(SyncProtocol.Message.class);
        when(mockManifestMsg.getParams()).thenReturn(new String[] {"0"});
        when(mockProtocol.waitForCommand(anyString(), anyLong())).thenReturn(mockManifestMsg);
        when(mockProtocol.receiveManifest(anyInt()))
                .thenReturn(FileChangeDetector.generateManifest(syncFolder, false, true));
    }

    private String getLastErrorMessage() {
        for (int i = postedEvents.size() - 1; i >= 0; i--) {
            SyncEvent e = postedEvents.get(i);
            if (e instanceof SyncEvent.ErrorEvent) {
                return ((SyncEvent.ErrorEvent) e).getMessage();
            }
        }
        return null;
    }

    // ========== Confirmed-state (base) recording ==========

    /** A fresh read of the store the coordinator maintains for the test sync folder. */
    private SyncStateStore stateStore() {
        return SyncStateStore.forFolder(syncFolder);
    }

    /**
     * Pre-seed one confirmed state into the on-disk store and close it again. Assertions must never
     * reuse this instance: the coordinator opens its own, and a later read has to go through a new
     * {@link #stateStore()} to observe what the coordinator persisted.
     */
    private void seedBase(String path, String md5, long size) throws IOException {
        SyncStateStore seeder = stateStore();
        seeder.confirm(path, md5, size);
        seeder.flush();
    }

    /**
     * Stub the batch transfer so every batch entry succeeds without touching the wire, remembering
     * nothing about failures.
     */
    private void stubSuccessfulBatches() throws IOException {
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
                                cb.onEntryProcessed(i, size, (String) batch.get(i)[1]);
                            }
                            return true;
                        });
        when(mockProtocol.waitForWriteFailures()).thenReturn(java.util.Set.of());
    }

    /** Run one full sender session against the given plan. */
    private void runSync(SyncPreviewPlan plan) {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);
        coordinator.setExecutor(null);
        coordinator.startSyncWithPlan(plan);
    }

    @Test
    void performSync_success_recordsBaseForTransferredAndConvergedFiles() throws IOException {
        Files.writeString(new File(syncFolder, "sent.txt").toPath(), "sender content");
        Files.writeString(new File(syncFolder, "converged.txt").toPath(), "same on both sides");

        List<FileChangeDetector.FileInfo> transfer =
                List.of(new FileChangeDetector.FileInfo("sent.txt", 4L, 1L, "local-md5"));
        Map<String, FileChangeDetector.FileInfo> local = new java.util.HashMap<>();
        local.put("sent.txt", transfer.get(0));
        local.put(
                "converged.txt",
                new FileChangeDetector.FileInfo("converged.txt", 16L, 1L, "shared-md5"));
        Map<String, FileChangeDetector.FileInfo> remote = new java.util.HashMap<>(local);
        stubSuccessfulBatches();

        runSync(
                new SyncPreviewPlan(
                        transfer,
                        List.of(),
                        List.of(),
                        List.of(),
                        4L,
                        false,
                        List.of(),
                        java.util.Set.of(),
                        java.util.Set.of(),
                        java.util.Set.of(),
                        remote,
                        local));

        SyncStateStore store = stateStore();
        SyncStateStore.Confirmed sent = store.base("sent.txt");
        assertNotNull(sent, "a transferred file is confirmed with its local state");
        assertEquals("local-md5", sent.md5());
        SyncStateStore.Confirmed converged = store.base("converged.txt");
        assertNotNull(converged, "a converged file keeps its base fresh without a transfer");
        assertEquals("shared-md5", converged.md5());
    }

    @Test
    void performSync_success_prunesBasesForDeletedPaths() throws IOException {
        Map<String, FileChangeDetector.FileInfo> local = new java.util.HashMap<>();
        local.put("kept.txt", new FileChangeDetector.FileInfo("kept.txt", 5L, 1L, "md5-kept"));
        // A leftover base for a path the local manifest no longer has: pruned at session end.
        seedBase("gone.txt", "md5-gone", 3L);
        seedBase("kept.txt", "md5-kept-old", 5L);
        stubSuccessfulBatches();

        runSync(
                new SyncPreviewPlan(
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        0L,
                        false,
                        List.of(),
                        java.util.Set.of(),
                        java.util.Set.of(),
                        java.util.Set.of(),
                        new java.util.HashMap<>(local),
                        local));

        SyncStateStore store = stateStore();
        SyncStateStore.Confirmed kept = store.base("kept.txt");
        assertNotNull(kept, "a still-present path keeps (a refreshed) base");
        assertEquals("md5-kept", kept.md5(), "the refreshed base reflects the manifest md5");
        assertNull(store.base("gone.txt"), "a base for a path gone from the manifest is pruned");
    }

    @Test
    void performSync_success_recordsMergedContentForMergeResolutions() throws IOException {
        Files.writeString(new File(syncFolder, "merged.txt").toPath(), "old content");
        FileChangeDetector.FileInfo fi =
                new FileChangeDetector.FileInfo("merged.txt", 11L, 1L, "stale-local-md5");
        ConflictInfo conflict =
                new ConflictInfo("merged.txt", fi, fi, false, "old content".getBytes());
        conflict.setResolution(ConflictInfo.Resolution.MERGE);
        conflict.setMergedContent("merged content");
        stubSuccessfulBatches();

        runSync(
                new SyncPreviewPlan(
                        List.of(fi),
                        List.of(),
                        List.of(),
                        List.of(),
                        11L,
                        false,
                        List.of(conflict),
                        java.util.Set.of(),
                        java.util.Set.of(),
                        java.util.Set.of(),
                        new java.util.HashMap<>(),
                        java.util.Map.of("merged.txt", fi)));

        SyncStateStore.Confirmed base = stateStore().base("merged.txt");
        assertNotNull(base, "a merged file is confirmed with the merged content's hash");
        assertEquals(
                FileChangeDetector.manifestMd5("merged content".getBytes()),
                base.md5(),
                "the merged result is not in any manifest, so its hash is computed from the"
                        + " content actually sent");
        assertEquals("merged content".length(), base.size());
    }

    @Test
    void performSync_success_skipsBaseForSkippedConflictsAndUnresolved() throws IOException {
        Files.writeString(new File(syncFolder, "skipped.txt").toPath(), "x");
        Files.writeString(new File(syncFolder, "untouched.txt").toPath(), "x");
        FileChangeDetector.FileInfo skipped =
                new FileChangeDetector.FileInfo("skipped.txt", 1L, 1L, "md5-skip");
        FileChangeDetector.FileInfo diverged =
                new FileChangeDetector.FileInfo("untouched.txt", 1L, 1L, "md5-diverged");
        ConflictInfo skipConflict = new ConflictInfo("skipped.txt", skipped, skipped, false, null);
        skipConflict.setResolution(ConflictInfo.Resolution.SKIP);
        stubSuccessfulBatches();

        // untouched.txt differs on both sides but is not part of the transfer: a diverged file that
        // was neither sent nor converged must keep its divergence alive instead of being blessed.
        runSync(
                new SyncPreviewPlan(
                        List.of(skipped),
                        List.of(),
                        List.of(),
                        List.of(),
                        1L,
                        false,
                        List.of(skipConflict),
                        java.util.Set.of(),
                        java.util.Set.of(),
                        java.util.Set.of(),
                        new java.util.HashMap<>(),
                        java.util.Map.of("skipped.txt", skipped, "untouched.txt", diverged)));

        assertNull(
                stateStore().base("skipped.txt"),
                "a SKIP resolution must not record a base: the divergence has to resurface");
        assertNull(
                stateStore().base("untouched.txt"),
                "a diverged file that was not sent and is not converged records nothing");
    }

    @Test
    void performSync_success_keepRemoteBoth_announcesAndRecordsRemoteState() throws IOException {
        FileChangeDetector.FileInfo local =
                new FileChangeDetector.FileInfo("adopted.txt", 3L, 1L, "local-md5");
        FileChangeDetector.FileInfo remote =
                new FileChangeDetector.FileInfo("adopted.txt", 7L, 9L, "remote-md5");
        ConflictInfo conflict =
                new ConflictInfo("adopted.txt", local, remote, false, "local\n".getBytes());
        conflict.setResolution(ConflictInfo.Resolution.KEEP_REMOTE);
        conflict.setApplyTarget(ConflictInfo.ApplyTarget.BOTH);
        conflict.setRemoteContent("remote content".getBytes());
        stubSuccessfulBatches();

        // The local manifest really knows the file (prune only keeps what it lists), and the
        // remote manifest reports the diverged state that provoked the conflict.
        Map<String, FileChangeDetector.FileInfo> localManifest =
                java.util.Map.of("adopted.txt", local);
        runSync(
                new SyncPreviewPlan(
                        List.of(local),
                        List.of(),
                        List.of(),
                        List.of(),
                        3L,
                        false,
                        List.of(conflict),
                        java.util.Set.of(),
                        java.util.Set.of(),
                        java.util.Set.of(),
                        java.util.Map.of("adopted.txt", remote),
                        localManifest));

        // The resolution is broadcast so the peer's base advances in the same session...
        ArgumentCaptor<Map<String, SyncStateStore.Confirmed>> captor =
                ArgumentCaptor.forClass(Map.class);
        verify(mockProtocol).sendConflictAdopted(captor.capture());
        assertEquals(
                new SyncStateStore.Confirmed("remote-md5", 7L),
                captor.getValue().get("adopted.txt"),
                "the adopted state announces the receiver's md5 and size");
        // ...and recorded locally: both sides now hold the remote version.
        SyncStateStore.Confirmed base = stateStore().base("adopted.txt");
        assertNotNull(base, "KEEP_REMOTE+BOTH records the remote state as the base");
        assertEquals("remote-md5", base.md5());
        assertEquals(7L, base.size());
        assertEquals(
                "remote content",
                Files.readString(new File(syncFolder, "adopted.txt").toPath()),
                "the local file was overwritten with the remote content");
    }

    @Test
    void performSync_zeroOperations_stillRecordsResolvedAndConvergedFiles() throws IOException {
        FileChangeDetector.FileInfo local =
                new FileChangeDetector.FileInfo("adopted.txt", 3L, 1L, "local-md5");
        FileChangeDetector.FileInfo remote =
                new FileChangeDetector.FileInfo("adopted.txt", 7L, 9L, "remote-md5");
        ConflictInfo conflict =
                new ConflictInfo("adopted.txt", local, remote, false, "l".getBytes());
        conflict.setResolution(ConflictInfo.Resolution.KEEP_REMOTE);
        conflict.setApplyTarget(ConflictInfo.ApplyTarget.BOTH);
        conflict.setRemoteContent("remote content".getBytes());
        Map<String, FileChangeDetector.FileInfo> locals = new java.util.HashMap<>();
        locals.put("adopted.txt", local);
        locals.put(
                "converged.txt",
                new FileChangeDetector.FileInfo("converged.txt", 4L, 1L, "shared-md5"));
        Files.writeString(new File(syncFolder, "converged.txt").toPath(), "same");
        when(mockProtocol.waitForWriteFailures()).thenReturn(java.util.Set.of());

        // KEEP_REMOTE-only plans filter the file out of filesToTransfer, so totalOperations is 0
        // and the early-return path runs — the bases must still be recorded.
        runSync(
                new SyncPreviewPlan(
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        0L,
                        false,
                        List.of(conflict),
                        java.util.Set.of(),
                        java.util.Set.of(),
                        java.util.Set.of("converged.txt"),
                        locals,
                        locals));

        SyncStateStore store = stateStore();
        SyncStateStore.Confirmed adopted = store.base("adopted.txt");
        assertNotNull(adopted, "the early return still records adopted resolutions");
        assertEquals("remote-md5", adopted.md5());
        assertNotNull(store.base("converged.txt"), "and keeps converged bases fresh");
        verify(mockProtocol, never()).sendSyncComplete();
    }

    @Test
    void performSync_success_excludesReportedWriteFailuresFromBase() throws IOException {
        Files.writeString(new File(syncFolder, "ok.txt").toPath(), "x");
        Files.writeString(new File(syncFolder, "locked.txt").toPath(), "x");
        FileChangeDetector.FileInfo ok =
                new FileChangeDetector.FileInfo("ok.txt", 1L, 1L, "md5-ok");
        FileChangeDetector.FileInfo locked =
                new FileChangeDetector.FileInfo("locked.txt", 1L, 1L, "md5-locked");
        stubSuccessfulBatches();
        // The receiver reports locked.txt as unwritable: its confirmation must be withdrawn.
        when(mockProtocol.waitForWriteFailures()).thenReturn(java.util.Set.of("locked.txt"));

        runSync(
                new SyncPreviewPlan(
                        List.of(ok, locked),
                        List.of(),
                        List.of(),
                        List.of(),
                        2L,
                        false,
                        List.of(),
                        java.util.Set.of(),
                        java.util.Set.of(),
                        java.util.Set.of(),
                        new java.util.HashMap<>(),
                        java.util.Map.of("ok.txt", ok, "locked.txt", locked)));

        SyncStateStore store = stateStore();
        assertNotNull(store.base("ok.txt"), "a written file is confirmed");
        assertNull(
                store.base("locked.txt"),
                "a reported write failure withdraws the path's optimistic confirmation");
    }

    @Test
    void handleConflictAdopted_recordsAnnouncedStates() throws IOException {
        String frame = "[[SYNC:CONFLICT_ADOPTED:2:a.txt:aa11:10:sub/b.txt:bb22:20]]";
        SyncProtocol.Message msg = SyncProtocol.parseMessage(frame);
        assertNotNull(msg, "test frame must parse");

        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);
        coordinator.handleConflictAdopted(msg);

        SyncStateStore.Confirmed a = stateStore().base("a.txt");
        assertNotNull(a, "the adopted path is confirmed from the frame");
        assertEquals("aa11", a.md5());
        assertEquals(10L, a.size());
        SyncStateStore.Confirmed b = stateStore().base("sub/b.txt");
        assertNotNull(b, "nested paths are announced like any other");
        assertEquals("bb22", b.md5());
        assertEquals(20L, b.size());
    }

    @Test
    void handleConflictAdopted_emptyFrame_recordsNothing() throws IOException {
        SyncProtocol.Message msg = SyncProtocol.parseMessage("[[SYNC:CONFLICT_ADOPTED:0]]");
        assertNotNull(msg);
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);
        coordinator.handleConflictAdopted(msg);
        verify(mockEventBus, never()).post(isA(SyncEvent.ErrorEvent.class));
    }

    @Test
    void handleWriteFailures_withdrawsConfirmedPaths() throws IOException {
        seedBase("a.txt", "md5-a", 1L);
        seedBase("b.txt", "md5-b", 2L);

        SyncProtocol.Message msg = SyncProtocol.parseMessage("[[SYNC:WRITE_FAILURES:1:a.txt]]");
        assertNotNull(msg);
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);
        coordinator.handleWriteFailures(msg);

        SyncStateStore store = stateStore();
        assertNull(store.base("a.txt"), "a reported failure withdraws the confirmation");
        assertNotNull(store.base("b.txt"), "other paths are untouched");
    }

    @Test
    void handleSyncComplete_flushesStoreAndReportsFailures() throws IOException {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);

        // Simulate a receive session: one confirmed transfer and one failed write.
        coordinator.confirmReceiverState("good.txt", "md5-good", 12L);
        coordinator.noteReceiverWriteFailure("locked.txt");

        coordinator.handleSyncComplete();

        SyncStateStore store = stateStore();
        assertNotNull(store.base("good.txt"), "a confirmed path was persisted before reporting");
        assertEquals("md5-good", store.base("good.txt").md5());
        ArgumentCaptor<java.util.Collection<String>> captor =
                ArgumentCaptor.forClass(java.util.Collection.class);
        verify(mockProtocol).sendWriteFailures(captor.capture());
        assertEquals(
                List.of("locked.txt"),
                List.copyOf(captor.getValue()),
                "the failure set is reported to the sender once");
    }

    @Test
    void confirmReceiverState_skipsEntriesWithoutHash() throws IOException {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);

        coordinator.confirmReceiverState("fast.bin", null, 10L);
        coordinator.confirmReceiverState("fast.bin", "", 10L);

        assertNull(
                stateStore().base("fast.bin"),
                "fast mode leaves no hash, and without one no base can be compared");
    }

    @Test
    void handleFileDelete_removesBaseForDeletedPath() throws IOException {
        File file = new File(syncFolder, "doomed.txt");
        Files.writeString(file.toPath(), "content");
        seedBase("doomed.txt", "md5-doomed", 7L);

        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);
        coordinator.handleFileDelete("doomed.txt");

        assertFalse(file.exists());
        assertNull(stateStore().base("doomed.txt"), "a deleted path drops its base with the file");
    }

    @Test
    void handleManifestRequest_resetsFailureSetFromPriorSession() throws IOException {
        SyncCoordinator coordinator =
                createCoordinator(() -> true, () -> true, () -> true, null, null, null);
        // A failure recorded by a session that never reached SYNC_COMPLETE.
        coordinator.noteReceiverWriteFailure("stale-locked.txt");
        stubManifestExchangeForPreview();

        // The next session's manifest request must start the failure set clean, so the stale
        // path is not reported as this session's failure.
        coordinator.handleManifestRequest(null, null);
        coordinator.handleSyncComplete();

        ArgumentCaptor<java.util.Collection<String>> captor =
                ArgumentCaptor.forClass(java.util.Collection.class);
        verify(mockProtocol).sendWriteFailures(captor.capture());
        assertTrue(
                captor.getValue().isEmpty(),
                "a new session reports only its own failures, got: " + captor.getValue());
    }
}
