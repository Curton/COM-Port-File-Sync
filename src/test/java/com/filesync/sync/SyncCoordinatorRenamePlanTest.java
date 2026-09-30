package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.filesync.protocol.SyncProtocol;
import com.filesync.sync.FileChangeDetector.FileInfo;
import com.filesync.sync.FileChangeDetector.FileManifest;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Planning with rename detection: the paired rename replaces both the transfer of its new path and
 * the delete of its old path, while every other operation survives untouched.
 */
class SyncCoordinatorRenamePlanTest {

    @TempDir java.nio.file.Path tempDir;

    private SyncProtocol mockProtocol;
    private SyncEventBus mockEventBus;
    private PendingFileWriteService pendingWriteService;
    private File syncFolder;

    @BeforeEach
    void setUp() {
        mockProtocol = mock(SyncProtocol.class);
        mockEventBus = mock(SyncEventBus.class);
        pendingWriteService = mock(PendingFileWriteService.class);
        syncFolder = tempDir.toFile();
    }

    /** Strict-mode, hashed coordinator with its caches inside the test folder. */
    private SyncCoordinator createCoordinator() {
        return new SyncCoordinator(
                mockProtocol,
                mockEventBus,
                () -> syncFolder,
                () -> true, // strict sync mode
                () -> false,
                () -> false, // full hashing
                () -> true,
                () -> true,
                () -> true,
                pendingWriteService,
                new AtomicBoolean(false),
                () -> {},
                () -> {},
                () -> {}) {
            @Override
            SyncStateStore createSyncStateStore(File folder) {
                return new SyncStateStore(new File(folder, "syncstate-test.json"));
            }
        };
    }

    private void stubManifestExchange(FileManifest remote) throws IOException {
        when(mockProtocol.getTimeout()).thenReturn(30000);
        SyncProtocol.Message manifestMsg = mock(SyncProtocol.Message.class);
        when(manifestMsg.getParams()).thenReturn(new String[] {"0"});
        when(mockProtocol.waitForCommand(eq(SyncProtocol.CMD_MANIFEST_DATA), anyLong()))
                .thenReturn(manifestMsg);
        when(mockProtocol.receiveManifest(anyInt())).thenReturn(remote);
    }

    private static Map<String, FileInfo> remoteFiles(FileInfo... entries) {
        Map<String, FileInfo> files = new HashMap<>();
        for (FileInfo entry : entries) {
            files.put(entry.getPath(), entry);
        }
        return files;
    }

    @Test
    void renamedFileBecomesARenameInsteadOfTransferPlusDelete() throws IOException {
        byte[] moved = "the very same bytes".getBytes(StandardCharsets.UTF_8);
        String movedMd5 = FileChangeDetector.manifestMd5(moved);
        Files.write(syncFolder.toPath().resolve("renamed.bin"), moved);

        stubManifestExchange(
                new FileManifest(
                        remoteFiles(
                                // The receiver's copy of the file the sender moved.
                                new FileInfo("original.bin", moved.length, 0L, movedMd5),
                                // A file the sender deleted outright: still a plain delete.
                                new FileInfo("gone.bin", 8, 0L, "md5-gone")),
                        new HashSet<>()));

        SyncPreviewPlan plan = createCoordinator().createSyncPreviewPlan();

        // One rename, and nothing else planned for either of its paths.
        assertEquals(1, plan.getRenames().size());
        assertEquals("original.bin", plan.getRenames().get(0).getFromPath());
        assertEquals("renamed.bin", plan.getRenames().get(0).getToPath());
        assertTrue(
                plan.getFilesToTransfer().stream()
                        .noneMatch(fi -> fi.getPath().equals("renamed.bin")),
                "the rename replaces the transfer of the new path");
        assertFalse(
                plan.getFilesToDelete().contains("original.bin"),
                "the rename replaces the delete of the old path");
        // The unrelated deletion is untouched.
        assertTrue(plan.getFilesToDelete().contains("gone.bin"));
        // The rename is counted as its own single operation.
        assertEquals(2, plan.getTotalOperations());
        // No bytes were attributed to the rendezvoused file.
        assertEquals(0L, plan.getTotalBytesToTransfer());
    }

    @Test
    void renameIsPlannedEvenWithoutStrictMode() throws IOException {
        // A rename mirrors an explicit move, not an "extra file on the receiver", so it must not
        // depend on the strict-mode delete pass.
        byte[] moved = "moved content".getBytes(StandardCharsets.UTF_8);
        String movedMd5 = FileChangeDetector.manifestMd5(moved);
        Files.write(syncFolder.toPath().resolve("after.txt"), moved);

        when(mockProtocol.getTimeout()).thenReturn(30000);
        SyncProtocol.Message manifestMsg = mock(SyncProtocol.Message.class);
        when(manifestMsg.getParams()).thenReturn(new String[] {"0"});
        when(mockProtocol.waitForCommand(eq(SyncProtocol.CMD_MANIFEST_DATA), anyLong()))
                .thenReturn(manifestMsg);
        when(mockProtocol.receiveManifest(anyInt()))
                .thenReturn(
                        new FileManifest(
                                remoteFiles(new FileInfo("before.txt", moved.length, 0L, movedMd5)),
                                new HashSet<>()));

        SyncCoordinator lenient =
                new SyncCoordinator(
                        mockProtocol,
                        mockEventBus,
                        () -> syncFolder,
                        () -> false, // NOT strict
                        () -> false,
                        () -> false,
                        () -> true,
                        () -> true,
                        () -> true,
                        pendingWriteService,
                        new AtomicBoolean(false),
                        () -> {},
                        () -> {},
                        () -> {}) {
                    @Override
                    SyncStateStore createSyncStateStore(File folder) {
                        return new SyncStateStore(new File(folder, "syncstate-test.json"));
                    }
                };

        SyncPreviewPlan plan = lenient.createSyncPreviewPlan();

        assertEquals(1, plan.getRenames().size());
        assertEquals("before.txt", plan.getRenames().get(0).getFromPath());
        assertEquals("after.txt", plan.getRenames().get(0).getToPath());
    }

    @Test
    void fastModeBinaryWithoutHashIsNotRenamed() throws IOException {
        // No hash on either side: nothing reliable to match, so the file transfers as usual even
        // though the content is identical.
        byte[] moved = new byte[] {1, 2, 3, 4, 5, 6, 7};
        Files.write(syncFolder.toPath().resolve("renamed.bin"), moved);

        stubManifestExchange(
                new FileManifest(
                        remoteFiles(new FileInfo("original.bin", moved.length, 0L, null)),
                        new HashSet<>()));

        SyncCoordinator fast =
                new SyncCoordinator(
                        mockProtocol,
                        mockEventBus,
                        () -> syncFolder,
                        () -> true,
                        () -> false,
                        () -> true, // fast mode
                        () -> true,
                        () -> true,
                        () -> true,
                        pendingWriteService,
                        new AtomicBoolean(false),
                        () -> {},
                        () -> {},
                        () -> {}) {
                    @Override
                    SyncStateStore createSyncStateStore(File folder) {
                        return new SyncStateStore(new File(folder, "syncstate-test.json"));
                    }
                };

        SyncPreviewPlan plan = fast.createSyncPreviewPlan();

        assertTrue(plan.getRenames().isEmpty());
        assertTrue(
                plan.getFilesToTransfer().stream()
                        .anyMatch(fi -> fi.getPath().equals("renamed.bin")),
                "without a hash the file keeps the regular transfer");
    }
}
