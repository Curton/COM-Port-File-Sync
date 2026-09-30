package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.filesync.protocol.SyncProtocol;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Receiver side of the rename command: the move is performed only when it verifies, and anything
 * that cannot be verified is answered with a rejection the sender turns into a plain
 * transfer-plus-delete fallback.
 */
class SyncCoordinatorFileRenameTest {

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

    private SyncCoordinator createCoordinator() {
        return new SyncCoordinator(
                mockProtocol,
                mockEventBus,
                () -> syncFolder,
                () -> false,
                () -> false,
                () -> true,
                () -> true,
                () -> true,
                () -> true,
                pendingWriteService,
                new AtomicBoolean(false),
                () -> {},
                () -> {},
                () -> {});
    }

    private static SyncProtocol.Message renameMessage(
            String from, String to, long size, long lastModified, String md5) {
        return new SyncProtocol.Message(
                SyncProtocol.CMD_FILE_RENAME,
                new String[] {from, to, String.valueOf(size), String.valueOf(lastModified), md5});
    }

    @Test
    void verifiedMoveRenamesStampsTimestampAndAcks() throws IOException {
        File oldFile = new File(syncFolder, "old.bin");
        Files.writeString(oldFile.toPath(), "content");
        String md5 = FileChangeDetector.manifestMd5("content".getBytes(StandardCharsets.UTF_8));
        SyncCoordinator coordinator = createCoordinator();

        coordinator.handleFileRename(renameMessage("old.bin", "moved.bin", 7L, 5555L, md5));

        File newFile = new File(syncFolder, "moved.bin");
        assertTrue(newFile.isFile(), "the receiver's file must now live at the new path");
        assertFalse(oldFile.exists(), "the old path must be gone");
        assertEquals(5555L, newFile.lastModified());
        verify(mockProtocol).sendAck();
        verify(mockProtocol, never())
                .sendRenameRejected(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString());

        // The confirmed state moved with the file: the new path is recorded against the sender's
        // announced md5 and the old path's entry is gone.
        SyncStateStore store = coordinator.baseStateStore();
        assertNotNull(store.base("moved.bin"));
        assertEquals(md5, store.base("moved.bin").md5());
        assertNull(store.base("old.bin"));
    }

    @Test
    void moveCreatesTheTargetDirectoryWhenNeeded() throws IOException {
        File oldFile = new File(syncFolder, "old.bin");
        Files.writeString(oldFile.toPath(), "content");
        String md5 = FileChangeDetector.manifestMd5("content".getBytes(StandardCharsets.UTF_8));
        SyncCoordinator coordinator = createCoordinator();

        coordinator.handleFileRename(renameMessage("old.bin", "sub/dir/moved.bin", 7L, 1L, md5));

        assertTrue(new File(syncFolder, "sub/dir/moved.bin").isFile());
        verify(mockProtocol).sendAck();
    }

    @Test
    void driftedContentIsRejectedAndTheFileStays() throws IOException {
        File oldFile = new File(syncFolder, "old.bin");
        Files.writeString(oldFile.toPath(), "edited-after-the-preview");
        SyncCoordinator coordinator = createCoordinator();

        coordinator.handleFileRename(
                renameMessage("old.bin", "moved.bin", 7L, 1L, "md5-of-something-else"));

        assertTrue(oldFile.exists(), "a drifted file must not be moved on");
        assertFalse(new File(syncFolder, "moved.bin").exists());
        verify(mockProtocol)
                .sendRenameRejected("old.bin", "moved.bin", "content drifted on the receiver");
        verify(mockProtocol, never()).sendAck();
    }

    @Test
    void missingSourceIsRejected() throws IOException {
        SyncCoordinator coordinator = createCoordinator();

        coordinator.handleFileRename(renameMessage("absent.bin", "moved.bin", 7L, 1L, "md5-x"));

        verify(mockProtocol)
                .sendRenameRejected("absent.bin", "moved.bin", "source file does not exist");
        verify(mockProtocol, never()).sendAck();
    }

    @Test
    void occupiedTargetIsRejected() throws IOException {
        File oldFile = new File(syncFolder, "old.bin");
        Files.writeString(oldFile.toPath(), "content");
        Files.writeString(new File(syncFolder, "taken.bin").toPath(), "something else");
        String md5 = FileChangeDetector.manifestMd5("content".getBytes(StandardCharsets.UTF_8));
        SyncCoordinator coordinator = createCoordinator();

        coordinator.handleFileRename(renameMessage("old.bin", "taken.bin", 7L, 1L, md5));

        assertTrue(oldFile.exists());
        assertEquals(
                "something else", Files.readString(new File(syncFolder, "taken.bin").toPath()));
        verify(mockProtocol).sendRenameRejected("old.bin", "taken.bin", "target path is occupied");
        verify(mockProtocol, never()).sendAck();
    }

    @Test
    void escapingPathIsRejected() throws IOException {
        SyncCoordinator coordinator = createCoordinator();

        coordinator.handleFileRename(renameMessage("../outside.bin", "moved.bin", 7L, 1L, "md5-x"));

        verify(mockProtocol)
                .sendRenameRejected(
                        org.mockito.ArgumentMatchers.eq("../outside.bin"),
                        org.mockito.ArgumentMatchers.eq("moved.bin"),
                        org.mockito.ArgumentMatchers.contains("invalid path"));
        verify(mockProtocol, never()).sendAck();
    }

    @Test
    void malformedMessageIsRejectedWithoutTouchingAnything() throws IOException {
        SyncCoordinator coordinator = createCoordinator();

        coordinator.handleFileRename(
                new SyncProtocol.Message(SyncProtocol.CMD_FILE_RENAME, new String[] {"a", "b"}));

        verify(mockProtocol).sendRenameRejected("?", "?", "malformed rename command");
        verify(mockProtocol, never()).sendAck();
    }

    @Test
    void emptyAnnouncedMd5MovesWithoutVerification() throws IOException {
        // A peer that could not hash the file (fast mode) announces no md5; the move itself still
        // carries the sender's content to its new path, which beats a retransfer.
        File oldFile = new File(syncFolder, "old.bin");
        Files.writeString(oldFile.toPath(), "content");
        SyncCoordinator coordinator = createCoordinator();

        coordinator.handleFileRename(renameMessage("old.bin", "moved.bin", 7L, 1L, ""));

        assertTrue(new File(syncFolder, "moved.bin").isFile());
        verify(mockProtocol).sendAck();
    }
}
