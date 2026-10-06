package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.filesync.protocol.SyncProtocol;
import com.filesync.sync.FileChangeDetector.FileInfo;
import com.filesync.sync.FileChangeDetector.FileRename;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/**
 * Executing the planned renames: one confirmed exchange per pair, with the plain
 * transfer-plus-delete replayed when the receiver refuses the move.
 */
class SyncCoordinatorRenameExecutionTest {

    @TempDir java.nio.file.Path tempDir;

    private SyncProtocol mockProtocol;
    private SyncEventBus mockEventBus;
    private PendingFileWriteService pendingWriteService;
    private File syncFolder;
    private List<SyncEvent> postedEvents;

    @BeforeEach
    void setUp() {
        mockProtocol = mock(SyncProtocol.class);
        mockEventBus = mock(SyncEventBus.class);
        pendingWriteService = mock(PendingFileWriteService.class);
        syncFolder = tempDir.toFile();
        postedEvents = new ArrayList<>();
        doAnswer(
                        invocation -> {
                            postedEvents.add((SyncEvent) invocation.getArgument(0));
                            return null;
                        })
                .when(mockEventBus)
                .post(any(SyncEvent.class));
    }

    private SyncCoordinator createCoordinator() {
        return new SyncCoordinator(
                mockProtocol,
                mockEventBus,
                () -> syncFolder,
                () -> false,
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
    }

    /**
     * A plan carrying the rename plus the local manifest entry of the new path, exactly what a real
     * preview produces (the end-of-session base recording prunes against that manifest).
     */
    private SyncPreviewPlan planWith(FileRename rename) {
        return new SyncPreviewPlan(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                0L,
                false,
                List.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Map.of(),
                Map.of(
                        rename.getToPath(),
                        new FileInfo(rename.getToPath(), rename.getSize(), 0L, rename.getMd5())),
                List.of(rename));
    }

    private boolean posted(Class<? extends SyncEvent> type) {
        return postedEvents.stream().anyMatch(type::isInstance);
    }

    @Test
    void acceptedRenameSendsOneCommandAndRecordsTheNewBase() throws IOException {
        FileRename rename = new FileRename("old.bin", "new.bin", 1024L, 777L, "md5-abc");
        when(mockProtocol.sendFileRename("old.bin", "new.bin", 1024L, 777L, "md5-abc"))
                .thenReturn(true);
        when(mockProtocol.waitForWriteFailures()).thenReturn(Set.of());
        SyncCoordinator coordinator = createCoordinator();

        coordinator.startSyncWithPlan(planWith(rename));

        verify(mockProtocol).sendFileRename("old.bin", "new.bin", 1024L, 777L, "md5-abc");
        verify(mockProtocol).sendSyncComplete();
        // A successful rename needs no fallback traffic.
        verify(mockProtocol, never()).sendFileDelete("old.bin");
        verify(mockProtocol, never()).sendFile(eq(syncFolder), eq("new.bin"), eq("md5-abc"));
        assertTrue(posted(SyncEvent.SyncCompleteEvent.class), "the session must complete");

        // The sender's base advances to the renamed path with the announced state.
        SyncStateStore store = coordinator.baseStateStore();
        assertNotNull(store.base("new.bin"));
        assertEquals("md5-abc", store.base("new.bin").md5());
        assertEquals(1024L, store.base("new.bin").size());

        // The rename's progress event counts towards the session total like any transfer.
        ArgumentCaptor<SyncEvent> events = ArgumentCaptor.forClass(SyncEvent.class);
        verify(mockEventBus, atLeastOnce()).post(events.capture());
        List<String> progressPaths =
                events.getAllValues().stream()
                        .filter(e -> e instanceof SyncEvent.FileProgressEvent)
                        .map(e -> ((SyncEvent.FileProgressEvent) e).getFileName())
                        .toList();
        assertTrue(
                progressPaths.contains("[REN] old.bin -> new.bin"),
                "the rename must show in the progress line, got: " + progressPaths);
    }

    @Test
    void rejectedRenameFallsBackToTransferPlusDeleteAndStillRecordsTheBase() throws IOException {
        FileRename rename = new FileRename("old.bin", "new.bin", 1024L, 777L, "md5-abc");
        when(mockProtocol.sendFileRename("old.bin", "new.bin", 1024L, 777L, "md5-abc"))
                .thenReturn(false);
        when(mockProtocol.waitForWriteFailures()).thenReturn(Set.of());
        SyncCoordinator coordinator = createCoordinator();

        coordinator.startSyncWithPlan(planWith(rename));

        verify(mockProtocol).sendFileRename("old.bin", "new.bin", 1024L, 777L, "md5-abc");
        verify(mockProtocol).sendFile(syncFolder, "new.bin", "md5-abc");
        verify(mockProtocol).sendFileDelete("old.bin");
        verify(mockProtocol).sendSyncComplete();
        assertTrue(posted(SyncEvent.SyncCompleteEvent.class));

        // The fallback delivered the sender's bytes to the new path, so the path ends up agreed-on
        // either way — the rename's confirmation stands in for the transfer the plan no longer
        // lists, and the prune against the local manifest keeps it.
        SyncStateStore store = coordinator.baseStateStore();
        assertNotNull(store.base("new.bin"));
        assertEquals("md5-abc", store.base("new.bin").md5());
    }
}
