package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.filesync.delta.HashUtil;
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
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/**
 * Integration tests for the SyncCoordinator delta-sync wiring: candidate selection in {@code
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
        // Anonymous subclass keeps the signature cache inside the test folder instead of the real
        // user-home .filesync directory.
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
                () -> {}) {
            @Override
            SignatureCache createSignatureCache(File syncFolder) {
                return new SignatureCache(new File(syncFolder, "sigcache-test.json"));
            }
        };
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
            files.put(
                    p,
                    new FileChangeDetector.FileInfo(
                            p, 9999L, 0L, "deadbeefdeadbeefdeadbeefdeadbeef"));
        }
        return new FileChangeDetector.FileManifest(files, new java.util.HashSet<>());
    }

    // ========== candidate selection ==========

    @Test
    void createSyncPreviewPlan_selectsBothSidesLargeNonConflictIncludingText() throws IOException {
        // big.bin: binary, >=8KB, in remote -> candidate.
        Files.write(tempDir.resolve("big.bin"), randomBytes(10 * 1024, 1));
        // big.txt: text, >=8KB, in remote -> candidate too (text is no longer excluded).
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
        assertEquals(
                Set.of("big.bin", "big.txt"),
                candidates,
                "both binary and text large files should be delta candidates");
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
                .thenReturn(
                        new FileChangeDetector.FileManifest(
                                new HashMap<>(), new java.util.HashSet<>()));

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
        when(msg.getParams())
                .thenReturn(new String[] {"big.bin", "50", "false", "100", "10240", "abc"});
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
        when(msg.getParams())
                .thenReturn(new String[] {"big.bin", "50", "false", "100", "1024", "abc"});
        doThrow(
                        new FileWriteException(
                                "big.bin", reconstructed, 100L, "locked", new IOException("lock")))
                .when(mockProtocol)
                .receiveFileDelta(
                        any(File.class),
                        anyString(),
                        anyInt(),
                        anyBoolean(),
                        anyLong(),
                        anyLong(),
                        nullable(String.class));

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
        when(msg.getParams())
                .thenReturn(new String[] {"big.bin", "50", "false", "100", "1024", "abc"});
        doThrow(new IOException("Delta reconstruction verification failed for big.bin"))
                .when(mockProtocol)
                .receiveFileDelta(
                        any(File.class),
                        anyString(),
                        anyInt(),
                        anyBoolean(),
                        anyLong(),
                        anyLong(),
                        nullable(String.class));

        org.junit.jupiter.api.Assertions.assertThrows(
                IOException.class, () -> createCoordinator().handleIncomingFileDelta(msg));
    }

    // ========== sender: performSync delta decision ==========

    private SyncPreviewPlan planFor(String path, long size) {
        FileChangeDetector.FileInfo fi = new FileChangeDetector.FileInfo(path, size, 1L, "h");
        return new SyncPreviewPlan(
                List.of(fi), List.of(), List.of(), List.of(), size, false, List.of(), Set.of(path));
    }

    @Test
    void performSync_beneficialDelta_sendsFileDeltaAndSkipsBatch() throws IOException {
        byte[] data = randomBytes(10 * 1024, 1);
        Files.write(tempDir.resolve("big.bin"), data);

        // Signatures computed from the same bytes -> every block matches -> delta is tiny ->
        // beneficial.
        SignatureSet sigs =
                new SignatureSet(
                        List.of(
                                SignatureUtil.compute(
                                        "big.bin",
                                        data,
                                        SignatureUtil.chooseBlockSize(data.length))));
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
                .sendBatch(
                        anyList(),
                        anyInt(),
                        isA(BatchTransferSession.BatchProgressCallback.class),
                        any(File.class));
    }

    @Test
    void performSync_notBeneficialDelta_fallsBackToBatch() throws IOException {
        byte[] data = randomBytes(10 * 1024, 1);
        Files.write(tempDir.resolve("big.bin"), data);

        // Signatures from completely unrelated bytes -> no block matches -> delta ~= full -> not
        // beneficial.
        byte[] unrelated = randomBytes(10 * 1024, 99);
        SignatureSet sigs =
                new SignatureSet(
                        List.of(
                                SignatureUtil.compute(
                                        "big.bin",
                                        unrelated,
                                        SignatureUtil.chooseBlockSize(data.length))));
        when(mockProtocol.getTimeout()).thenReturn(30000);
        when(mockProtocol.requestDeltaSignatures(anyList())).thenReturn(sigs);
        when(mockProtocol.sendBatch(
                        anyList(),
                        anyInt(),
                        isA(BatchTransferSession.BatchProgressCallback.class),
                        any(File.class)))
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
        verify(mockProtocol)
                .sendBatch(
                        anyList(),
                        anyInt(),
                        isA(BatchTransferSession.BatchProgressCallback.class),
                        any(File.class));
    }

    @Test
    void performSync_noSignaturesForCandidate_fallsBackToBatch() throws IOException {
        byte[] data = randomBytes(10 * 1024, 1);
        Files.write(tempDir.resolve("big.bin"), data);

        // Receiver returned no signature for the path -> candidate must fall back to full transfer.
        when(mockProtocol.getTimeout()).thenReturn(30000);
        when(mockProtocol.requestDeltaSignatures(anyList())).thenReturn(SignatureSet.empty());
        when(mockProtocol.sendBatch(
                        anyList(),
                        anyInt(),
                        isA(BatchTransferSession.BatchProgressCallback.class),
                        any(File.class)))
                .thenReturn(true);

        SyncCoordinator coordinator = createCoordinator();
        coordinator.setExecutor(null);
        coordinator.startSyncWithPlan(planFor("big.bin", data.length));

        verify(mockProtocol, never())
                .sendFileDelta(anyString(), any(), anyLong(), anyLong(), anyString());
        verify(mockProtocol)
                .sendBatch(
                        anyList(),
                        anyInt(),
                        isA(BatchTransferSession.BatchProgressCallback.class),
                        any(File.class));
    }

    @Test
    void performSync_cachedSignatures_skipSecondSignatureExchange() throws IOException {
        byte[] data = randomBytes(10 * 1024, 1);
        Files.write(tempDir.resolve("big.bin"), data);

        // The receiver state (size/mtime/md5) is stable across both syncs, so after the first
        // exchange the sender must reuse the cached signatures instead of requesting them again.
        SignatureSet sigs =
                new SignatureSet(
                        List.of(
                                SignatureUtil.compute(
                                        "big.bin",
                                        data,
                                        SignatureUtil.chooseBlockSize(data.length))));
        when(mockProtocol.getTimeout()).thenReturn(30000);
        when(mockProtocol.requestDeltaSignatures(anyList())).thenReturn(sigs);
        when(mockProtocol.sendFileDelta(anyString(), any(), anyLong(), anyLong(), anyString()))
                .thenReturn(false);

        SyncCoordinator coordinator = createCoordinator();
        coordinator.setExecutor(null);
        SyncPreviewPlan plan = appendPlanFor("big.bin", data.length, 9999L, "remote-md5-value");

        coordinator.startSyncWithPlan(plan);
        verify(mockProtocol).requestDeltaSignatures(anyList());
        verify(mockProtocol).sendFileDelta(anyString(), any(), anyLong(), anyLong(), anyString());

        // Second sync against the same receiver state: no signature exchange, cached signatures.
        coordinator.startSyncWithPlan(plan);
        verify(mockProtocol, times(1)).requestDeltaSignatures(anyList());
        verify(mockProtocol, times(2))
                .sendFileDelta(anyString(), any(), anyLong(), anyLong(), anyString());
    }

    @Test
    void performSync_signatureExchangeFailure_fallsBackToBatchWithoutAborting() throws IOException {
        byte[] data = randomBytes(10 * 1024, 1);
        Files.write(tempDir.resolve("big.bin"), data);

        // Simulate an older peer that does not answer the signature request (timeout/IO error).
        when(mockProtocol.getTimeout()).thenReturn(30000);
        when(mockProtocol.requestDeltaSignatures(anyList()))
                .thenThrow(new IOException("timed out waiting for DELTA_SIG_DATA"));
        when(mockProtocol.sendBatch(
                        anyList(),
                        anyInt(),
                        isA(BatchTransferSession.BatchProgressCallback.class),
                        any(File.class)))
                .thenReturn(true);

        SyncCoordinator coordinator = createCoordinator();
        coordinator.setExecutor(null);
        coordinator.startSyncWithPlan(planFor("big.bin", data.length));

        verify(mockProtocol, never())
                .sendFileDelta(anyString(), any(), anyLong(), anyLong(), anyString());
        verify(mockProtocol)
                .sendBatch(
                        anyList(),
                        anyInt(),
                        isA(BatchTransferSession.BatchProgressCallback.class),
                        any(File.class));
        // A sync-complete marker must still be emitted so the run finishes normally.
        verify(mockProtocol).sendSyncComplete();
    }

    // ========== sender: append-only fast path ==========

    /** Plan whose remote metadata describes a receiver file of {@code remoteSize} bytes. */
    private SyncPreviewPlan appendPlanFor(
            String path, long localSize, long remoteSize, String remoteMd5) {
        FileChangeDetector.FileInfo local =
                new FileChangeDetector.FileInfo(path, localSize, 1L, "h");
        FileChangeDetector.FileInfo remote =
                new FileChangeDetector.FileInfo(path, remoteSize, 0L, remoteMd5);
        Map<String, FileChangeDetector.FileInfo> remoteInfos = new HashMap<>();
        remoteInfos.put(path, remote);
        return new SyncPreviewPlan(
                List.of(local),
                List.of(),
                List.of(),
                List.of(),
                localSize,
                false,
                List.of(),
                Set.of(path),
                Set.of(path),
                remoteInfos);
    }

    @Test
    void performSync_pureAppend_sendsTailOnlyWithoutSignatureExchange() throws IOException {
        byte[] base =
                "2026-09-01 12:00:00 INFO sync log line\n"
                        .repeat(400)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] tail =
                "2026-09-01 12:01:00 INFO appended later\n"
                        .repeat(30)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] grown = new byte[base.length + tail.length];
        System.arraycopy(base, 0, grown, 0, base.length);
        System.arraycopy(tail, 0, grown, base.length, tail.length);
        Files.write(tempDir.resolve("app.log"), grown);
        // The remote md5 must equal what the receiver's manifest computes for the base bytes
        // (text content is hashed with line-ending normalization).
        Path baseCopy = tempDir.resolve("base-copy.tmp");
        Files.write(baseCopy, base);
        String remoteMd5 = FileChangeDetector.calculateMD5(baseCopy.toFile());

        when(mockProtocol.getTimeout()).thenReturn(30000);
        when(mockProtocol.sendFileAppend(
                        anyString(), any(), anyLong(), anyLong(), anyLong(), anyString()))
                .thenReturn(false);

        SyncCoordinator coordinator = createCoordinator();
        coordinator.setExecutor(null);
        coordinator.startSyncWithPlan(
                appendPlanFor("app.log", grown.length, base.length, remoteMd5));

        ArgumentCaptor<byte[]> tailCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(mockProtocol)
                .sendFileAppend(
                        eq("app.log"),
                        tailCaptor.capture(),
                        anyLong(),
                        eq((long) base.length),
                        eq((long) grown.length),
                        anyString());
        assertArrayEquals(tail, tailCaptor.getValue(), "only the appended tail may be sent");
        verify(mockProtocol, never()).requestDeltaSignatures(anyList());
        verify(mockProtocol, never())
                .sendFileDelta(anyString(), any(), anyLong(), anyLong(), anyString());
        verify(mockProtocol, never())
                .sendBatch(
                        anyList(),
                        anyInt(),
                        isA(BatchTransferSession.BatchProgressCallback.class),
                        any(File.class));
        verify(mockProtocol).sendSyncComplete();
    }

    @Test
    void performSync_appendPrefixMismatch_usesSignatureDelta() throws IOException {
        byte[] base =
                "2026-09-01 12:00:00 INFO sync log line\n"
                        .repeat(400)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] tail =
                "2026-09-01 12:01:00 INFO appended later\n"
                        .repeat(30)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] grown = new byte[base.length + tail.length];
        System.arraycopy(base, 0, grown, 0, base.length);
        System.arraycopy(tail, 0, grown, base.length, tail.length);
        Files.write(tempDir.resolve("app.log"), grown);
        // The remote md5 describes different prefix content: not a pure append.
        Path unrelated = tempDir.resolve("unrelated.tmp");
        Files.write(
                unrelated,
                "totally different bytes\n"
                        .repeat(400)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String remoteMd5 = FileChangeDetector.calculateMD5(unrelated.toFile());

        when(mockProtocol.getTimeout()).thenReturn(30000);
        when(mockProtocol.requestDeltaSignatures(anyList())).thenReturn(SignatureSet.empty());
        when(mockProtocol.sendBatch(
                        anyList(),
                        anyInt(),
                        isA(BatchTransferSession.BatchProgressCallback.class),
                        any(File.class)))
                .thenReturn(true);

        SyncCoordinator coordinator = createCoordinator();
        coordinator.setExecutor(null);
        coordinator.startSyncWithPlan(
                appendPlanFor("app.log", grown.length, base.length, remoteMd5));

        verify(mockProtocol, never())
                .sendFileAppend(anyString(), any(), anyLong(), anyLong(), anyLong(), anyString());
        verify(mockProtocol).requestDeltaSignatures(anyList());
        verify(mockProtocol)
                .sendBatch(
                        anyList(),
                        anyInt(),
                        isA(BatchTransferSession.BatchProgressCallback.class),
                        any(File.class));
    }

    @Test
    void performSync_shrunkFile_usesSignatureDelta() throws IOException {
        byte[] grown =
                "2026-09-01 12:00:00 INFO sync log line\n"
                        .repeat(400)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(tempDir.resolve("app.log"), grown);
        // Remote copy is LARGER than the local file (log rotated/truncated on the sender).
        when(mockProtocol.getTimeout()).thenReturn(30000);
        when(mockProtocol.requestDeltaSignatures(anyList())).thenReturn(SignatureSet.empty());
        when(mockProtocol.sendBatch(
                        anyList(),
                        anyInt(),
                        isA(BatchTransferSession.BatchProgressCallback.class),
                        any(File.class)))
                .thenReturn(true);

        SyncCoordinator coordinator = createCoordinator();
        coordinator.setExecutor(null);
        coordinator.startSyncWithPlan(
                appendPlanFor("app.log", grown.length, grown.length + 1000, "deadbeef"));

        verify(mockProtocol, never())
                .sendFileAppend(anyString(), any(), anyLong(), anyLong(), anyLong(), anyString());
        verify(mockProtocol).requestDeltaSignatures(anyList());
    }

    @Test
    void performSync_remoteWithoutMd5_usesSignatureDelta() throws IOException {
        byte[] base =
                "2026-09-01 12:00:00 INFO sync log line\n"
                        .repeat(400)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] tail =
                "2026-09-01 12:01:00 INFO appended later\n"
                        .repeat(30)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] grown = new byte[base.length + tail.length];
        System.arraycopy(base, 0, grown, 0, base.length);
        System.arraycopy(tail, 0, grown, base.length, tail.length);
        Files.write(tempDir.resolve("app.log"), grown);
        // Quick-hash manifest: the receiver's FileInfo carries no md5, so append detection
        // cannot verify the prefix and must not claim the fast path.
        when(mockProtocol.getTimeout()).thenReturn(30000);
        when(mockProtocol.requestDeltaSignatures(anyList())).thenReturn(SignatureSet.empty());
        when(mockProtocol.sendBatch(
                        anyList(),
                        anyInt(),
                        isA(BatchTransferSession.BatchProgressCallback.class),
                        any(File.class)))
                .thenReturn(true);

        SyncCoordinator coordinator = createCoordinator();
        coordinator.setExecutor(null);
        coordinator.startSyncWithPlan(appendPlanFor("app.log", grown.length, base.length, null));

        verify(mockProtocol, never())
                .sendFileAppend(anyString(), any(), anyLong(), anyLong(), anyLong(), anyString());
        verify(mockProtocol).requestDeltaSignatures(anyList());
    }

    @Test
    void performSync_appendSendFailure_fallsBackToBatch() throws IOException {
        byte[] base =
                "2026-09-01 12:00:00 INFO sync log line\n"
                        .repeat(400)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] tail =
                "2026-09-01 12:01:00 INFO appended later\n"
                        .repeat(30)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] grown = new byte[base.length + tail.length];
        System.arraycopy(base, 0, grown, 0, base.length);
        System.arraycopy(tail, 0, grown, base.length, tail.length);
        Files.write(tempDir.resolve("app.log"), grown);
        Path baseCopy = tempDir.resolve("base-copy.tmp");
        Files.write(baseCopy, base);
        String remoteMd5 = FileChangeDetector.calculateMD5(baseCopy.toFile());

        when(mockProtocol.getTimeout()).thenReturn(30000);
        when(mockProtocol.sendFileAppend(
                        anyString(), any(), anyLong(), anyLong(), anyLong(), anyString()))
                .thenThrow(new IOException("verification failed on receiver"));
        when(mockProtocol.sendBatch(
                        anyList(),
                        anyInt(),
                        isA(BatchTransferSession.BatchProgressCallback.class),
                        any(File.class)))
                .thenReturn(true);

        SyncCoordinator coordinator = createCoordinator();
        coordinator.setExecutor(null);
        coordinator.startSyncWithPlan(
                appendPlanFor("app.log", grown.length, base.length, remoteMd5));

        // The failed append must not lose the file: it is re-sent fully via the batch path.
        verify(mockProtocol)
                .sendBatch(
                        anyList(),
                        anyInt(),
                        isA(BatchTransferSession.BatchProgressCallback.class),
                        any(File.class));
        verify(mockProtocol).sendSyncComplete();
    }

    @Test
    void performSync_appendCandidatesAreDetectedOneAtATime() throws IOException {
        byte[] baseA =
                "2026-09-01 12:00:00 INFO a log line\n"
                        .repeat(400)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] tailA =
                "2026-09-01 12:01:00 INFO appended later\n"
                        .repeat(30)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] baseB =
                "2026-09-01 12:00:00 INFO b log line\n"
                        .repeat(400)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] tailB =
                "2026-09-01 12:01:00 INFO appended later\n"
                        .repeat(30)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] grownA = new byte[baseA.length + tailA.length];
        System.arraycopy(baseA, 0, grownA, 0, baseA.length);
        System.arraycopy(tailA, 0, grownA, baseA.length, tailA.length);
        byte[] grownB = new byte[baseB.length + tailB.length];
        System.arraycopy(baseB, 0, grownB, 0, baseB.length);
        System.arraycopy(tailB, 0, grownB, baseB.length, tailB.length);
        Files.write(tempDir.resolve("a.log"), grownA);
        Files.write(tempDir.resolve("b.log"), grownB);
        Path baseCopyA = tempDir.resolve("base-copy-a.tmp");
        Files.write(baseCopyA, baseA);
        Path baseCopyB = tempDir.resolve("base-copy-b.tmp");
        Files.write(baseCopyB, baseB);
        String md5A = FileChangeDetector.calculateMD5(baseCopyA.toFile());
        String md5B = FileChangeDetector.calculateMD5(baseCopyB.toFile());

        when(mockProtocol.getTimeout()).thenReturn(30000);
        when(mockProtocol.sendFileAppend(
                        anyString(), any(), anyLong(), anyLong(), anyLong(), anyString()))
                .thenAnswer(
                        invocation -> {
                            // While a.log's tail is on the wire, b.log's prefix changes on disk.
                            // Because candidates are detected one at a time (never batched in
                            // memory), b.log must be re-evaluated against the new content and
                            // routed to the signature path instead of being sent as a stale
                            // pre-extracted tail.
                            if ("a.log".equals(invocation.getArgument(0))) {
                                Files.write(
                                        tempDir.resolve("b.log"),
                                        "rewritten while a.log is being sent\n"
                                                .repeat(500)
                                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            }
                            return false;
                        });
        when(mockProtocol.requestDeltaSignatures(anyList())).thenReturn(SignatureSet.empty());
        when(mockProtocol.sendBatch(
                        anyList(),
                        anyInt(),
                        isA(BatchTransferSession.BatchProgressCallback.class),
                        any(File.class)))
                .thenReturn(true);

        Map<String, FileChangeDetector.FileInfo> remoteInfos = new HashMap<>();
        remoteInfos.put("a.log", new FileChangeDetector.FileInfo("a.log", baseA.length, 0L, md5A));
        remoteInfos.put("b.log", new FileChangeDetector.FileInfo("b.log", baseB.length, 0L, md5B));
        SyncPreviewPlan plan =
                new SyncPreviewPlan(
                        List.of(
                                new FileChangeDetector.FileInfo("a.log", grownA.length, 1L, "h"),
                                new FileChangeDetector.FileInfo("b.log", grownB.length, 1L, "h")),
                        List.of(),
                        List.of(),
                        List.of(),
                        grownA.length + grownB.length,
                        false,
                        List.of(),
                        Set.of("a.log", "b.log"),
                        Set.of("a.log", "b.log"),
                        remoteInfos);

        SyncCoordinator coordinator = createCoordinator();
        coordinator.setExecutor(null);
        coordinator.startSyncWithPlan(plan);

        verify(mockProtocol)
                .sendFileAppend(eq("a.log"), any(), anyLong(), anyLong(), anyLong(), anyString());
        verify(mockProtocol, never())
                .sendFileAppend(eq("b.log"), any(), anyLong(), anyLong(), anyLong(), anyString());
        verify(mockProtocol)
                .requestDeltaSignatures(
                        argThat(
                                paths ->
                                        paths.size() == 1
                                                && paths.contains("b.log")
                                                && !paths.contains("a.log")));
        verify(mockProtocol)
                .sendBatch(
                        anyList(),
                        anyInt(),
                        isA(BatchTransferSession.BatchProgressCallback.class),
                        any(File.class));
        verify(mockProtocol).sendSyncComplete();
    }

    @Test
    void performSync_pureAppend_binaryFileSendsTailOnly() throws IOException {
        // Alternating null bytes guarantee the manifest classifies this as binary, so the gate
        // hash must be the raw md5 of the receiver's bytes.
        byte[] base = new byte[10 * 1024];
        for (int i = 0; i < base.length; i++) {
            base[i] = (i % 2 == 0) ? (byte) 0 : (byte) 'x';
        }
        byte[] tail = new byte[2048];
        for (int i = 0; i < tail.length; i++) {
            tail[i] = (i % 2 == 0) ? (byte) 1 : (byte) 'y';
        }
        byte[] grown = new byte[base.length + tail.length];
        System.arraycopy(base, 0, grown, 0, base.length);
        System.arraycopy(tail, 0, grown, base.length, tail.length);
        Files.write(tempDir.resolve("data.bin"), grown);
        String remoteMd5 = HashUtil.md5Hex(base);

        when(mockProtocol.getTimeout()).thenReturn(30000);
        when(mockProtocol.sendFileAppend(
                        anyString(), any(), anyLong(), anyLong(), anyLong(), anyString()))
                .thenReturn(false);

        SyncCoordinator coordinator = createCoordinator();
        coordinator.setExecutor(null);
        coordinator.startSyncWithPlan(
                appendPlanFor("data.bin", grown.length, base.length, remoteMd5));

        ArgumentCaptor<byte[]> tailCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(mockProtocol)
                .sendFileAppend(
                        eq("data.bin"),
                        tailCaptor.capture(),
                        anyLong(),
                        eq((long) base.length),
                        eq((long) grown.length),
                        anyString());
        assertArrayEquals(tail, tailCaptor.getValue(), "only the appended tail may be sent");
        verify(mockProtocol, never()).requestDeltaSignatures(anyList());
        verify(mockProtocol).sendSyncComplete();
    }

    // ========== sender: BASE_STALE rejection memo ==========

    @Test
    void performSync_rejectedBase_skipsAppendAndExchangesSignatures() throws IOException {
        byte[] base =
                "2026-09-01 12:00:00 INFO sync log line\n"
                        .repeat(400)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] tail =
                "2026-09-01 12:01:00 INFO appended later\n"
                        .repeat(30)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] grown = new byte[base.length + tail.length];
        System.arraycopy(base, 0, grown, 0, base.length);
        System.arraycopy(tail, 0, grown, base.length, tail.length);
        Files.write(tempDir.resolve("app.log"), grown);
        Path baseCopy = tempDir.resolve("base-copy.tmp");
        Files.write(baseCopy, base);
        String remoteMd5 = FileChangeDetector.calculateMD5(baseCopy.toFile());

        // A previous BASE_STALE marked this exact receiver state as rejected: the append gate
        // must skip it so the file goes through the signature exchange instead of repeating
        // the same rejected transfer on every sync.
        SignatureCache seed = new SignatureCache(new File(syncFolder, "sigcache-test.json"));
        seed.markRejected("app.log", base.length, 0L, remoteMd5);
        seed.flush();

        when(mockProtocol.getTimeout()).thenReturn(30000);
        when(mockProtocol.requestDeltaSignatures(anyList())).thenReturn(SignatureSet.empty());
        when(mockProtocol.sendBatch(
                        anyList(),
                        anyInt(),
                        isA(BatchTransferSession.BatchProgressCallback.class),
                        any(File.class)))
                .thenReturn(true);

        SyncCoordinator coordinator = createCoordinator();
        coordinator.setExecutor(null);
        coordinator.startSyncWithPlan(
                appendPlanFor("app.log", grown.length, base.length, remoteMd5));

        verify(mockProtocol, never())
                .sendFileAppend(anyString(), any(), anyLong(), anyLong(), anyLong(), anyString());
        verify(mockProtocol).requestDeltaSignatures(anyList());
        verify(mockProtocol)
                .sendBatch(
                        anyList(),
                        anyInt(),
                        isA(BatchTransferSession.BatchProgressCallback.class),
                        any(File.class));
    }

    @Test
    void performSync_baseStaleDuringSignatureExchange_memoSurvivesSessionFlush()
            throws IOException {
        byte[] data = randomBytes(9 * 1024, 21);
        Files.write(tempDir.resolve("data.bin"), data);

        // A stale entry for a path the receiver no longer has: pruning it dirties the session
        // cache, so its end-of-section flush runs after the memo arrives. Only the shared
        // session instance keeps the memo through that flush.
        SignatureCache seed = new SignatureCache(new File(syncFolder, "sigcache-test.json"));
        seed.store(
                "gone.log",
                new FileChangeDetector.FileInfo("gone.log", 500, 1L, "gone-md5"),
                SignatureUtil.compute("gone.log", randomBytes(500, 22), 64));
        seed.flush();

        SyncProtocol.Message stale =
                SyncProtocol.parseMessage("[[SYNC:BASE_STALE:data.bin:9999:0:cafebabe]]");
        assertNotNull(stale);

        when(mockProtocol.getTimeout()).thenReturn(30000);
        SyncCoordinator coordinator = createCoordinator();
        when(mockProtocol.requestDeltaSignatures(anyList()))
                .thenAnswer(
                        inv -> {
                            // Simulate the notification arriving inside the exchange's wait,
                            // as the waitForCommand handler would deliver it mid-session.
                            coordinator.handleIncomingBaseStale(stale);
                            throw new IOException("Remote rejected the transfer base");
                        });
        when(mockProtocol.sendBatch(
                        anyList(),
                        anyInt(),
                        isA(BatchTransferSession.BatchProgressCallback.class),
                        any(File.class)))
                .thenReturn(true);

        coordinator.setExecutor(null);
        coordinator.startSyncWithPlan(appendPlanFor("data.bin", data.length, 9999, "cafebabe"));

        // The memo must persist even though the session's prune-dirty flush runs after it, and
        // the pruned stale entry must be gone.
        SignatureCache persisted = new SignatureCache(new File(syncFolder, "sigcache-test.json"));
        assertTrue(
                persisted.isRejected(
                        "data.bin",
                        new FileChangeDetector.FileInfo("data.bin", 9999, 0L, "cafebabe")));
        assertNull(
                persisted.lookup(
                        "gone.log",
                        new FileChangeDetector.FileInfo("gone.log", 500, 1L, "gone-md5")));
        verify(mockProtocol).sendSyncComplete();
    }

    @Test
    void handleIncomingBaseStale_marksReceiverStateRejected() throws IOException {
        createCoordinator()
                .handleIncomingBaseStale(
                        SyncProtocol.parseMessage("[[SYNC:BASE_STALE:app.log:100:42:cafebabe]]"));

        SignatureCache cache = new SignatureCache(new File(syncFolder, "sigcache-test.json"));
        assertTrue(
                cache.isRejected(
                        "app.log",
                        new FileChangeDetector.FileInfo("app.log", 100, 42, "cafebabe")));
        assertFalse(
                cache.isRejected(
                        "app.log",
                        new FileChangeDetector.FileInfo("app.log", 100, 42, "other-md5")));
    }

    @Test
    void handleIncomingBaseStale_malformedNotificationIsIgnored() throws IOException {
        createCoordinator()
                .handleIncomingBaseStale(SyncProtocol.parseMessage("[[SYNC:BASE_STALE:a:1:2]]"));

        // No identity to pin: nothing may be recorded.
        assertFalse(new File(syncFolder, "sigcache-test.json").exists());
        assertTrue(
                postedEvents.stream()
                        .anyMatch(
                                e ->
                                        e instanceof SyncEvent.LogEvent
                                                && ((SyncEvent.LogEvent) e)
                                                        .getMessage()
                                                        .contains("malformed")),
                "the malformed notification must be logged");
    }

    @Test
    void handleIncomingBaseStale_withoutFolderIsNoop() throws IOException {
        SyncCoordinator coordinator = createCoordinatorWithFolder(() -> null);
        coordinator.handleIncomingBaseStale(
                SyncProtocol.parseMessage("[[SYNC:BASE_STALE:app.log:100:42:cafebabe]]"));

        // The notification was received and logged, but there is nowhere to record the memo.
        assertTrue(
                postedEvents.stream()
                        .anyMatch(
                                e ->
                                        e instanceof SyncEvent.LogEvent
                                                && ((SyncEvent.LogEvent) e)
                                                        .getMessage()
                                                        .contains("Remote rejected stale base")));
    }

    // ========== receiver: handleIncomingFileAppend ==========

    @Test
    void handleIncomingFileAppend_success_marksWrittenAndAcks() throws IOException {
        Files.write(
                tempDir.resolve("app.log"),
                "hello\n".repeat(100).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        // Parse a real wire message so the param layout matches sendFileAppend exactly:
        // path | size | compressed | lastModified | baseSize | finalSize | finalMd5.
        SyncProtocol.Message msg =
                SyncProtocol.parseMessage("[[SYNC:FILE_APPEND:app.log:50:false:100:600:650:abc]]");
        assertNotNull(msg);
        createCoordinator().handleIncomingFileAppend(msg);

        verify(mockProtocol).sendAck();
        verify(mockProtocol)
                .receiveFileAppend(syncFolder, "app.log", 50, false, 100L, 600L, 650L, "abc");
        verify(pendingWriteService).markWritten("app.log");
    }

    @Test
    void handleIncomingFileAppend_writeFailureQueuesReconstructedBytes() throws IOException {
        byte[] reconstructed =
                "hello\n".repeat(120).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(
                tempDir.resolve("app.log"),
                "hello\n".repeat(100).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        SyncProtocol.Message msg =
                SyncProtocol.parseMessage("[[SYNC:FILE_APPEND:app.log:50:false:100:600:720:abc]]");
        assertNotNull(msg);
        doThrow(
                        new FileWriteException(
                                "app.log", reconstructed, 100L, "locked", new IOException("lock")))
                .when(mockProtocol)
                .receiveFileAppend(
                        any(File.class),
                        anyString(),
                        anyInt(),
                        anyBoolean(),
                        anyLong(),
                        anyLong(),
                        anyLong(),
                        nullable(String.class));

        createCoordinator().handleIncomingFileAppend(msg);

        verify(pendingWriteService).enqueue(syncFolder, "app.log", reconstructed, 100L, "locked");
        verify(mockEventBus).post(isA(SyncEvent.SyncControlRefreshEvent.class));
        verify(mockEventBus, never()).post(isA(SyncEvent.SyncCompleteEvent.class));
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

        List<FileChangeDetector.FileInfo> filesToSync =
                List.of(
                        fi("ok.bin", 10 * 1024),
                        fi("conflict.bin", 10 * 1024),
                        fi("ghost.bin", 10 * 1024));
        ConflictInfo conflict =
                new ConflictInfo(
                        "conflict.bin", fi("conflict.bin", 1), fi("conflict.bin", 1), true, null);

        Set<String> candidates =
                createCoordinator()
                        .selectDeltaCandidates(
                                filesToSync,
                                manifestWith("ok.bin", "conflict.bin", "ghost.bin"),
                                List.of(conflict),
                                syncFolder);

        assertEquals(Set.of("ok.bin"), candidates);
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
        createCoordinator().handleDeltaSigRequest(List.of("../evil", "realdir", "big.bin"));

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
        when(mockProtocol.sendBatch(
                        anyList(),
                        anyInt(),
                        isA(BatchTransferSession.BatchProgressCallback.class),
                        any(File.class)))
                .thenReturn(true);

        SyncCoordinator coordinator = createCoordinator();
        coordinator.setExecutor(null);
        coordinator.startSyncWithPlan(planFor("big.bin", data.length));

        verify(mockProtocol, never())
                .sendFileDelta(anyString(), any(), anyLong(), anyLong(), anyString());
        verify(mockProtocol)
                .sendBatch(
                        anyList(),
                        anyInt(),
                        isA(BatchTransferSession.BatchProgressCallback.class),
                        any(File.class));
    }

    @Test
    void performSync_readFailureOnCandidate_fallsBackToBatch() throws IOException {
        // Candidate is in the plan but the file is absent on disk -> Files.readAllBytes throws.
        byte[] data = randomBytes(10 * 1024, 1);
        SignatureSet sigs = new SignatureSet(List.of(SignatureUtil.compute("ghost.bin", data, 64)));
        when(mockProtocol.getTimeout()).thenReturn(30000);
        when(mockProtocol.requestDeltaSignatures(anyList())).thenReturn(sigs);
        when(mockProtocol.sendBatch(
                        anyList(),
                        anyInt(),
                        isA(BatchTransferSession.BatchProgressCallback.class),
                        any(File.class)))
                .thenReturn(true);

        SyncCoordinator coordinator = createCoordinator();
        coordinator.setExecutor(null);
        coordinator.startSyncWithPlan(planFor("ghost.bin", data.length));

        verify(mockProtocol, never())
                .sendFileDelta(anyString(), any(), anyLong(), anyLong(), anyString());
        verify(mockProtocol)
                .sendBatch(
                        anyList(),
                        anyInt(),
                        isA(BatchTransferSession.BatchProgressCallback.class),
                        any(File.class));
    }

    @Test
    void performSync_beneficialCompressedDelta_logsCompressedTag() throws IOException {
        byte[] data = randomBytes(10 * 1024, 1);
        Files.write(tempDir.resolve("big.bin"), data);

        SignatureSet sigs =
                new SignatureSet(
                        List.of(
                                SignatureUtil.compute(
                                        "big.bin",
                                        data,
                                        SignatureUtil.chooseBlockSize(data.length))));
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
