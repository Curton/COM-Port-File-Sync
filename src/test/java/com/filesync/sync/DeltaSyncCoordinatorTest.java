package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.filesync.delta.FileSignatures;
import com.filesync.delta.SignatureSet;
import com.filesync.delta.SignatureUtil;
import com.filesync.protocol.BatchTransferSession;
import com.filesync.protocol.FileWriteException;
import com.filesync.protocol.SyncProtocol;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/**
 * Integration tests for the SyncCoordinator delta-sync wiring: binary candidate selection in {@code
 * createSyncPreviewPlan}, the receiver-side signature request handler, the receiver-side delta
 * handler, and the sender-side decision to delta-transfer vs fall back to the batch path.
 */
class DeltaSyncCoordinatorTest {

    @TempDir Path tempDir;
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
        postedEvents = new ArrayList<>();
        doAnswer(
                        inv -> {
                            Object e = inv.getArgument(0);
                            if (e instanceof SyncEvent) postedEvents.add((SyncEvent) e);
                            return null;
                        })
                .when(mockEventBus)
                .post(isA(SyncEvent.class));
        syncFolder = tempDir.toFile();
    }

    private SyncCoordinator createCoordinator() {
        return createCoordinatorWithFolder(() -> syncFolder);
    }

    private SyncCoordinator createCoordinatorWithFolder(Supplier<File> folderSupplier) {
        return new SyncCoordinator(
                mockProtocol,
                mockEventBus,
                folderSupplier,
                () -> false,
                () -> false,
                () -> false, // fastMode=false so md5s are computed and compared
                () -> true,
                () -> true,
                () -> true,
                pendingWriteService,
                new AtomicBoolean(false),
                () -> {},
                () -> {},
                () -> {});
    }

    private byte[] randomBytes(int len, long seed) {
        Random rng = new Random(seed);
        byte[] b = new byte[len];
        rng.nextBytes(b);
        return b;
    }

    private FileChangeDetector.FileManifest remoteManifest(String... paths) {
        Map<String, FileChangeDetector.FileInfo> files = new HashMap<>();
        for (String p : paths) {
            // md5 differs from local and mtime=0 (older) -> changed but not a conflict.
            files.put(p, new FileChangeDetector.FileInfo(p, 9999L, 0L, "deadbeefdeadbeefdeadbeefdeadbeef"));
        }
        return new FileChangeDetector.FileManifest(files, new java.util.HashSet<>());
    }

    // ========== candidate selection ==========

    @Test
    void createSyncPreviewPlan_selectsOnlyBinaryBothSidesLargeNonConflict() throws IOException {
        // big.bin: binary, >=8KB, in remote -> candidate.
        Files.write(tempDir.resolve("big.bin"), randomBytes(10 * 1024, 1));
        // big.txt: text, >=8KB, in remote -> excluded (text).
        Files.writeString(tempDir.resolve("big.txt"), "hello world\n".repeat(2000));
        // small.bin: binary, <8KB, in remote -> excluded (size).
        Files.write(tempDir.resolve("small.bin"), randomBytes(1024, 2));
        // new.bin: binary, >=8KB, NOT in remote -> excluded (new file, no receiver base).
        Files.write(tempDir.resolve("new.bin"), randomBytes(10 * 1024, 3));

        when(mockProtocol.getTimeout()).thenReturn(30000);
        SyncProtocol.Message manifestMsg = mock(SyncProtocol.Message.class);
        when(manifestMsg.getParams()).thenReturn(new String[] {"0"});
        when(mockProtocol.waitForCommand(anyString())).thenReturn(manifestMsg);
        // Remote knows big.bin, big.txt, small.bin (all "changed"), but not new.bin.
        when(mockProtocol.receiveManifest(anyInt()))
                .thenReturn(remoteManifest("big.bin", "big.txt", "small.bin"));

        SyncPreviewPlan plan = createCoordinator().createSyncPreviewPlan();

        Set<String> candidates = plan.getDeltaCandidatePaths();
        assertEquals(Set.of("big.bin"), candidates, "only big.bin should be a delta candidate");
    }

    @Test
    void createSyncPreviewPlan_noCandidatesWhenAllFilesAreNew() throws IOException {
        Files.write(tempDir.resolve("big.bin"), randomBytes(10 * 1024, 1));
        when(mockProtocol.getTimeout()).thenReturn(30000);
        SyncProtocol.Message manifestMsg = mock(SyncProtocol.Message.class);
        when(manifestMsg.getParams()).thenReturn(new String[] {"0"});
        when(mockProtocol.waitForCommand(anyString())).thenReturn(manifestMsg);
        // Remote has nothing -> big.bin is new -> no delta candidate.
        when(mockProtocol.receiveManifest(anyInt()))
                .thenReturn(new FileChangeDetector.FileManifest(new HashMap<>(), new java.util.HashSet<>()));

        SyncPreviewPlan plan = createCoordinator().createSyncPreviewPlan();
        assertTrue(plan.getDeltaCandidatePaths().isEmpty());
    }

    // ========== receiver: handleDeltaSigRequest ==========

    @Test
    void handleDeltaSigRequest_sendsSignaturesOnlyForExistingFiles() throws IOException {
        Files.write(tempDir.resolve("big.bin"), randomBytes(10 * 1024, 1));
        createCoordinator().handleDeltaSigRequest(List.of("big.bin", "missing.bin"));

        ArgumentCaptor<SignatureSet> captor = ArgumentCaptor.forClass(SignatureSet.class);
        verify(mockProtocol).sendDeltaSignatures(captor.capture());
        SignatureSet sent = captor.getValue();
        assertEquals(1, sent.size(), "missing.bin must be omitted");
        assertNotNull(sent.get("big.bin"));
    }

    @Test
    void handleDeltaSigRequest_sendsEmptySetWhenNoFilesExist() throws IOException {
        createCoordinator().handleDeltaSigRequest(List.of("missing.bin"));
        verify(mockProtocol).sendDeltaSignatures(isA(SignatureSet.class));
    }

    // ========== receiver: handleIncomingFileDelta ==========

    @Test
    void handleIncomingFileDelta_success_marksWrittenAndAcks() throws IOException {
        Files.write(tempDir.resolve("big.bin"), randomBytes(10 * 1024, 1));
        SyncProtocol.Message msg = mock(SyncProtocol.Message.class);
        when(msg.getParam(0)).thenReturn("big.bin");
        when(msg.getParamAsInt(1)).thenReturn(50);
        when(msg.getParamAsBoolean(2)).thenReturn(false);
        when(msg.getParams()).thenReturn(new String[] {"big.bin", "50", "false", "100", "10240", "abc"});
        // receiveFileDelta is void; default mock does nothing (success path).
        createCoordinator().handleIncomingFileDelta(msg);

        verify(mockProtocol).sendAck();
        verify(pendingWriteService).markWritten("big.bin");
    }

    @Test
    void handleIncomingFileDelta_writeFailureQueuesReconstructedBytes() throws IOException {
        byte[] reconstructed = randomBytes(1024, 7);
        Files.write(tempDir.resolve("big.bin"), reconstructed);
        SyncProtocol.Message msg = mock(SyncProtocol.Message.class);
        when(msg.getParam(0)).thenReturn("big.bin");
        when(msg.getParamAsInt(1)).thenReturn(50);
        when(msg.getParamAsBoolean(2)).thenReturn(false);
        when(msg.getParams()).thenReturn(new String[] {"big.bin", "50", "false", "100", "1024", "abc"});
        doThrow(new FileWriteException("big.bin", reconstructed, 100L, "locked", new IOException("lock")))
                .when(mockProtocol)
                .receiveFileDelta(
                        any(File.class), anyString(), anyInt(), anyBoolean(), anyLong(), anyLong(), nullable(String.class));

        createCoordinator().handleIncomingFileDelta(msg);

        verify(pendingWriteService).enqueue(syncFolder, "big.bin", reconstructed, 100L, "locked");
        // A locked target must refresh the Sync Control button so it does not stay stuck on an
        // enabled "Cancel" while the pending-write dialog is open.
        verify(mockEventBus).post(isA(SyncEvent.SyncControlRefreshEvent.class));
        verify(mockEventBus, never()).post(isA(SyncEvent.SyncCompleteEvent.class));
    }

    @Test
    void handleIncomingFileDelta_md5MismatchReThrows() throws IOException {
        Files.write(tempDir.resolve("big.bin"), randomBytes(1024, 7));
        SyncProtocol.Message msg = mock(SyncProtocol.Message.class);
        when(msg.getParam(0)).thenReturn("big.bin");
        when(msg.getParamAsInt(1)).thenReturn(50);
        when(msg.getParamAsBoolean(2)).thenReturn(false);
        when(msg.getParams()).thenReturn(new String[] {"big.bin", "50", "false", "100", "1024", "abc"});
        doThrow(new IOException("Delta reconstruction verification failed for big.bin"))
                .when(mockProtocol)
                .receiveFileDelta(
                        any(File.class), anyString(), anyInt(), anyBoolean(), anyLong(), anyLong(), nullable(String.class));

        org.junit.jupiter.api.Assertions.assertThrows(
                IOException.class, () -> createCoordinator().handleIncomingFileDelta(msg));
    }

    // ========== sender: performSync delta decision ==========

    private SyncPreviewPlan planFor(String path, long size) {
        FileChangeDetector.FileInfo fi = new FileChangeDetector.FileInfo(path, size, 1L, "h");
        return new SyncPreviewPlan(
                List.of(fi),
                List.of(),
                List.of(),
                List.of(),
                size,
                false,
                List.of(),
                Set.of(path));
    }

    @Test
    void performSync_beneficialDelta_sendsFileDeltaAndSkipsBatch() throws IOException {
        byte[] data = randomBytes(10 * 1024, 1);
        Files.write(tempDir.resolve("big.bin"), data);

        // Signatures computed from the same bytes -> every block matches -> delta is tiny -> beneficial.
        SignatureSet sigs =
                new SignatureSet(List.of(SignatureUtil.compute("big.bin", data, SignatureUtil.chooseBlockSize(data.length))));
        when(mockProtocol.getTimeout()).thenReturn(30000);
        when(mockProtocol.requestDeltaSignatures(anyList())).thenReturn(sigs);
        when(mockProtocol.sendFileDelta(anyString(), any(), anyLong(), anyLong(), anyString()))
                .thenReturn(false);

        SyncCoordinator coordinator = createCoordinator();
        coordinator.setExecutor(null);
        coordinator.startSyncWithPlan(planFor("big.bin", data.length));

        verify(mockProtocol).requestDeltaSignatures(anyList());
        verify(mockProtocol).sendFileDelta(anyString(), any(), anyLong(), anyLong(), anyString());
        verify(mockProtocol, never())
                .sendBatch(anyList(), anyInt(), isA(BatchTransferSession.BatchProgressCallback.class), any(File.class));
    }

    @Test
    void performSync_notBeneficialDelta_fallsBackToBatch() throws IOException {
        byte[] data = randomBytes(10 * 1024, 1);
        Files.write(tempDir.resolve("big.bin"), data);

        // Signatures from completely unrelated bytes -> no block matches -> delta ~= full -> not beneficial.
        byte[] unrelated = randomBytes(10 * 1024, 99);
        SignatureSet sigs =
                new SignatureSet(List.of(SignatureUtil.compute("big.bin", unrelated, SignatureUtil.chooseBlockSize(data.length))));
        when(mockProtocol.getTimeout()).thenReturn(30000);
        when(mockProtocol.requestDeltaSignatures(anyList())).thenReturn(sigs);
        when(mockProtocol.sendBatch(anyList(), anyInt(), isA(BatchTransferSession.BatchProgressCallback.class), any(File.class)))
                .thenAnswer(
                        inv -> {
                            BatchTransferSession.BatchProgressCallback cb = inv.getArgument(2);
                            @SuppressWarnings("unchecked")
                            List<Object[]> batch = inv.getArgument(0);
                            for (int i = 0; i < batch.size(); i++) {
                                cb.onEntryProcessed(i, batch.size(), (String) batch.get(i)[1]);
                            }
                            return true;
                        });

        SyncCoordinator coordinator = createCoordinator();
        coordinator.setExecutor(null);
        coordinator.startSyncWithPlan(planFor("big.bin", data.length));

        verify(mockProtocol).requestDeltaSignatures(anyList());
        verify(mockProtocol, never())
                .sendFileDelta(anyString(), any(), anyLong(), anyLong(), anyString());
        verify(mockProtocol).sendBatch(anyList(), anyInt(), isA(BatchTransferSession.BatchProgressCallback.class), any(File.class));
    }

    @Test
    void performSync_noSignaturesForCandidate_fallsBackToBatch() throws IOException {
        byte[] data = randomBytes(10 * 1024, 1);
        Files.write(tempDir.resolve("big.bin"), data);

        // Receiver returned no signature for the path -> candidate must fall back to full transfer.
        when(mockProtocol.getTimeout()).thenReturn(30000);
        when(mockProtocol.requestDeltaSignatures(anyList())).thenReturn(SignatureSet.empty());
        when(mockProtocol.sendBatch(anyList(), anyInt(), isA(BatchTransferSession.BatchProgressCallback.class), any(File.class)))
                .thenReturn(true);

        SyncCoordinator coordinator = createCoordinator();
        coordinator.setExecutor(null);
        coordinator.startSyncWithPlan(planFor("big.bin", data.length));

        verify(mockProtocol, never())
                .sendFileDelta(anyString(), any(), anyLong(), anyLong(), anyString());
        verify(mockProtocol).sendBatch(anyList(), anyInt(), isA(BatchTransferSession.BatchProgressCallback.class), any(File.class));
    }

    @Test
    void performSync_signatureExchangeFailure_fallsBackToBatchWithoutAborting() throws IOException {
        byte[] data = randomBytes(10 * 1024, 1);
        Files.write(tempDir.resolve("big.bin"), data);

        // Simulate an older peer that does not answer the signature request (timeout/IO error).
        when(mockProtocol.getTimeout()).thenReturn(30000);
        when(mockProtocol.requestDeltaSignatures(anyList()))
                .thenThrow(new IOException("timed out waiting for DELTA_SIG_DATA"));
        when(mockProtocol.sendBatch(anyList(), anyInt(), isA(BatchTransferSession.BatchProgressCallback.class), any(File.class)))
                .thenReturn(true);

        SyncCoordinator coordinator = createCoordinator();
        coordinator.setExecutor(null);
        coordinator.startSyncWithPlan(planFor("big.bin", data.length));

        verify(mockProtocol, never())
                .sendFileDelta(anyString(), any(), anyLong(), anyLong(), anyString());
        verify(mockProtocol).sendBatch(anyList(), anyInt(), isA(BatchTransferSession.BatchProgressCallback.class), any(File.class));
        // A sync-complete marker must still be emitted so the run finishes normally.
        verify(mockProtocol).sendSyncComplete();
    }

    // ========== selectDeltaCandidates branches ==========

    private FileChangeDetector.FileInfo fi(String path, long size) {
        return new FileChangeDetector.FileInfo(path, size, 1L, "h");
    }

    private FileChangeDetector.FileManifest manifestWith(String... paths) {
        Map<String, FileChangeDetector.FileInfo> files = new HashMap<>();
        for (String p : paths) {
            files.put(p, fi(p, 9999));
        }
        return new FileChangeDetector.FileManifest(files, new java.util.HashSet<>());
    }

    @Test
    void selectDeltaCandidates_skipsConflictsAndMissingFiles() throws IOException {
        // ok.bin: valid binary candidate -> included.
        // conflict.bin: >=8KB binary, in remote, but listed as a conflict -> skipped.
        // ghost.bin: in remote + filesToSync, but the file is absent on disk -> skipped.
        Files.write(tempDir.resolve("ok.bin"), randomBytes(10 * 1024, 1));
        Files.write(tempDir.resolve("conflict.bin"), randomBytes(10 * 1024, 2));

        List<FileChangeDetector.FileInfo> filesToSync = List.of(
                fi("ok.bin", 10 * 1024), fi("conflict.bin", 10 * 1024), fi("ghost.bin", 10 * 1024));
        ConflictInfo conflict =
                new ConflictInfo("conflict.bin", fi("conflict.bin", 1), fi("conflict.bin", 1), true, null);

        Set<String> candidates =
                createCoordinator()
                        .selectDeltaCandidates(filesToSync, manifestWith("ok.bin", "conflict.bin", "ghost.bin"),
                                List.of(conflict), syncFolder);

        assertEquals(Set.of("ok.bin"), candidates);
    }

    @Test
    void isBinaryFile_classifiesContentAndHandlesEdgeCases() throws IOException {
        // Large binary -> true (full 4KB sample path).
        File bigBin = tempDir.resolve("big.bin").toFile();
        Files.write(bigBin.toPath(), randomBytes(10 * 1024, 1));
        assertTrue(SyncCoordinator.isBinaryFile(bigBin));

        // Large text -> false.
        File bigTxt = tempDir.resolve("big.txt").toFile();
        Files.writeString(bigTxt.toPath(), "hello world\n".repeat(2000));
        assertFalse(SyncCoordinator.isBinaryFile(bigTxt));

        // Empty file -> false (read returns <= 0).
        File empty = tempDir.resolve("empty.bin").toFile();
        Files.write(empty.toPath(), new byte[0]);
        assertFalse(SyncCoordinator.isBinaryFile(empty));

        // Small binary (< 4KB sample) -> true (short-sample copyOf path).
        File smallBin = tempDir.resolve("small.bin").toFile();
        Files.write(smallBin.toPath(), randomBytes(100, 2));
        assertTrue(SyncCoordinator.isBinaryFile(smallBin));

        // Directory -> false (FileInputStream throws, caught).
        File dir = tempDir.resolve("subdir").toFile();
        dir.mkdirs();
        assertFalse(SyncCoordinator.isBinaryFile(dir));
    }

    // ========== handleDeltaSigRequest edge cases ==========

    @Test
    void handleDeltaSigRequest_nullFolderSendsError() throws IOException {
        SyncCoordinator coordinator = createCoordinatorWithFolder(() -> null);
        coordinator.handleDeltaSigRequest(List.of("a.bin"));
        verify(mockProtocol).sendError("Sync folder not configured");
        verify(mockProtocol, never()).sendDeltaSignatures(isA(SignatureSet.class));
    }

    @Test
    void handleDeltaSigRequest_nonExistentFolderSendsError() throws IOException {
        // Non-null but missing folder exercises the !syncFolder.exists() side of the guard.
        SyncCoordinator coordinator =
                createCoordinatorWithFolder(() -> new File(tempDir.toFile(), "does-not-exist"));
        coordinator.handleDeltaSigRequest(List.of("a.bin"));
        verify(mockProtocol).sendError("Sync folder not configured");
        verify(mockProtocol, never()).sendDeltaSignatures(isA(SignatureSet.class));
    }

    @Test
    void handleDeltaSigRequest_skipsDirectoryAndTraversalPaths() throws IOException {
        Files.write(tempDir.resolve("big.bin"), randomBytes(10 * 1024, 1));
        // A directory at "realdir" and a traversal path "../evil" both fail to produce signatures
        // but must not abort the request; big.bin still yields a signature.
        tempDir.resolve("realdir").toFile().mkdirs();
        createCoordinator()
                .handleDeltaSigRequest(List.of("../evil", "realdir", "big.bin"));

        ArgumentCaptor<SignatureSet> captor = ArgumentCaptor.forClass(SignatureSet.class);
        verify(mockProtocol).sendDeltaSignatures(captor.capture());
        assertEquals(1, captor.getValue().size(), "only big.bin should produce a signature");
        assertNotNull(captor.getValue().get("big.bin"));
    }

    // ========== handleIncomingFileDelta edge cases ==========

    @Test
    void handleIncomingFileDelta_nullFolderReturnsEarly() throws IOException {
        SyncProtocol.Message msg = mock(SyncProtocol.Message.class);
        when(msg.getParam(0)).thenReturn("a.bin");
        when(msg.getParamAsInt(1)).thenReturn(10);
        when(msg.getParamAsBoolean(2)).thenReturn(false);
        when(msg.getParams()).thenReturn(new String[] {"a.bin", "10", "false"});
        createCoordinatorWithFolder(() -> null).handleIncomingFileDelta(msg);
        verify(mockProtocol, never()).sendAck();
    }

    @Test
    void handleIncomingFileDelta_shortParamsDefaultTimestampsToZero() throws IOException {
        Files.write(tempDir.resolve("big.bin"), randomBytes(1024, 1));
        // Only 3 params (path, size, compressed): lastModified/sourceSize/sourceMd5 default.
        SyncProtocol.Message msg = mock(SyncProtocol.Message.class);
        when(msg.getParam(0)).thenReturn("big.bin");
        when(msg.getParamAsInt(1)).thenReturn(10);
        when(msg.getParamAsBoolean(2)).thenReturn(false);
        when(msg.getParams()).thenReturn(new String[] {"big.bin", "10", "false"});
        // receiveFileDelta is void; default mock does nothing (success path).
        createCoordinator().handleIncomingFileDelta(msg);

        verify(mockProtocol).sendAck();
        verify(pendingWriteService).markWritten("big.bin");
    }

    // ========== performSync remaining branches ==========

    @Test
    void performSync_signaturesPresentButPathMissing_fallsBackToBatch() throws IOException {
        byte[] data = randomBytes(10 * 1024, 1);
        Files.write(tempDir.resolve("big.bin"), data);

        // Non-empty set that lacks the candidate path -> sigs == null -> full transfer.
        SignatureSet others =
                new SignatureSet(List.of(SignatureUtil.compute("other.bin", data, 64)));
        when(mockProtocol.getTimeout()).thenReturn(30000);
        when(mockProtocol.requestDeltaSignatures(anyList())).thenReturn(others);
        when(mockProtocol.sendBatch(anyList(), anyInt(), isA(BatchTransferSession.BatchProgressCallback.class), any(File.class)))
                .thenReturn(true);

        SyncCoordinator coordinator = createCoordinator();
        coordinator.setExecutor(null);
        coordinator.startSyncWithPlan(planFor("big.bin", data.length));

        verify(mockProtocol, never())
                .sendFileDelta(anyString(), any(), anyLong(), anyLong(), anyString());
        verify(mockProtocol).sendBatch(anyList(), anyInt(), isA(BatchTransferSession.BatchProgressCallback.class), any(File.class));
    }

    @Test
    void performSync_readFailureOnCandidate_fallsBackToBatch() throws IOException {
        // Candidate is in the plan but the file is absent on disk -> Files.readAllBytes throws.
        byte[] data = randomBytes(10 * 1024, 1);
        SignatureSet sigs =
                new SignatureSet(List.of(SignatureUtil.compute("ghost.bin", data, 64)));
        when(mockProtocol.getTimeout()).thenReturn(30000);
        when(mockProtocol.requestDeltaSignatures(anyList())).thenReturn(sigs);
        when(mockProtocol.sendBatch(anyList(), anyInt(), isA(BatchTransferSession.BatchProgressCallback.class), any(File.class)))
                .thenReturn(true);

        SyncCoordinator coordinator = createCoordinator();
        coordinator.setExecutor(null);
        coordinator.startSyncWithPlan(planFor("ghost.bin", data.length));

        verify(mockProtocol, never())
                .sendFileDelta(anyString(), any(), anyLong(), anyLong(), anyString());
        verify(mockProtocol).sendBatch(anyList(), anyInt(), isA(BatchTransferSession.BatchProgressCallback.class), any(File.class));
    }

    @Test
    void performSync_beneficialCompressedDelta_logsCompressedTag() throws IOException {
        byte[] data = randomBytes(10 * 1024, 1);
        Files.write(tempDir.resolve("big.bin"), data);

        SignatureSet sigs =
                new SignatureSet(List.of(SignatureUtil.compute("big.bin", data, SignatureUtil.chooseBlockSize(data.length))));
        when(mockProtocol.getTimeout()).thenReturn(30000);
        when(mockProtocol.requestDeltaSignatures(anyList())).thenReturn(sigs);
        // sendFileDelta reports the delta was compressed -> covers the "(compressed)" log branch.
        when(mockProtocol.sendFileDelta(anyString(), any(), anyLong(), anyLong(), anyString()))
                .thenReturn(true);

        SyncCoordinator coordinator = createCoordinator();
        coordinator.setExecutor(null);
        coordinator.startSyncWithPlan(planFor("big.bin", data.length));

        verify(mockProtocol).sendFileDelta(anyString(), any(), anyLong(), anyLong(), anyString());
        assertTrue(
                postedEvents.stream()
                        .filter(e -> e instanceof SyncEvent.LogEvent)
                        .map(e -> ((SyncEvent.LogEvent) e).getMessage())
                        .anyMatch(s -> s.contains("compressed") && s.contains("big.bin")),
                "a compressed delta sync should be logged");
    }
}